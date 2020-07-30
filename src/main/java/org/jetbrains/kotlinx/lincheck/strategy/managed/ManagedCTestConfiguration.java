/*-
 * #%L
 * Lincheck
 * %%
 * Copyright (C) 2019 - 2020 JetBrains s.r.o.
 * %%
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
 * <http://www.gnu.org/licenses/lgpl-3.0.html>.
 * #L%
 */
package org.jetbrains.kotlinx.lincheck.strategy.managed;

import org.jetbrains.kotlinx.lincheck.CTestConfiguration;
import org.jetbrains.kotlinx.lincheck.execution.ExecutionGenerator;
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingStrategy;
import org.jetbrains.kotlinx.lincheck.verifier.Verifier;

import java.util.Arrays;
import java.util.List;

/**
 * Configuration for {@link ModelCheckingStrategy random search} strategy.
 */
public abstract class ManagedCTestConfiguration extends CTestConfiguration {
    public static final boolean DEFAULT_CHECK_OBSTRUCTION_FREEDOM = false;
    public static final boolean DEFAULT_ELIMINATE_LOCAL_OBJECTS = true;
    public static final int DEFAULT_HANGING_DETECTION_THRESHOLD = 30;
    public static final int DEFAULT_INVOCATIONS = 10_000;
    public static final int LIVELOCK_EVENTS_THRESHOLD = 1_000;
    public static final List<ManagedGuarantee> DEFAULT_GUARANTEES = Arrays.asList(
            // These classes use WeakHashMap, and thus, their code is non-deterministic.
            // Non-determinism should not be present in managed executions, but luckily the classes
            // can be just ignored, so that no thread context switches are added inside their methods.
            org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedGuaranteeKt.forClasses(
                    kotlinx.coroutines.internal.StackTraceRecoveryKt.class.getName(),
                    kotlinx.coroutines.internal.ExceptionsConstuctorKt.class.getName()
            ).allMethods().ignore(),
            // Some atomic primitives are common and can be analyzed from a higher level of abstraction.
            // For this purpose they are treated as if they are atomic instructions.
            org.jetbrains.kotlinx.lincheck.strategy.managed.ManagedGuaranteeKt.forClasses(TrustedAtomicPrimitivesKt::isTrustedPrimitive)
                    .allMethods()
                    .treatAsAtomic()
    );

    public final boolean checkObstructionFreedom;
    public final boolean eliminateLocalObjects;
    public final int hangingDetectionThreshold;
    public final int invocationsPerIteration;
    public final List<ManagedGuarantee> guarantees;

    public ManagedCTestConfiguration(Class<?> testClass, int iterations, int threads, int actorsPerThread, int actorsBefore,
                                           int actorsAfter, Class<? extends ExecutionGenerator> generatorClass, Class<? extends Verifier> verifierClass,
                                           boolean checkObstructionFreedom, int hangingDetectionThreshold, int invocationsPerIteration,
                                           List<ManagedGuarantee> guarantees, boolean requireStateEquivalenceCheck, boolean minimizeFailedScenario,
                                           Class<?> sequentialSpecification, long timeoutMs, boolean eliminateLocalObjects)
    {
        super(testClass, iterations, threads, actorsPerThread, actorsBefore, actorsAfter, generatorClass, verifierClass,
                requireStateEquivalenceCheck, minimizeFailedScenario, sequentialSpecification, timeoutMs);
        this.checkObstructionFreedom = checkObstructionFreedom;
        this.hangingDetectionThreshold = hangingDetectionThreshold;
        this.invocationsPerIteration = invocationsPerIteration;
        this.guarantees = guarantees;
        this.eliminateLocalObjects = eliminateLocalObjects;
    }
}
