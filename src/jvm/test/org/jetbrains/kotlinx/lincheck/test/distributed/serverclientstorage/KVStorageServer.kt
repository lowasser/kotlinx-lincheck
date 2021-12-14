package org.jetbrains.kotlinx.lincheck.test.distributed.serverclientstorage

import org.jetbrains.kotlinx.lincheck.LinChecker
import org.jetbrains.kotlinx.lincheck.LincheckAssertionError
import org.jetbrains.kotlinx.lincheck.annotations.Operation
import org.jetbrains.kotlinx.lincheck.distributed.*
import org.junit.Test
import org.reflections.serializers.JsonSerializer
import java.util.*
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.serialization.*
import kotlinx.serialization.json.Json
import org.jetbrains.kotlinx.lincheck.execution.emptyClockArray

class KVStorageServer(private val env: Environment<Command, Unit>) : Node<Command, Unit> {
    private val storage = HashMap<Int, Int>()
    private val commandResults = Array<HashMap<Int, Command>>(env.numberOfNodes) {
        HashMap()
    }

    override fun onMessage(message: Command, sender: Int) {
        val id = message.id
        if (commandResults[sender].containsKey(id)) {
            env.send(commandResults[sender][id]!!, sender)
            return
        }
        val result: Command = try {
            when (message) {
                is ContainsCommand -> ContainsResult(storage.containsKey(message.key), id)
                is GetCommand -> GetResult(storage[message.key], id)
                is PutCommand -> PutResult(storage.put(message.key, message.value), id)
                is RemoveCommand -> RemoveResult(storage.remove(message.key), id)
                is AddCommand -> AddResult(
                    storage.put(
                        message.key,
                        storage.getOrDefault(message.key, 0) + message.value
                    ), id
                )
                else -> throw RuntimeException("Unexpected command")
            }
        } catch (e: Throwable) {
            ErrorResult(e, id)
        }
        commandResults[sender][id] = result
        env.send(result, receiver = sender)
    }
}

class KVStorageClient(private val environment: Environment<Command, Unit>) : Node<Command, Unit> {
    private var commandId = 0
    private val commandResults = HashMap<Int, Command>()
    private val serverAddr = environment.getAddressesForClass(KVStorageServer::class.java)!![0]
    private var continuation: Continuation<Unit>? = null
    private val queue = LinkedList<Command>()


    private suspend fun sendOnce(command: Command): Command {
        while (true) {
            environment.send(command, serverAddr)
            environment.recordInternalEvent("Before await")
            environment.withTimeout(6) {
                environment.recordInternalEvent("Before suspend")
                suspendCoroutine { cont ->
                    continuation = cont
                    environment.recordInternalEvent("Set continuation $continuation")
                }
            }
            continuation = null
            environment.recordInternalEvent("After await")
            val response = queue.poll()
            if (response != null) {
                commandResults[response.id] = response
            }
            if (commandResults.containsKey(command.id)) {
                val res = commandResults[command.id]!!
                if (res is ErrorResult) {
                    throw res.error
                }
                return res
            }
        }
    }

    @Operation(cancellableOnSuspension = false)
    suspend fun contains(key: Int): Boolean {
        val response = sendOnce(ContainsCommand(key, commandId++)) as ContainsResult
        return response.res
    }

    @Operation(cancellableOnSuspension = false)
    suspend fun put(key: Int, value: Int): Int? {
        val response = sendOnce(PutCommand(key, value, commandId++)) as PutResult
        return response.res
    }

    @Operation(cancellableOnSuspension = false)
    suspend fun remove(key: Int): Int? {
        val response = sendOnce(RemoveCommand(key, commandId++)) as RemoveResult
        return response.res
    }


    @Operation(cancellableOnSuspension = false)
    suspend fun get(key: Int): Int? {
        val response = sendOnce(GetCommand(key, commandId++)) as GetResult
        return response.res
    }

    @Operation(cancellableOnSuspension = false)
    suspend fun add(key: Int, value: Int): Int? {
        val response = sendOnce(AddCommand(key, value, commandId++)) as AddResult
        return response.res
    }

    override fun onMessage(message: Command, sender: Int) {
        queue.add(message)
        environment.recordInternalEvent("Before resume ${continuation}")
        continuation?.resume(Unit)
    }
}

class KVStorageServerTestClass {
    private fun createOptions(serverType: Class<out Node<Command, Unit>> = KVStorageServer::class.java) =
        createDistributedOptions<Command>()
            .requireStateEquivalenceImplCheck(false)
            .sequentialSpecification(SingleNode::class.java)
            .invocationsPerIteration(10_000)
            .iterations(30)
            .threads(3)
            .minimizeFailedScenario(false)
            .actorsPerThread(3)
            .nodeType(serverType, 1)

    @Test
    fun testAsync() {
        LinChecker.check(
            KVStorageClient::class.java,
            createOptions().messageOrder(MessageOrder.ASYNCHRONOUS).storeLogsForFailedScenario("async.txt")
        )
    }

    @Test
    fun testNetworkUnreliable() {
        LinChecker.check(
            KVStorageClient::class.java,
            createOptions().networkReliable(false).storeLogsForFailedScenario("unreliable.txt")
        )
    }

    @Test
    fun testMessageDuplications() {
        LinChecker.check(
            KVStorageClient::class.java,
            createOptions().messageDuplications(true)
        )
    }

    @Test
    fun test() {
        LinChecker.check(
            KVStorageClient::class.java,
            createOptions()
                .messageDuplications(true)
                .networkReliable(false)
                .messageOrder(MessageOrder.ASYNCHRONOUS)
        )
    }

    @Test(expected = LincheckAssertionError::class)
    fun testIncorrect() {
        LinChecker.check(
            KVStorageClientIncorrect::class.java,
            createOptions(KVStorageServerIncorrect::class.java)
                .messageDuplications(true)
                .networkReliable(false)
                .messageOrder(MessageOrder.ASYNCHRONOUS)
        )
    }

    @Test(expected = LincheckAssertionError::class)
    fun testIncorrectAsync() {
        LinChecker.check(
            KVStorageClientIncorrect::class.java,
            createOptions(KVStorageServerIncorrect::class.java)
                .messageOrder(MessageOrder.ASYNCHRONOUS)
                .storeLogsForFailedScenario("async_incorrect.txt")
        )
    }

    @Test(expected = LincheckAssertionError::class)
    fun testIncorrectNetworkUnreliable() {
        LinChecker.check(
            KVStorageClientIncorrect::class.java,
            createOptions(KVStorageServerIncorrect::class.java)
                .networkReliable(false)
        )
    }

    @Test(expected = LincheckAssertionError::class)
    fun testIncorrectMessageDuplications() {
        LinChecker.check(
            KVStorageClientIncorrect::class.java,
            createOptions(KVStorageServerIncorrect::class.java)
                .messageDuplications(true)
        )
    }
}