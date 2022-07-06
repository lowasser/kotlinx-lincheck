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

package org.jetbrains.kotlinx.lincheck.strategy.managed.eventstruct

typealias EventID = Int

// TODO: maybe call it DualLabelKind?
enum class LabelKind { Request, Response, Total }

enum class SynchronizationKind { Binary, Barrier }

// TODO: make a constant for default thread ID
abstract class EventLabel(
    open val threadId: Int = 0,
    open val kind: LabelKind = LabelKind.Total,
) {
    open val synchKind: SynchronizationKind = SynchronizationKind.Binary

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
    open infix fun synchronize(lab: EventLabel): EventLabel? =
        when {
            (lab is EmptyLabel) -> this
            else -> null
        }

    val isRequest: Boolean
        get() = (kind == LabelKind.Request)

    val isResponse: Boolean
        get() = (kind == LabelKind.Response)

    val isTotal: Boolean
        get() = (kind == LabelKind.Total)

    open val isCompleted: Boolean = true

    val isCompetedResponse: Boolean
        get() = isCompleted && isResponse

    fun isThreadInitEvent() = isRequest && (this is ThreadStartLabel)
}

// TODO: rename to BarrierRaceException?
class InvalidBarrierSynchronizationException(message: String): Exception(message)

data class EmptyLabel(override val threadId: Int = 0): EventLabel(threadId) {
    override fun synchronize(lab: EventLabel) = lab
}

abstract class ThreadLabel(threadId: Int, kind: LabelKind = LabelKind.Total): EventLabel(threadId, kind)

data class ThreadForkLabel(
    override val threadId: Int,
    val forkThreadIds: Set<Int>,
): ThreadLabel(threadId) {
    override fun synchronize(lab: EventLabel): EventLabel? {
        if (lab is ThreadStartLabel && lab.isRequest && lab.threadId in forkThreadIds)
            return ThreadStartLabel(
                threadId = lab.threadId,
                kind = LabelKind.Response
            )
        return super.synchronize(lab)
    }
}

data class ThreadStartLabel(
    override val threadId: Int,
    override val kind: LabelKind
): ThreadLabel(threadId, kind) {

    init {
        require(isRequest || isResponse)
    }

    override fun synchronize(lab: EventLabel): EventLabel? {
        if (lab is ThreadForkLabel)
            return lab.synchronize(this)
        return super.synchronize(lab)
    }
}

data class ThreadFinishLabel(
    override val threadId: Int,
    val finishedThreadIds: Set<Int> = setOf(threadId)
): ThreadLabel(threadId) {
    override val synchKind: SynchronizationKind = SynchronizationKind.Barrier

    override fun synchronize(lab: EventLabel): EventLabel? {
        // TODO: handle cases of invalid synchronization:
        //  - when there are multiple ThreadFinish labels with the same thread id
        //  - when thread finishes outside of matching ThreadFork/ThreadJoin scope
        //  In order to handle the last case we need to add `scope` parameter to Thread labels?.
        //  Throw `InvalidBarrierSynchronizationException` in these cases.
        if (lab is ThreadJoinLabel && lab.joinThreadIds.containsAll(finishedThreadIds))
            return ThreadJoinLabel(
                threadId = lab.threadId,
                kind = LabelKind.Response,
                joinThreadIds = lab.joinThreadIds - finishedThreadIds,
            )
        if (lab is ThreadFinishLabel)
            return ThreadFinishLabel(
                threadId = threadId,
                finishedThreadIds = finishedThreadIds + lab.finishedThreadIds
            )
        return super.synchronize(lab)
    }
}

data class ThreadJoinLabel(
    override val threadId: Int,
    override val kind: LabelKind,
    val joinThreadIds: Set<Int>,
): ThreadLabel(threadId, kind) {

    init {
        require(isRequest || isResponse)
    }

    override val synchKind: SynchronizationKind = SynchronizationKind.Barrier

    override fun synchronize(lab: EventLabel): EventLabel? {
        if (lab is ThreadFinishLabel)
            return lab.synchronize(this)
        return super.synchronize(lab)
    }

    override val isCompleted: Boolean =
        isRequest || (isResponse && joinThreadIds.isEmpty())
}

enum class MemoryAccessKind { ReadRequest, ReadResponse, Write }

fun MemoryAccessKind.toLabelKind(): LabelKind =
    when (this) {
        MemoryAccessKind.ReadRequest -> LabelKind.Request
        MemoryAccessKind.ReadResponse -> LabelKind.Response
        MemoryAccessKind.Write -> LabelKind.Total
    }

data class MemoryAccessLabel(
    override val threadId: Int,
    val accessKind: MemoryAccessKind,
    val typeDesc: String,
    val memId: Int,
    val value: Any?
): EventLabel(threadId, accessKind.toLabelKind()) {
    override fun synchronize(lab: EventLabel): EventLabel? {
        return when {
            (lab is MemoryAccessLabel) && (memId == lab.memId) -> {
                // TODO: perform dynamic type-check of `typeDesc`
                val writeReadSync = { writeLabel : MemoryAccessLabel, readLabel : MemoryAccessLabel ->
                    MemoryAccessLabel(
                        threadId = readLabel.threadId,
                        accessKind = MemoryAccessKind.ReadResponse,
                        typeDesc = writeLabel.typeDesc,
                        memId = writeLabel.memId,
                        value = writeLabel.value,
                    )
                }
                return when {
                    (accessKind == MemoryAccessKind.Write) && (lab.accessKind == MemoryAccessKind.ReadRequest) ->
                        writeReadSync(this, lab)
                    (accessKind == MemoryAccessKind.ReadRequest) && (lab.accessKind == MemoryAccessKind.Write) ->
                        writeReadSync(lab, this)
                    else -> null
                }
            }
            (lab is EmptyLabel) -> this
            else -> null
        }
    }
}