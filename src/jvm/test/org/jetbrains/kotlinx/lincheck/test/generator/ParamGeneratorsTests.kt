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

package org.jetbrains.kotlinx.lincheck.test.generator

import junit.framework.Assert.assertTrue
import org.jetbrains.kotlinx.lincheck.LincheckAssertionError
import org.jetbrains.kotlinx.lincheck.LoggingLevel
import org.jetbrains.kotlinx.lincheck.RandomProvider
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.annotations.Param
import org.jetbrains.kotlinx.lincheck.check
import org.jetbrains.kotlinx.lincheck.paramgen.IntGen
import org.jetbrains.kotlinx.lincheck.paramgen.StringGen
import org.jetbrains.kotlinx.lincheck.strategy.managed.modelchecking.ModelCheckingOptions
import org.junit.Test

/**
 * This test checks that parameter generators random use different seeds than executions generator.
 * This test fails if seeds are equals.
 * [Corresponding bug description](https://github.com/Kotlin/kotlinx-lincheck/issues/120).
 */
@Param(name = "key", gen = IntGen::class, conf = "0:1")
class GeneratorSeedTest {
    private val array = IntArray(size = 2)

    @Operation
    fun get(@Param(name = "key") key: Int) = array[key]

    @Operation
    fun inc(@Param(name = "key") key: Int) {
        array[key] += 1
    }

    @Test(expected = LincheckAssertionError::class)
    fun test() = ModelCheckingOptions().logLevel(LoggingLevel.INFO).check(this::class)

}

/**
 *  Test checks that method with both parameters of the same type annotated with [Param] won't receive same values all the time
 */
@Param(name = "key", gen = IntGen::class, conf = "0:1000")
class MethodParameterGenerationTestWithBothParametersAnnotated {
    @Operation
    fun operation(@Param(name = "key") first: Int, @Param(name = "key") second: Int) =
        checkParameters(first, second)

    @Test(expected = IllegalStateException::class)
    fun test() = ModelCheckingOptions().logLevel(LoggingLevel.INFO).check(this::class)

}

/**
 *  Test checks that method with both parameters of the same type, where the first one is annotated with [Param],
 *  won't receive same values all the time.
 */
@Param(name = "key", gen = IntGen::class, conf = "0:1000")
class MethodParameterGenerationTestWithFirstParameterAnnotated {

    @Operation
    fun operation(@Param(name = "key") first: Int, second: Int) = checkParameters(first, second)

    @Test(expected = IllegalStateException::class)
    fun test() = ModelCheckingOptions().logLevel(LoggingLevel.INFO).check(this::class)

}

/**
 *  Test checks that method with both parameters of the same type, where the second one is annotated with [Param],
 *  won't receive same values all the time.
 */
@Param(name = "key", gen = IntGen::class, conf = "0:1000")
class MethodParameterGenerationTestWithSecondParameterAnnotated {
    @Operation
    fun operation(first: Int, @Param(name = "key") second: Int) = checkParameters(first, second)

    @Test(expected = IllegalStateException::class)
    fun test() = ModelCheckingOptions().logLevel(LoggingLevel.INFO).check(this::class)

}

/**
 *  Test checks that method with both parameters of the same type won't receive same values all the time
 */
class MethodParameterGenerationTest {
    @Operation
    fun operation(first: Int, second: Int) = checkParameters(first, second)

    @Test(expected = IllegalStateException::class)
    fun test() = ModelCheckingOptions().logLevel(LoggingLevel.INFO).check(this::class)

}

private object DifferentParametersAsExpectedException : Exception()

private fun checkParameters(first: Int, second: Int) {
    if (first != second) {
        throw DifferentParametersAsExpectedException
    }
}

class StringParamGeneratorTest {

    @Test
    fun `should eventually increase generated word length`() {
        val alphabet = "abcdef"
        val maxStringLength = 10

        val randomProvider = RandomProvider()
        val alphabetSet = alphabet.toHashSet()
        val gen = StringGen(randomProvider, "$maxStringLength:$alphabet")

        val generatedStrings = (0 until 500).map {
            gen.generate()
        }

        // check all symbols are in alphabet
        generatedStrings.forEach { word -> assertTrue(word.all { it in alphabetSet }) }

        // check that the size does not decrease
        val generatesStringsLengths = generatedStrings.map { it.length }
        generatesStringsLengths.windowed(2).forEach { (prevLength, nextLength) ->
            assertTrue(prevLength <= nextLength)
        }

        // check size eventually increases
        assertTrue(generatesStringsLengths.groupBy { it }.size > 1)
    }

}

/**
 * This test ensures that parameter generator dynamically expanding range
 * will be shrunk to start size after each scenario run.
 *
 * [Corresponding issue description](https://github.com/Kotlin/kotlinx-lincheck/issues/179).
 */
class ParamGeneratorResetBetweenScenariosTest {

    @Test
    fun test() {
        ModelCheckingOptions()
            .threads(2)
            .actorsPerThread(5)
            .iterations(30)
            .logLevel(LoggingLevel.INFO).check(this::class)
    }

    @Operation
    @Suppress("UNUSED")
    @Synchronized
    fun operation(value: Int) = check(value in -10 .. 10)

}