/*
 * Lincheck
 *
 * Copyright (C) 2019 - 2022 JetBrains s.r.o.
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

package org.jetbrains.kotlinx.lincheck.strategy.managed.eventstructure

typealias EventID = Int

// TODO: maybe call it DualLabelKind?
enum class LabelKind { Request, Response, Total }

enum class SynchronizationKind { Binary, Barrier }

// TODO: make a constant for default thread ID
abstract class EventLabel(
    open val threadId: Int = 0,
) {

    abstract fun replay(label: EventLabel): Boolean

    /**
     * Synchronizes event label with another label passed as a parameter.
     * For example a write label `wlab = W(x, v)` synchronizes with a read-request label `rlab = R^{req}(x)`
     * and produces the read-response label `lab = R^{rsp}(x, v)`.
     * That is a call `rlab.synchronize(wlab)` returns `lab`.
     * Synchronize operation is expected to be associative and commutative.
     * Thus it is also declared as infix operation: `a synchronize b`.
     * In terms of synchronization algebra, non-null return value to the call `C = A.synchronize(B)`
     * means that `A \+ B = C` and consequently `A >> C` and `B >> C`
     * (read as A synchronizes with B into C, and A/B synchronizes with C respectively).
     */
    open infix fun synchronize(label: EventLabel): EventLabel? =
        when {
            (label is EmptyLabel) -> this
            else -> null
        }

    // TODO: rename?
    open infix fun aggregate(label: EventLabel): EventLabel? =
        when {
            (label is EmptyLabel) -> this
            else -> null
        }

    fun aggregatesWith(label: EventLabel): Boolean =
        aggregate(label) != null

    abstract val isRequest: Boolean

    abstract val isResponse: Boolean

    abstract val isTotal: Boolean

    // TODO: better name?
    abstract val isCompleted: Boolean

    abstract val isBinarySynchronizing: Boolean

    abstract val isBarrierSynchronizing: Boolean

    val isThreadInitializer: Boolean
        get() = isRequest && (this is ThreadStartLabel)

}

// TODO: use of word `Atomic` here is perhaps misleading?
//   Maybe rename it to `SingletonEventLabel` or something similar?
//   At least document the meaning of `Atomic` here.
abstract class AtomicEventLabel(
    threadId: Int,
    open val kind: LabelKind,
    open val syncKind: SynchronizationKind,
    override val isCompleted: Boolean,
): EventLabel(threadId) {

    override val isRequest: Boolean
        get() = (kind == LabelKind.Request)

    override val isResponse: Boolean
        get() = (kind == LabelKind.Response)

    override val isTotal: Boolean
        get() = (kind == LabelKind.Total)

    override val isBinarySynchronizing: Boolean
        get() = (syncKind == SynchronizationKind.Binary)

    override val isBarrierSynchronizing: Boolean
        get() = (syncKind == SynchronizationKind.Barrier)

}

// TODO: rename to BarrierRaceException?
class InvalidBarrierSynchronizationException(message: String): Exception(message)

data class EmptyLabel(
    override val threadId: Int = 0
): AtomicEventLabel(
    threadId = threadId,
    kind = LabelKind.Total,
    syncKind = SynchronizationKind.Binary,
    isCompleted = true
) {

    override fun replay(label: EventLabel): Boolean =
        (this == label)

    override fun synchronize(label: EventLabel) = label

    override fun aggregate(label: EventLabel) = label

    override fun toString(): String = "Empty"

}

abstract class ThreadEventLabel(
    threadId: Int,
    kind: LabelKind,
    syncKind: SynchronizationKind,
    isCompleted: Boolean
): AtomicEventLabel(threadId, kind, syncKind, isCompleted)

data class ThreadForkLabel(
    override val threadId: Int,
    val forkThreadIds: Set<Int>,
): ThreadEventLabel(
    threadId = threadId,
    kind = LabelKind.Total,
    syncKind = SynchronizationKind.Binary,
    isCompleted = true,
) {

    override fun replay(label: EventLabel): Boolean =
        (this == label)

    override fun synchronize(label: EventLabel): EventLabel? {
        if (label is ThreadStartLabel && label.isRequest && label.threadId in forkThreadIds)
            return ThreadStartLabel(
                threadId = label.threadId,
                kind = LabelKind.Response
            )
        return super.synchronize(label)
    }

    override fun toString(): String =
        "ThreadFork(${forkThreadIds})"

}

data class ThreadStartLabel(
    override val threadId: Int,
    override val kind: LabelKind
): ThreadEventLabel(
    threadId = threadId,
    kind = kind,
    syncKind = SynchronizationKind.Binary,
    isCompleted = true,
) {

    override fun replay(label: EventLabel): Boolean =
        (this == label)

    override fun synchronize(label: EventLabel): EventLabel? {
        if (label is ThreadForkLabel)
            return label.synchronize(this)
        return super.synchronize(label)
    }

    override fun aggregate(label: EventLabel): EventLabel? {
        if (isRequest && label.isResponse &&
            label is ThreadStartLabel && threadId == label.threadId)
            return ThreadStartLabel(threadId, LabelKind.Total)
        return super.aggregate(label)
    }

    override fun toString(): String =
        "ThreadStart"

}

data class ThreadFinishLabel(
    override val threadId: Int,
    val finishedThreadIds: Set<Int> = setOf(threadId)
): ThreadEventLabel(
    threadId = threadId,
    kind = LabelKind.Total,
    syncKind = SynchronizationKind.Barrier,
    isCompleted = true,
) {

    override fun replay(label: EventLabel): Boolean =
        (this == label)

    override fun synchronize(label: EventLabel): EventLabel? {
        // TODO: handle cases of invalid synchronization:
        //  - when there are multiple ThreadFinish labels with the same thread id
        //  - when thread finishes outside of matching ThreadFork/ThreadJoin scope
        //  In order to handle the last case we need to add `scope` parameter to Thread labels?.
        //  Throw `InvalidBarrierSynchronizationException` in these cases.
        if (label is ThreadJoinLabel && label.joinThreadIds.containsAll(finishedThreadIds))
            return ThreadJoinLabel(
                threadId = label.threadId,
                kind = LabelKind.Response,
                joinThreadIds = label.joinThreadIds - finishedThreadIds,
            )
        if (label is ThreadFinishLabel)
            return ThreadFinishLabel(
                threadId = threadId,
                finishedThreadIds = finishedThreadIds + label.finishedThreadIds
            )
        return super.synchronize(label)
    }

    override fun toString(): String =
        "ThreadFinish"

}

data class ThreadJoinLabel(
    override val threadId: Int,
    override val kind: LabelKind,
    val joinThreadIds: Set<Int>,
): ThreadEventLabel(
    threadId = threadId,
    kind = kind,
    syncKind = SynchronizationKind.Barrier,
    isCompleted = (kind == LabelKind.Response) implies joinThreadIds.isEmpty()
) {

    override fun replay(label: EventLabel): Boolean =
        (this == label)

    override fun synchronize(label: EventLabel): EventLabel? {
        if (label is ThreadFinishLabel)
            return label.synchronize(this)
        return super.synchronize(label)
    }

    override fun aggregate(label: EventLabel): EventLabel? {
        if (isRequest && label.isResponse &&
            label is ThreadJoinLabel && threadId == label.threadId &&
            label.joinThreadIds.isEmpty())
            return ThreadJoinLabel(threadId, LabelKind.Total, setOf())
        return super.aggregate(label)
    }

    override fun toString(): String =
        "ThreadJoin(${joinThreadIds})"
}

enum class MemoryAccessKind { Read, Write }

data class MemoryAccessLabel(
    override val threadId: Int,
    override val kind: LabelKind,
    val accessKind: MemoryAccessKind,
    val typeDesc: String,
    val memId: Int,
    var value: Any?,
    val isExclusive: Boolean = false
): AtomicEventLabel(
    threadId = threadId,
    kind = kind,
    syncKind = SynchronizationKind.Binary,
    isCompleted = true
) {

    val isRead: Boolean = (accessKind == MemoryAccessKind.Read)

    val isWrite: Boolean = (accessKind == MemoryAccessKind.Write)

    init {
        require((isRead && isRequest) implies (value == null))
        require(isWrite implies isTotal)
    }

    fun equalUpToValue(label: MemoryAccessLabel): Boolean =
        (threadId == label.threadId) &&
        (kind == label.kind) &&
        (accessKind == label.accessKind) &&
        (typeDesc == label.typeDesc) &&
        (memId == label.memId) &&
        (isExclusive == label.isExclusive)

    override fun replay(label: EventLabel): Boolean {
        if (label is MemoryAccessLabel && equalUpToValue(label)) {
            value = label.value
            return true
        }
        return false
    }

    override fun synchronize(label: EventLabel): EventLabel? {
        // TODO: perform dynamic type-check of `typeDesc`
        val writeReadSync = { writeLabel : MemoryAccessLabel, readLabel : MemoryAccessLabel ->
            MemoryAccessLabel(
                threadId = readLabel.threadId,
                kind = LabelKind.Response,
                accessKind = MemoryAccessKind.Read,
                typeDesc = writeLabel.typeDesc,
                memId = writeLabel.memId,
                value = writeLabel.value,
                isExclusive = readLabel.isExclusive,
            )
        }
        if ((label is MemoryAccessLabel) && (memId == label.memId)) {
            if (isWrite && label.isRead && label.isRequest)
                return writeReadSync(this, label)
            if (label.isWrite && isRead && isRequest)
                return writeReadSync(label, this)
        }
        return super.synchronize(label)
    }

    override fun aggregate(label: EventLabel): EventLabel? {
        // TODO: perform dynamic type-check of `typeDesc`
        if (isRead && isRequest && value == null
            && label is MemoryAccessLabel && label.isRead && label.isResponse
            && threadId == label.threadId && memId == label.memId && isExclusive == label.isExclusive)
            return MemoryAccessLabel(
                threadId = threadId,
                kind = LabelKind.Total,
                accessKind = MemoryAccessKind.Read,
                typeDesc = typeDesc,
                memId = memId,
                value = label.value,
                isExclusive = isExclusive
            )
        if (isRead && isTotal && isExclusive
            && label is MemoryAccessLabel && label.isWrite && label.isExclusive
            && threadId == label.threadId && memId == label.memId)
            return ReadModifyWriteMemoryAccessLabel(this, label)
        return super.aggregate(label)
    }

    // TODO: move to common class for type descriptor logic
    private val valueString: String = when (typeDesc) {
        // for primitive types just print the value
        "I", "Z", "B", "C", "S", "J", "D", "F" -> value.toString()
        // for object types we should not call `toString` because
        // it itself can be transformed and instrumented to call
        // `onSharedVariableRead`, `onSharedVariableWrite` or similar methods,
        // calling those would recursively create new events
        // TODO: perhaps, there is better workaround for this problem?
        else -> if (value == null) "null"
                else (value as Any).javaClass.name + '@' + Integer.toHexString(value.hashCode())
    }

    override fun toString(): String {
        val kindString = when (kind) {
            LabelKind.Request -> "^req"
            LabelKind.Response -> "^rsp"
            LabelKind.Total -> ""
        }
        val exclString = if (isExclusive) "_ex" else ""
        return "${accessKind}${kindString}${exclString}(${memId}, ${valueString})"
    }

}

// TODO: rename?
abstract class CompoundEventLabel(
    threadId: Int,
    val labels: List<AtomicEventLabel>,
) : EventLabel(threadId) {

    override val isRequest: Boolean
        get() = labels.any { it.isRequest }

    override val isResponse: Boolean
        get() = labels.any { it.isResponse }

    override val isTotal: Boolean
        get() = labels.any { it.isTotal }

    override val isCompleted: Boolean
        get() = labels.all { it.isCompleted }

    override val isBinarySynchronizing: Boolean
        get() = labels.any { it.isBinarySynchronizing }

    override val isBarrierSynchronizing: Boolean
        get() = labels.any { it.isBinarySynchronizing }

}

// TODO: MemoryAccessLabel and ReadModifyWriteMemoryAccessLabel likely
//   should have common ancestor in the hierarchy?
data class ReadModifyWriteMemoryAccessLabel(
    val readLabel: MemoryAccessLabel,
    val writeLabel: MemoryAccessLabel,
) : CompoundEventLabel(readLabel.threadId, listOf(readLabel, writeLabel)) {

    init {
        require(readLabel.isRead && readLabel.isExclusive)
        require(writeLabel.isWrite && writeLabel.isExclusive)
        require(readLabel.threadId == writeLabel.threadId)
        require(readLabel.memId == writeLabel.memId)
        // TODO: also check types
        require(readLabel.isTotal && writeLabel.isTotal)
    }

    val typeDesc: String = readLabel.typeDesc

    val memId: Int = readLabel.memId

    val readValue: Any?
        get() = readLabel.value

    val writeValue: Any?
        get() = writeLabel.value

    override fun replay(label: EventLabel): Boolean {
        if (label is ReadModifyWriteMemoryAccessLabel &&
            readLabel.equalUpToValue(label.readLabel) &&
            writeLabel.equalUpToValue(label.writeLabel)) {
            readLabel.value = label.readValue
            writeLabel.value = label.writeValue
            return true
        }
        return false
    }

    override fun synchronize(label: EventLabel): EventLabel? {
        return if (label is EmptyLabel) this else writeLabel.synchronize(label)
    }

    override fun aggregate(label: EventLabel): EventLabel? {
        return super.aggregate(label)
    }

    override fun toString(): String =
        "Update(${memId}, ${readValue}, ${writeValue})"

}