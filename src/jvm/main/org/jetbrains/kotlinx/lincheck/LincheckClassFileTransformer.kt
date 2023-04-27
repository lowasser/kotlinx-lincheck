/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2023 JetBrains s.r.o.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Lesser Public License for more details.
 *
 * You should have received a copy of the GNU General Lesser Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/lgpl-3.0.html>
 */

package org.jetbrains.kotlinx.lincheck

import net.bytebuddy.*
import net.bytebuddy.agent.*
import net.bytebuddy.dynamic.*
import net.bytebuddy.dynamic.loading.*
import net.bytebuddy.pool.*
import org.objectweb.asm.*
import org.objectweb.asm.Opcodes.*
import org.objectweb.asm.commons.*
import org.objectweb.asm.commons.GeneratorAdapter.*
import org.objectweb.asm.commons.InstructionAdapter.*
import sun.nio.ch.lincheck.*
import java.lang.instrument.*
import java.security.*
import java.util.*
import kotlin.collections.set
import kotlin.reflect.*
import kotlin.reflect.jvm.*

internal object TransformationInjectionsInitializer {
    private var initialized = false

    @Synchronized
    fun initialize() {
        if (initialized) return
        val typePool: TypePool = TypePool.Default.ofSystemLoader()

        listOf(
            "kotlin.jvm.internal.Intrinsics",
            "sun.nio.ch.lincheck.WeakIdentityHashMap",
            "sun.nio.ch.lincheck.WeakIdentityHashMap\$Ref",
            "sun.nio.ch.lincheck.AtomicFieldNameMapper",
            "sun.nio.ch.lincheck.FinalFields",
            "sun.nio.ch.lincheck.CodeLocations",
            "sun.nio.ch.lincheck.SharedEventsTracker",
            "sun.nio.ch.lincheck.TestThread",
            "sun.nio.ch.lincheck.Injections",
        ).forEach { className ->
            ByteBuddy().redefine<Any>(
                typePool.describe(className).resolve(),
                ClassFileLocator.ForClassLoader.ofSystemLoader()
            ).make().allTypes.let {
                ClassInjector.UsingUnsafe.ofBootLoader().inject(it)
            }
        }

        initialized = true
    }
}


internal inline fun <R> withLincheckTransformer(block: () -> R): R {
    LincheckClassFileTransformer.install()
    return try {
        block()
    } finally {
        LincheckClassFileTransformer.uninstall()
    }
}

object LincheckClassFileTransformer : ClassFileTransformer {
    private val transformedClasses = HashMap<Any, ByteArray>()
    private val oldClasses = HashMap<Any, ByteArray>()

    private val instrumentation = ByteBuddyAgent.install()

    internal fun install() {
        TransformationInjectionsInitializer.initialize()
        instrumentation.addTransformer(LincheckClassFileTransformer, true)
        val loadedClasses = instrumentation.allLoadedClasses
            .filter(instrumentation::isModifiableClass)
            .filter { shouldTransform(it.name) }
        try {
            instrumentation.retransformClasses(*loadedClasses.toTypedArray())
        } catch (t: Throwable) {
            loadedClasses.forEach {
                try {
                    instrumentation.retransformClasses(it)
                } catch (t: Throwable) {
                    System.err.println("Failed to re-transform $it")
                    t.printStackTrace()
                }
            }
        }
    }

    internal fun uninstall() {
        instrumentation.removeTransformer(LincheckClassFileTransformer)
        val classDefinitions = instrumentation.allLoadedClasses.mapNotNull { clazz ->
            val bytes = oldClasses[classKey(clazz.classLoader, clazz.name)]
            bytes?.let { ClassDefinition(clazz, it) }
        }
        instrumentation.redefineClasses(*classDefinitions.toTypedArray())
    }

    override fun transform(
        loader: ClassLoader?,
        className: String,
        classBeingRedefined: Class<*>?,
        protectionDomain: ProtectionDomain?,
        classfileBuffer: ByteArray
    ): ByteArray {
        Injections.enterIgnoredSection()
        try {
            synchronized(LincheckClassFileTransformer) {
                if (!shouldTransform(className.canonicalClassName)) return classfileBuffer
                return transformedClasses.computeIfAbsent(classKey(loader, className)) {
                    oldClasses[classKey(loader, className)] = classfileBuffer
                    val reader = ClassReader(classfileBuffer)
                    val writer = ClassWriter(reader, ClassWriter.COMPUTE_FRAMES)
                    try {
                        reader.accept(LincheckClassVisitor(writer), ClassReader.EXPAND_FRAMES)
                        writer.toByteArray()
                    } catch (e: Exception) {
                        System.err.println("Unable to transform $className")
                        e.printStackTrace()
                        classfileBuffer
                    }
                }
            }
        } finally {
            Injections.leaveIgnoredSection()
        }
    }

    private fun shouldTransform(className: String): Boolean {
        if (className.contains("ClassLoader")) return true
        if (className.startsWith("sun.nio.ch.lincheck.")) return false
        if (className == "kotlin.collections.ArraysKt___ArraysKt") return false
        if (className == "kotlin.collections.CollectionsKt___CollectionsKt") return false

        if (className.startsWith("kotlinx.atomicfu.")) return false
        if (className.startsWith("sun.nio.ch.")) return false
        if (className.startsWith("org.gradle.")) return false
        if (className.startsWith("worker.org.gradle.")) return false
        if (className.startsWith("org.objectweb.asm.")) return false
        if (className.startsWith("net.bytebuddy.")) return false
        if (className.startsWith("org.junit.")) return false
        if (className.startsWith("junit.framework.")) return false
        if (className.startsWith("com.sun.tools.")) return false
        if (className.startsWith("java.util.")) {
            if (className.startsWith("java.util.zip")) return false
            if (className.startsWith("java.util.regex")) return false
            if (className.startsWith("java.util.jar")) return false
            if (className.startsWith("java.util.Immutable")) return false
            if (className.startsWith("java.util.logging")) return false
            if (className.startsWith("java.util.ServiceLoader")) return false
            if (className.startsWith("java.util.concurrent.atomic.") && className.contains("Atomic")) return false
            if (className.startsWith("java.util.function.")) return false
            if (className.contains("Exception")) return false
            return true
        }

        if (className.startsWith("sun.") ||
            className.startsWith("java.") ||
            className.startsWith("jdk.internal.") ||
            className.startsWith("kotlin.") &&
            !className.startsWith("kotlin.collections.") &&  // transform kotlin collections
            !(className.startsWith("kotlin.jvm.internal.Array") && className.contains("Iterator")) &&
            !className.startsWith("kotlin.ranges.") ||
            className.startsWith("com.intellij.rt.coverage.") ||
            className.startsWith("org.jetbrains.kotlinx.lincheck.") && !className.startsWith("org.jetbrains.kotlinx.lincheck.test.") ||
            className.startsWith("kotlinx.coroutines.DispatchedTask")
        ) return false

        return true
    }
}

private fun classKey(loader: ClassLoader?, className: String) =
    if (loader == null) className
    else loader to className


internal class LincheckClassVisitor(cw: ClassWriter) : ClassVisitor(ASM_API, cw) {
    private lateinit var className: String
    private var classVersion = 0
    private var fileName: String? = null

    override fun visitField(
        access: Int,
        fieldName: String,
        descriptor: String?,
        signature: String?,
        value: Any?
    ): FieldVisitor {
        if (!className.contains("ClassLoader") && access and ACC_FINAL != 0) {
            FinalFields.addFinalField(className, fieldName)
        }
        return super.visitField(access, fieldName, descriptor, signature, value)
    }

    override fun visit(
        version: Int,
        access: Int,
        name: String,
        signature: String?,
        superName: String,
        interfaces: Array<String>
    ) {
        className = name
        classVersion = version
        super.visit(version, access, name, signature, superName, interfaces)
    }

    override fun visitSource(source: String, debug: String?) {
        fileName = source
        super.visitSource(source, debug)
    }

    override fun visitMethod(
        access: Int,
        methodName: String,
        desc: String,
        signature: String?,
        exceptions: Array<String>?
    ): MethodVisitor {
        var mv = super.visitMethod(access, methodName, desc, signature, exceptions)
        if (access and ACC_NATIVE != 0) return mv
        if (className.contains("ClassLoader")) {
            if (methodName == "loadClass") mv = ClassLoaderTransformer(methodName, GeneratorAdapter(mv, access, methodName, desc))
            return mv
        }
        if (methodName == "<clinit>" || methodName == "<init>") {
            mv = AFUTrackingTransformer(methodName, GeneratorAdapter(mv, access, methodName, desc))
            return IgnoreClassInitializationTransformer(methodName, GeneratorAdapter(mv, access, methodName, desc))
        }
        mv = JSRInlinerAdapter(mv, access, methodName, desc, signature, exceptions)
        mv = TryCatchBlockSorter(mv, access, methodName, desc, signature, exceptions)
        mv = CoroutineCancellabilitySupportMethodTransformer(mv, access, methodName, desc)
        mv = MonitorEnterAndExitTransformer(methodName, GeneratorAdapter(mv, access, methodName, desc))
        if (access and ACC_SYNCHRONIZED != 0) {
            mv = SynchronizedMethodTransformer(methodName, GeneratorAdapter(mv, access, methodName, desc), classVersion)
        }
        mv = AFUTrackingTransformer(methodName, GeneratorAdapter(mv, access, methodName, desc))
        mv = WaitNotifyTransformer(methodName, GeneratorAdapter(mv, access, methodName, desc))
        mv = ParkUnparkTransformer(methodName, GeneratorAdapter(mv, access, methodName, desc))
        mv = run {
            val sv = SharedVariableAccessMethodTransformer(methodName, GeneratorAdapter(mv, access, methodName, desc))
            val aa = AnalyzerAdapter(className, access, methodName, desc, sv)
            sv.analyzer = aa
            aa
        }
        mv = MethodCallTransformer(methodName, GeneratorAdapter(mv, access, methodName, desc))
        mv = DeterministicHashCodeTransformer(methodName, GeneratorAdapter(mv, access, methodName, desc))
        mv = DeterministicTimeTransformer(GeneratorAdapter(mv, access, methodName, desc))
        mv = DeterministicRandomTransformer(methodName, GeneratorAdapter(mv, access, methodName, desc))
        return mv
    }

    private class CoroutineCancellabilitySupportMethodTransformer(
        mv: MethodVisitor,
        access: Int,
        methodName: String?,
        desc: String?
    ) : AdviceAdapter(ASM_API, mv, access, methodName, desc) {
        override fun visitMethodInsn(
            opcodeAndSource: Int,
            className: String?,
            methodName: String?,
            descriptor: String?,
            isInterface: Boolean
        ) {
            val isGetResult =
                ("kotlinx/coroutines/CancellableContinuation" == className || "kotlinx/coroutines/CancellableContinuationImpl" == className)
                        && "getResult" == methodName
            if (isGetResult) {
                dup()
                invokeStatic(Injections::storeCancellableContinuation)
            }
            super.visitMethodInsn(opcodeAndSource, className, methodName, descriptor, isInterface)
        }
    }

    /**
     * Adds invocations of ManagedStrategy methods before monitorenter and monitorexit instructions
     */
    private inner class MonitorEnterAndExitTransformer(mname: String, adapter: GeneratorAdapter) :
        ManagedStrategyMethodVisitor(mname, adapter) {
        override fun visitInsn(opcode: Int) = adapter.run {
            when (opcode) {
                MONITORENTER -> {
                    invokeIfInTestingCode(
                        original = { monitorEnter() },
                        code = {
                            loadNewCodeLocationId()
                            invokeStatic(Injections::lock)
                        }
                    )
                }

                MONITOREXIT -> {
                    invokeIfInTestingCode(
                        original = { monitorExit() },
                        code = {
                            loadNewCodeLocationId()
                            invokeStatic(Injections::unlock)
                        }
                    )
                }

                else -> visitInsn(opcode)
            }
        }
    }

    /**
     * Replace "method(...) {...}" with "method(...) {synchronized(this) {...} }"
     */
    private inner class SynchronizedMethodTransformer(
        methodName: String,
        mv: GeneratorAdapter,
        private val classVersion: Int
    ) : ManagedStrategyMethodVisitor(methodName, mv) {
        private val isStatic: Boolean = adapter.access and ACC_STATIC != 0
        private val tryLabel = Label()
        private val catchLabel = Label()

        override fun visitCode() = adapter.run {
            invokeIfInTestingCode(
                original = {},
                code = {
                    loadSynchronizedMethodMonitorOwner()
                    monitorExit()
                }
            )
            visitLabel(tryLabel)
            invokeIfInTestingCode(
                original = {},
                code = {
                    loadSynchronizedMethodMonitorOwner()
                    loadNewCodeLocationId()
                    invokeStatic(Injections::lock)
                }
            )
            visitCode()
        }

        override fun visitMaxs(maxStack: Int, maxLocals: Int) = adapter.run {
            visitLabel(catchLabel)
            invokeIfInTestingCode(
                original = {},
                code = {
                    loadSynchronizedMethodMonitorOwner()
                    loadNewCodeLocationId()
                    invokeStatic(Injections::unlock)
                    loadSynchronizedMethodMonitorOwner()
                    monitorEnter()
                }
            )
            throwException()
            visitTryCatchBlock(tryLabel, catchLabel, catchLabel, null)
            visitMaxs(maxStack, maxLocals)
        }

        override fun visitInsn(opcode: Int) = adapter.run {
            when (opcode) {
                ARETURN, DRETURN, FRETURN, IRETURN, LRETURN, RETURN -> {
                    invokeIfInTestingCode(
                        original = {},
                        code = {
                            loadSynchronizedMethodMonitorOwner()
                            loadNewCodeLocationId()
                            invokeStatic(Injections::unlock)
                            loadSynchronizedMethodMonitorOwner()
                            monitorEnter()
                        }
                    )
                }
            }
            visitInsn(opcode)
        }

        private fun loadSynchronizedMethodMonitorOwner() = adapter.run {
            if (isStatic) {
                val classType = Type.getType("L$className;")
                if (classVersion >= V1_5) {
                    visitLdcInsn(classType)
                } else {
                    visitLdcInsn(classType.className)
                    invokeStatic(Injections::enterIgnoredSection)
                    invokeStatic(CLASS_TYPE, CLASS_FOR_NAME_METHOD)
                    invokeStatic(Injections::leaveIgnoredSection)
                }
            } else {
                loadThis()
            }
        }
    }

    /**
     * Makes all <clinit> sections ignored, because managed execution in <clinit> can lead to a deadlock.
     * SharedVariableAccessMethodTransformer should be earlier than this transformer not to create switch points before
     * beforeIgnoredSectionEntering invocations.
     */
    private inner class IgnoreClassInitializationTransformer(methodName: String, adapter: GeneratorAdapter) :
        ManagedStrategyMethodVisitor(methodName, adapter) {

        init {
            check(methodName == "<clinit>" || methodName == "<init>")
        }

        override fun visitCode() = adapter.run {
            invokeStatic(Injections::enterIgnoredSection)
            visitCode()
        }

        override fun visitInsn(opcode: Int) = adapter.run {
            when (opcode) {
                ARETURN, DRETURN, FRETURN, IRETURN, LRETURN, RETURN -> {
                    invokeStatic(Injections::leaveIgnoredSection)
                }
            }
            visitInsn(opcode)
        }
    }

    private inner class ClassLoaderTransformer(methodName: String, adapter: GeneratorAdapter) :
        ManagedStrategyMethodVisitor(methodName, adapter) {

        init {
            check(methodName == "loadClass")
        }

        override fun visitCode() = adapter.run {
            invokeStatic(Injections::enterIgnoredSection)
            visitCode()
        }

        override fun visitInsn(opcode: Int) = adapter.run {
            when (opcode) {
                ARETURN, DRETURN, FRETURN, IRETURN, LRETURN, RETURN -> {
                    invokeStatic(Injections::leaveIgnoredSection)
                }
            }
            visitInsn(opcode)
        }
    }

    /**
     * Replaces Object.hashCode and Any.hashCode invocations with just zero.
     * This transformer prevents non-determinism due to the native hashCode implementation,
     * which typically returns memory address of the object. There is no guarantee that
     * memory addresses will be the same in different runs.
     */
    private inner class DeterministicHashCodeTransformer(methodName: String, adapter: GeneratorAdapter) :
        ManagedStrategyMethodVisitor(methodName, adapter) {
        override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) =
            adapter.run {
                if (owner == "java/lang/Object" && name == "hashCode") {
                    invokeIfInTestingCode(
                        original = {
                            visitMethodInsn(opcode, owner, name, desc, itf)
                        },
                        code = {
                            invokeStatic(Injections::deterministicHashCode)
                        }
                    )
                } else {
                    visitMethodInsn(opcode, owner, name, desc, itf)
                }
            }
    }

    /**
     * Replaces `System.nanoTime` and `System.currentTimeMillis` with stubs to prevent non-determinism
     */
    private class DeterministicTimeTransformer(val adapter: GeneratorAdapter) : MethodVisitor(ASM_API, adapter) {
        override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) =
            adapter.run {
                if (owner == "java/lang/System" && (name == "nanoTime" || name == "currentTimeMillis")) {
                    invokeIfInTestingCode(
                        original = { visitMethodInsn(opcode, owner, name, desc, itf) },
                        code = { push(1337L) } // any constant value
                    )
                    return
                }
                visitMethodInsn(opcode, owner, name, desc, itf)
            }
    }

    private companion object {
        private val randomMethods = Random::class.java.declaredMethods.map { Method.getMethod(it) }
    }

    /**
     * Makes java.util.Random and all classes that extend it deterministic.
     * In every Random method invocation replaces the owner with Random from ManagedStateHolder.
     * TODO: Kotlin's random support
     */
    private inner class DeterministicRandomTransformer(methodName: String, adapter: GeneratorAdapter) : ManagedStrategyMethodVisitor(methodName, adapter) {
        override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) =
            adapter.run {
                if (owner == "java/util/concurrent/ThreadLocalRandom" || owner == "java/util/concurrent/atomic/Striped64") {
                    if (name == "nextSecondarySeed" || name == "getProbe") { // INVOKESTATIC
                        invokeIfInTestingCode(
                            original = {
                                visitMethodInsn(opcode, owner, name, desc, itf)
                            },
                            code = {
                                invokeStatic(Injections::nextInt)
                            }
                        )
                        return
                    }
                    if (name == "advanceProbe") { // INVOKEVIRTUAL
                        invokeIfInTestingCode(
                            original = {
                                visitMethodInsn(opcode, owner, name, desc, itf)
                            },
                            code = {
                                pop()
                                invokeStatic(Injections::nextInt)
                            }
                        )
                        return
                    }
                } else if (opcode == INVOKEVIRTUAL && isRandomMethod(name, desc)) {
                    invokeIfInTestingCode(
                        original = {
                            visitMethodInsn(opcode, owner, name, desc, itf)
                        },
                        code = {
                            val arguments = storeArguments(desc)
                            val ownerLocal = newLocal(Type.getType("L$owner;"))
                            storeLocal(ownerLocal)
                            ifStatement(
                                condition = {
                                    loadLocal(ownerLocal)
                                    invokeStatic(Injections::isRandom)
                                },
                                ifClause = {
                                    invokeStatic(Injections::enterIgnoredSection)
                                    invokeStatic(Injections::deterministicRandom)
                                    loadLocals(arguments)
                                    visitMethodInsn(opcode, "java/util/Random", name, desc, itf)
                                    invokeStatic(Injections::leaveIgnoredSection)
                                },
                                elseClause = {
                                    loadLocal(ownerLocal)
                                    loadLocals(arguments)
                                    visitMethodInsn(opcode, owner, name, desc, itf)
                                }
                            )
                        }
                    )
                    return
                }
                visitMethodInsn(opcode, owner, name, desc, itf)
            }

        private fun isRandomMethod(methodName: String, desc: String): Boolean = randomMethods.any {
            it.name == methodName && it.descriptor == desc
        }
    }

    /**
     * Adds invocations of ManagedStrategy methods before park and after unpark calls
     */
    private inner class ParkUnparkTransformer(methodName: String, mv: GeneratorAdapter) :
        ManagedStrategyMethodVisitor(methodName, mv) {
        override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) =
            adapter.run {
                val isPark = owner.isUnsafe() && name == "park"
                val isUnpark = owner.isUnsafe() && name == "unpark"
                when {
                    isPark -> {
                        invokeIfInTestingCode(
                            original = {
                                visitMethodInsn(opcode, owner, name, desc, itf)
                            },
                            code = {
                                pop2() // time
                                pop() // isAbsolute
                                pop() // Unsafe
                                loadNewCodeLocationId()
                                invokeStatic(Injections::park)
                            }
                        )
                    }

                    isUnpark -> {
                        invokeIfInTestingCode(
                            original = {
                                visitMethodInsn(opcode, owner, name, desc, itf)
                            },
                            code = {
                                loadNewCodeLocationId()
                                invokeStatic(Injections::unpark)
                                pop() // pop Unsafe object
                            }
                        )
                    }

                    else -> {
                        visitMethodInsn(opcode, owner, name, desc, itf)
                    }
                }
            }
    }


    /**
     * Adds invocations of ManagedStrategy methods before reads and writes of shared variables
     */
    private inner class SharedVariableAccessMethodTransformer(methodName: String, adapter: GeneratorAdapter) :
        ManagedStrategyMethodVisitor(methodName, adapter) {

        lateinit var analyzer: AnalyzerAdapter

        override fun visitFieldInsn(opcode: Int, owner: String, fieldName: String, desc: String) = adapter.run {
            when (opcode) {
                GETSTATIC -> {
                    invokeIfInTestingCode(
                        original = {
                            visitFieldInsn(opcode, owner, fieldName, desc)
                        },
                        code = {
                            // STACK: <empty>
                            push(owner)
                            push(fieldName)
                            loadNewCodeLocationId()
                            // STACK: className: String, fieldName: String, codeLocation: Int
                            invokeStatic(Injections::beforeReadFieldStatic)
                            // STACK: owner: Object
                            visitFieldInsn(opcode, owner, fieldName, desc)
                            // STACK: value
                            invokeReadValue(Type.getType(desc))
                            // STACK: value
                        }
                    )
                }

                GETFIELD -> {
                    invokeIfInTestingCode(
                        original = {
                            visitFieldInsn(opcode, owner, fieldName, desc)
                        },
                        code = {
                            // STACK: owner: Object
                            dup()
                            // STACK: owner: Object, owner: Object
                            push(owner)
                            push(fieldName)
                            loadNewCodeLocationId()
                            // STACK: owner: Object, owner: Object, className: String, fieldName: String, codeLocation: Int
                            invokeStatic(Injections::beforeReadField)
                            // STACK: owner: Object
                            visitFieldInsn(opcode, owner, fieldName, desc)
                            // STACK: value
                            invokeReadValue(Type.getType(desc))
                            // STACK: value
                        }
                    )
                }

                PUTSTATIC -> {
                    // STACK: value: Object
                    invokeIfInTestingCode(
                        original = {},
                        code = {
                            val valueType = Type.getType(desc)
                            val valueLocal = newLocal(valueType) // we cannot use DUP as long/double require DUP2
                            storeLocal(valueLocal)
                            loadLocal(valueLocal)
                            // STACK: value: Object
                            push(owner)
                            push(fieldName)
                            loadLocal(valueLocal)
                            box(valueType)
                            loadNewCodeLocationId()
                            // STACK: value: Object, className: String, fieldName: String, value: Object, codeLocation: Int
                            invokeStatic(Injections::beforeWriteFieldStatic)
                            // STACK: value: Object
                        }
                    )
                    // STACK: value: Object
                    visitFieldInsn(opcode, owner, fieldName, desc)
                }

                PUTFIELD -> {
                    // STACK: owner: Object, value: Object
                    invokeIfInTestingCode(
                        original = {},
                        code = {
                            val valueType = Type.getType(desc)
                            val valueLocal = newLocal(valueType) // we cannot use DUP as long/double require DUP2
                            storeLocal(valueLocal)
                            // STACK: owner: Object
                            dup()
                            // STACK: owner: Object, owner: Object
                            push(className)
                            push(fieldName)
                            loadLocal(valueLocal)
                            box(valueType)
                            loadNewCodeLocationId()
                            // STACK: owner: Object, owner: Object, fieldName: String, fieldName: String, value: Object, codeLocation: Int
                            invokeStatic(Injections::beforeWriteField)
                            // STACK: owner: Object
                            loadLocal(valueLocal)
                            // STACK: owner: Object, value: Object
                        }
                    )
                    // STACK: owner: Object, value: Object
                    visitFieldInsn(opcode, owner, fieldName, desc)
                }

                else -> {
                    // All opcodes are covered above. However, in case a new one is added, Lincheck should not fail.
                    visitFieldInsn(opcode, owner, fieldName, desc)
                }
            }
        }

        override fun visitInsn(opcode: Int) = adapter.run {
            when (opcode) {
                AALOAD, LALOAD, FALOAD, DALOAD, IALOAD, BALOAD, CALOAD, SALOAD -> {
                    invokeIfInTestingCode(
                        original = {
                            visitInsn(opcode)
                        },
                        code = {
                            // STACK: array: Array, index: Int
                            val arrayElementType = getArrayElementType(opcode)
                            dup2()
                            // STACK: array: Array, index: Int, array: Array, index: Int
                            loadNewCodeLocationId()
                            // STACK: array: Array, index: Int, array: Array, index: Int, codeLocation: Int
                            invokeStatic(Injections::beforeReadArray)
                            // STACK: array: Array, index: Int
                            visitInsn(opcode)
                            // STACK: value
                            invokeReadValue(arrayElementType)
                            // STACK: value
                        }
                    )
                }

                AASTORE, IASTORE, FASTORE, BASTORE, CASTORE, SASTORE, LASTORE, DASTORE -> {
                    invokeIfInTestingCode(
                        original = {},
                        code = {
                            // STACK: array: Array, index: Int, value: Object
                            val arrayElementType = getArrayElementType(opcode)
                            val valueLocal = newLocal(arrayElementType) // we cannot use DUP as long/double require DUP2
                            storeLocal(valueLocal)
                            // STACK: array: Array, index: Int
                            dup2()
                            // STACK: array: Array, index: Int, array: Array, index: Int
                            loadLocal(valueLocal)
                            box(arrayElementType)
                            loadNewCodeLocationId()
                            // STACK: array: Array, index: Int, array: Array, index: Int, value: Object, codeLocation: Int
                            invokeStatic(Injections::beforeWriteArray)
                            // STACK: array: Array, index: Int
                            loadLocal(valueLocal)
                            // STACK: array: Array, index: Int, value: Object
                        }
                    )
                    visitInsn(opcode)
                }

                else -> {
                    visitInsn(opcode)
                }
            }
        }

        private fun GeneratorAdapter.invokeReadValue(type: Type) {
            // STACK: value
            val resultLocal = newLocal(type)
            storeLocal(resultLocal)
            loadLocal(resultLocal)
            loadLocal(resultLocal)
            // STACK: value, value
            box(type)
            invokeStatic(Injections::onReadValue)
            // STACK: value
        }

        private fun getArrayElementType(opcode: Int): Type = when (opcode) {
            // Load
            AALOAD -> getArrayAccessTypeFromStack(2) // OBJECT_TYPE
            IALOAD -> Type.INT_TYPE
            FALOAD -> Type.FLOAT_TYPE
            BALOAD -> Type.BOOLEAN_TYPE
            CALOAD -> Type.CHAR_TYPE
            SALOAD -> Type.SHORT_TYPE
            LALOAD -> Type.LONG_TYPE
            DALOAD -> Type.DOUBLE_TYPE
            // Store
            AASTORE -> getArrayAccessTypeFromStack(3) // OBJECT_TYPE
            IASTORE -> Type.INT_TYPE
            FASTORE -> Type.FLOAT_TYPE
            BASTORE -> Type.BOOLEAN_TYPE
            CASTORE -> Type.CHAR_TYPE
            SASTORE -> Type.SHORT_TYPE
            LASTORE -> Type.LONG_TYPE
            DASTORE -> Type.DOUBLE_TYPE
            else -> throw IllegalStateException("Unexpected opcode: $opcode")
        }

        /*
       * Tries to obtain the type of array elements by inspecting the type of the array itself.
       * In order to do this queries the analyzer to get the type of accessed array
       * which should lie on the stack. If the analyzer does not know the type
       * (according to the ASM docs it can happen, for example, when the visited instruction is unreachable)
       * then return null.
       */
        private fun getArrayAccessTypeFromStack(position: Int): Type {
            if (analyzer.stack == null) return OBJECT_TYPE // better than throwing an exception
            val arrayDesc = analyzer.stack[analyzer.stack.size - position]
            check(arrayDesc is String)
            val arrayType = Type.getType(arrayDesc)
            check(arrayType.sort == Type.ARRAY)
            check(arrayType.dimensions > 0)
            return Type.getType(arrayDesc.substring(1))
        }
    }

    /**
     * Adds strategy method invocations before and after method calls.
     */
    private inner class MethodCallTransformer(methodName: String, adapter: GeneratorAdapter) :
        ManagedStrategyMethodVisitor(methodName, adapter) {
        override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) = adapter.run {
            // TODO: ignore coroutine internals
            // TODO: ignore safe calls
            // TODO: do not ignore <init>
            if (isInternalCoroutineCall(owner, name) || name == "<init>" ||
                owner == "kotlin/jvm/internal/Intrinsics" || owner == "java/util/Objects" ||
                owner == "sun/nio/ch/lincheck/Injections" || owner == "java/lang/StringBuilder" ||
                owner == "java/util/Locale" || owner == "java/lang/String" ||
                owner == "org/slf4j/helpers/Util" || owner == "java/util/Properties" ||
                owner == "java/lang/Boolean" || owner == "java/lang/Integer" ||
                owner == "java/lang/Long")
            {
                visitMethodInsn(opcode, owner, name, desc, itf)
                return
            }
            invokeIfInTestingCode(
                original = {
                    visitMethodInsn(opcode, owner, name, desc, itf)
                },
                code = {
                    // STACK [INVOKEVIRTUAL]: owner, arguments
                    // STACK [INVOKESTATIC]: arguments
                    val argumentLocals = storeArguments(desc)
                    // STACK [INVOKEVIRTUAL]: owner
                    // STACK [INVOKESTATIC]: <empty>
                    when (opcode) {
                        INVOKESTATIC -> visitInsn(ACONST_NULL)
                        else -> dup()
                    }
                    // STACK [INVOKEVIRTUAL]: owner, owner
                    // STACK [INVOKESTATIC]: <empty>, null
                    push(owner)
                    push(name)
                    loadNewCodeLocationId()
                    // STACK [INVOKEVIRTUAL]: owner, owner, className, methodName, codeLocation
                    // STACK [INVOKESTATIC]: <empty>, null, className, methodName, codeLocation
                    val argumentTypes = Type.getArgumentTypes(desc)
                    when (argumentLocals.size) {
                        0 -> {
                            invokeStatic(Injections::beforeMethodCall0)
                        }
                        1 -> {
                            loadLocalsBoxed(argumentLocals, argumentTypes)
                            invokeStatic(Injections::beforeMethodCall1)
                        }
                        2 -> {
                            loadLocalsBoxed(argumentLocals, argumentTypes)
                            invokeStatic(Injections::beforeMethodCall2)
                        }
                        3 -> {
                            loadLocalsBoxed(argumentLocals, argumentTypes)
                            invokeStatic(Injections::beforeMethodCall3)
                        }
                        4 -> {
                            loadLocalsBoxed(argumentLocals, argumentTypes)
                            invokeStatic(Injections::beforeMethodCall4)
                        }
                        5 -> {
                            loadLocalsBoxed(argumentLocals, argumentTypes)
                            invokeStatic(Injections::beforeMethodCall5)
                        }
                        else -> {
                            push(argumentLocals.size) // size of the array
                            visitTypeInsn(ANEWARRAY, OBJECT_TYPE.internalName)
                            // STACK: ..., array
                            for (i in argumentLocals.indices) {
                                // STACK: ..., array
                                dup()
                                // STACK: ..., array, array
                                push(i)
                                // STACK: ..., array, array, index
                                loadLocal(argumentLocals[i])
                                // STACK: ..., array, array, index, argument[index]
                                box(argumentTypes[i])
                                arrayStore(OBJECT_TYPE)
                                // STACK: ..., array
                            }
                            // STACK: ..., array
                            invokeStatic(Injections::beforeMethodCall)
                        }
                    }
                    // STACK [INVOKEVIRTUAL]: owner, arguments
                    // STACK [INVOKESTATIC]: arguments
                    val methodCallStartLabel = newLabel()
                    val methodCallEndLabel = newLabel()
                    val handlerExceptionStartLabel = newLabel()
                    val handlerExceptionEndLabel = newLabel()
                    visitTryCatchBlock(methodCallStartLabel, methodCallEndLabel, handlerExceptionStartLabel, null)
                    visitLabel(methodCallStartLabel)
                    loadLocals(argumentLocals)
                    visitMethodInsn(opcode, owner, name, desc, itf)
                    visitLabel(methodCallEndLabel)
                    // STACK [INVOKEVIRTUAL]: owner, arguments
                    // STACK [INVOKESTATIC]: arguments
                    val resultType = Type.getReturnType(desc)
                    if (resultType == Type.VOID_TYPE) {
                        invokeStatic(Injections::onMethodCallVoidFinishedSuccessfully)
                    } else {
                        val resultLocal = newLocal(resultType)
                        storeLocal(resultLocal)
                        loadLocal(resultLocal)
                        box(resultType)
                        invokeStatic(Injections::onMethodCallFinishedSuccessfully)
                        loadLocal(resultLocal)
                    }
                    // STACK: value
                    goTo(handlerExceptionEndLabel)
                    visitLabel(handlerExceptionStartLabel)
                    dup()
                    invokeStatic(Injections::onMethodCallThrewException)
                    throwException()
                    visitLabel(handlerExceptionEndLabel)
                    // STACK: value
                }
            )
        }

        private fun isInternalCoroutineCall(owner: String, name: String) =
            owner == "kotlin/coroutines/intrinsics/IntrinsicsKt" && name == "getCOROUTINE_SUSPENDED"
    }

    /**
     * Tracks names of fields for created AFUs and saves them via ObjectManager.
     * CallStackTraceTransformer should be an earlier transformer than this transformer, because
     * this transformer reuse code locations created by CallStackTraceTransformer.
     * TODO: track other atomic constructions
     */
    private inner class AFUTrackingTransformer(methodName: String, mv: GeneratorAdapter) :
        ManagedStrategyMethodVisitor(methodName, mv) {
        override fun visitMethodInsn(opcode: Int, owner: String, mname: String, desc: String, isInterface: Boolean) = adapter.run {
            val isNewAtomicFieldUpdater = opcode == INVOKESTATIC && mname == "newUpdater" && owner.isAFU
            if (isNewAtomicFieldUpdater) {
                val nameLocal = newLocal(STRING_TYPE)
                storeTopToLocal(nameLocal) // name is the last parameter
                visitMethodInsn(opcode, owner, mname, desc, isInterface)
                dup() // copy AFU
                loadLocal(nameLocal)
                invokeStatic(Injections::onNewAtomicFieldUpdater)
            } else {
                visitMethodInsn(opcode, owner, mname, desc, isInterface)
            }
        }

        private val String.isAFU get() = startsWith("java/util/concurrent/atomic/Atomic") && endsWith("FieldUpdater")
    }


    /**
     * Adds invocations of ManagedStrategy methods before wait and after notify calls
     */
    private inner class WaitNotifyTransformer(methodName: String, mv: GeneratorAdapter) :
        ManagedStrategyMethodVisitor(methodName, mv) {
        override fun visitMethodInsn(opcode: Int, owner: String, name: String, desc: String, itf: Boolean) =
            adapter.run {
                if (opcode == INVOKEVIRTUAL) {
                    when {
                        isWait0(name, desc) -> {
                            invokeIfInTestingCode(
                                original = {
                                    visitMethodInsn(opcode, owner, name, desc, itf)
                                },
                                code = {
                                    loadNewCodeLocationId()
                                    invokeStatic(Injections::wait)
                                }
                            )
                        }

                        isWait1(name, desc) -> {
                            invokeIfInTestingCode(
                                original = {
                                    visitMethodInsn(opcode, owner, name, desc, itf)
                                },
                                code = {
                                    pop2() // timeMillis
                                    loadNewCodeLocationId()
                                    invokeStatic(Injections::waitWithTimeout)
                                }
                            )
                        }

                        isWait2(name, desc) -> {
                            invokeIfInTestingCode(
                                original = {
                                    visitMethodInsn(opcode, owner, name, desc, itf)
                                },
                                code = {
                                    pop() // timeNanos
                                    pop2() // timeMillis
                                    loadNewCodeLocationId()
                                    invokeStatic(Injections::waitWithTimeout)
                                }
                            )
                        }

                        isNotify(name, desc) -> {
                            invokeIfInTestingCode(
                                original = {
                                    visitMethodInsn(opcode, owner, name, desc, itf)
                                },
                                code = {
                                    loadNewCodeLocationId()
                                    invokeStatic(Injections::notify)
                                }
                            )
                        }

                        isNotifyAll(name, desc) -> {
                            invokeIfInTestingCode(
                                original = {
                                    visitMethodInsn(opcode, owner, name, desc, itf)
                                },
                                code = {
                                    loadNewCodeLocationId()
                                    invokeStatic(Injections::notifyAll)
                                }
                            )
                        }

                        else -> {
                            visitMethodInsn(opcode, owner, name, desc, itf)
                        }
                    }
                } else {
                    visitMethodInsn(opcode, owner, name, desc, itf)
                }
            }

        private fun isWait(opcode: Int, name: String, desc: String): Boolean {
            if (opcode == INVOKEVIRTUAL && name == "wait") {
                when (desc) {
                    "()V", "(J)V", "(JI)V" -> return true
                }
            }
            return false
        }

        private fun isWait0(mname: String, desc: String) = mname == "wait" && desc == "()V"
        private fun isWait1(mname: String, desc: String) = mname == "wait" && desc == "(J)V"
        private fun isWait2(mname: String, desc: String) = mname == "wait" && desc == "(JI)V"

        private fun isNotify(mname: String, desc: String) = mname == "notify" && desc == "()V"
        private fun isNotifyAll(mname: String, desc: String) = mname == "notifyAll" && desc == "()V"
    }

    private open inner class ManagedStrategyMethodVisitor(
        protected val methodName: String,
        val adapter: GeneratorAdapter
    ) : MethodVisitor(ASM_API, adapter) {
        private var lineNumber = 0

        protected fun loadNewCodeLocationId() {
            val stackTraceElement = StackTraceElement(className, methodName, fileName, lineNumber)
            val codeLocationId = CodeLocations.newCodeLocation(stackTraceElement)
            adapter.push(codeLocationId)
        }

        override fun visitLineNumber(line: Int, start: Label) {
            lineNumber = line
            super.visitLineNumber(line, start)
        }
    }
}

private val STRING_TYPE = Type.getType(String::class.java)
private val CLASS_TYPE = Type.getType(Class::class.java)
private val CLASS_FOR_NAME_METHOD =
    Method("forName", CLASS_TYPE, arrayOf(STRING_TYPE)) // manual, because there are several forName methods

/**
 * Returns array of locals containing given arguments.
 * STACK: param_1 param_2 ... param_n
 * RESULT STACK: (empty)
 */
private fun GeneratorAdapter.storeArguments(methodDescriptor: String): IntArray {
    val argumentTypes = Type.getArgumentTypes(methodDescriptor)
    val locals = IntArray(argumentTypes.size)
    // store all arguments
    for (i in argumentTypes.indices.reversed()) {
        locals[i] = newLocal(argumentTypes[i])
        storeLocal(locals[i], argumentTypes[i])
    }
    return locals
}

private fun GeneratorAdapter.loadLocals(locals: IntArray) {
    for (local in locals)
        loadLocal(local)
}

private fun GeneratorAdapter.loadLocalsBoxed(locals: IntArray, localTypes: Array<Type>) {
    for (i in locals.indices) {
        loadLocal(locals[i])
        box(localTypes[i])
    }
}

/**
 * Saves the top value on the stack without changing stack.
 */
private fun GeneratorAdapter.storeTopToLocal(local: Int) {
    // NB: We cannot use DUP here, as long and double require DUP2
    storeLocal(local)
    loadLocal(local)
}

private val functionToDeclaringClassMap = HashMap<KFunction<*>, Pair<Type, Method>>()

private fun GeneratorAdapter.invokeStatic(function: KFunction<*>) {
    val (clazz, method) = functionToDeclaringClassMap.computeIfAbsent(function) {
        function.javaMethod!!.let {
            Type.getType(it.declaringClass) to Method.getMethod(it)
        }
    }
    invokeStatic(clazz, method)
}

private inline fun GeneratorAdapter.ifStatement(
    condition: GeneratorAdapter.() -> Unit,
    ifClause: GeneratorAdapter.() -> Unit,
    elseClause: GeneratorAdapter.() -> Unit
) {
    val ifClauseStart = newLabel()
    val end = newLabel()
    condition()
    ifZCmp(GT, ifClauseStart)
    elseClause()
    goTo(end)
    visitLabel(ifClauseStart)
    ifClause()
    visitLabel(end)
}

private inline fun GeneratorAdapter.invokeIfInTestingCode(
    original: GeneratorAdapter.() -> Unit,
    code: GeneratorAdapter.() -> Unit
) = ifStatement(
    condition = { invokeStatic(Injections::inTestingCode) },
    ifClause = code,
    elseClause = original
)

private fun String.isUnsafe() = this == "sun/misc/Unsafe" || this == "jdk/internal/misc/Unsafe"
