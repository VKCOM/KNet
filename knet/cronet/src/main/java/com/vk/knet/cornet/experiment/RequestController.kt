/**
 * The MIT License (MIT)
 *
 * Copyright (c) 2019 vk.com
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
*/
package com.vk.knet.cornet.experiment

import com.vk.knet.core.http.HttpRequest
import com.vk.knet.core.utils.AnyThread
import com.vk.knet.core.utils.WorkerThread
import com.vk.knet.cornet.CronetHttpLogger
import com.vk.knet.cornet.backoff.BackoffExponentSum
import com.vk.knet.cornet.ext.toNormalJavaException
import com.vk.knet.cornet.pool.thread.CronetExecutor
import com.vk.knet.cornet.utils.ConditionLock
import com.vk.knet.cornet.utils.CronetLogger
import com.vk.knet.cornet.utils.CronetThread
import com.vk.knet.cornet.utils.redirect.Redirect
import org.chromium.net.*
import java.io.InputStream
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class RequestController(
    private val delegate: Delegate,
    private val redirect: Redirect,
    private val builder: CronetConnectionBuilder
) {

    interface Delegate {
        fun onTerminate(throwable: Throwable?)
    }

    private class State {
        companion object {
            const val QUEUED = 0
            const val CONNECTING = 1
            const val WRITING = 2
            const val READING = 3
            const val SUCCESS = 4
            const val CANCELED = 5
            const val ERROR = 6
        }
    }

    private class Event {
        companion object {
            const val SENT_REQUEST = 0
            const val WRITE_REQUEST = 1
            const val RESPONSE_READY = 2
            const val READ_RESPONSE = 3
            const val SUCCESS = 4
            const val CANCEL = 5
            const val ERROR = 6
        }
    }

    @Volatile
    private var state = State.QUEUED

    @Volatile
    private var connection: UrlRequest? = null

    @Volatile
    private var response: UrlResponseInfo? = null

    @Volatile
    private var error = AtomicReference<Throwable>(null)

    private val lock = ReentrantLock()

    private val connectCondition = ConditionLock(lock)
    private val writeCondition = ConditionLock(lock)
    private val responseCondition = ConditionLock(lock)
    private val readCondition = ConditionLock(lock)

    @WorkerThread
    fun setupSession(
        request: HttpRequest,
        executor: CronetExecutor,
    ) {
        val callback = CallbackDelegate(request, redirect)
        val provider = createUploadDataProvider(request)

        val newConnection = builder.buildRequest(request, executor, callback, provider)
        connection = newConnection
    }

    @WorkerThread
    fun startConnection() {
        updateState(Event.SENT_REQUEST)

        connection
            ?.start()
            ?: throw IllegalStateException("You have to create connection, before start it!")
    }

    @WorkerThread
    fun awaitConnection(
        request: HttpRequest,
        timeoutMs: Long
    ) {
        val backoffAwait = BackoffExponentSum(BACKOFF_INIT, timeoutMs)

        // Wait for the connection to be established
        do {
            val time = backoffAwait.next()

            CronetLogger.debug(CronetHttpLogger.DebugType.CLIENT_TIMEOUTS, "[cronet] Start awaiting of ${request.url} connection for $time ms")
            if (connectCondition.await(true, time)) {
                CronetLogger.debug(CronetHttpLogger.DebugType.CLIENT_TIMEOUTS, "[cronet] Connection to ${request.url} has been established!")
                break
            }

            connection?.let { connect ->
                if (connect.isDone) {
                    // If the request has already been completed, cancel it and wait for the callback call
                    CronetLogger.debug(CronetHttpLogger.DebugType.CLIENT_TIMEOUTS, "[cronet] Url ${request.url} is already done!")
                }
            }
        } while (!backoffAwait.isDone())

        if (backoffAwait.isDone()) {
            CronetLogger.error(TAG, "[cronet] Url ${request.url} is canceled by timeout")
            throw SocketTimeoutException("Unable to establish connection to server!")
        }
    }

    @WorkerThread
    fun awaitResponse(): UrlResponseInfo {
        return lock.withLock {
            connectCondition.await(value = true)
            checkNoErrors()

            writeCondition.await(value = true)
            checkNoErrors()

            responseCondition.await(value = true)
            checkNoErrors()

            checkAtLeast(State.READING)
            response ?: throw IllegalStateException("Expect response to be not null at this stage")
        }
    }

    @WorkerThread
    fun awaitChunk(buffer: ByteBuffer, timeoutMs: Long): ByteBuffer? {
        responseCondition.await(value = true)
        checkNoErrors()

        readCondition.change(false)
        buffer.clear()
        connection
            ?.read(buffer)
            ?: throw IllegalStateException("You have to create connection and await for response, before read it!")

        val awaited = readCondition.await(value = true, timeoutMs = timeoutMs)
        checkNoErrors()

        if (!awaited) {
            throw SocketTimeoutException("Unable to read response's body in ${timeoutMs}ms")
        }

        return lock.withLock {
            checkNoErrors()
            checkAtLeast(State.READING)

            if (state >= State.SUCCESS) {
                return null
            }

            buffer
        }
    }

    @WorkerThread
    fun closeConnection() {
        connection?.cancel()
    }

    @WorkerThread
    fun closeWithError(err: Throwable) {
        return lock.withLock {
            error.set(err)
            connection?.cancel()
        }
    }

    @Suppress("SameParameterValue")
    @WorkerThread
    private fun checkAtLeast(atLeastState: Int) {
        lock.withLock {
            val actualState = state
            if (actualState >= atLeastState) {
                return
            }

            throw IllegalStateException("Actual state $actualState should be more than $atLeastState!")
        }
    }

    @AnyThread
    private fun updateState(event: Int) {
        lock.withLock {
            if (event != state) {
                CronetLogger.debug(CronetHttpLogger.DebugType.CLIENT_STATE, "Old state $state, action $event")
            }

            when (event) {
                Event.SENT_REQUEST -> {
                    state = State.CONNECTING
                }
                Event.WRITE_REQUEST -> {
                    state = State.WRITING
                    connectCondition.change(true)
                    writeCondition.restartAwaitTimeouts()
                }
                Event.RESPONSE_READY -> {
                    state = State.READING
                    connectCondition.change(true)
                    writeCondition.change(true)
                    responseCondition.change(true)
                }
                Event.READ_RESPONSE -> {
                    state = State.READING
                    readCondition.change(true)
                }
                Event.SUCCESS -> {
                    state = State.SUCCESS
                    terminate()
                    delegate.onTerminate(error.get())
                }
                Event.ERROR -> {
                    state = State.ERROR
                    terminate()
                    delegate.onTerminate(error.get())
                }
                Event.CANCEL -> {
                    state = State.CANCELED
                    terminate()
                    delegate.onTerminate(error.get())
                }
            }
        }
    }

    @CronetThread
    private fun terminate() {
        lock.withLock {
            readCondition.change(true)
            responseCondition.change(true)
            writeCondition.change(true)
            connectCondition.change(true)
        }
    }

    @AnyThread
    private fun checkNoErrors() {
        val error = error.get()
        if (error != null) {
            throw error
        }
    }

    /**
     * Create [UploadDataProvider] to send data to server from specified [HttpRequest].
     * @return [UploadDataProvider] or null if not specified in the request
     */
    private fun createUploadDataProvider(request: HttpRequest): UploadDataProvider? {
        val requestMethod = request.method
        val requestBody = request.body

        if (requestMethod.isGet || requestBody == null) {
            return null
        }

        val provider = UploadDataProviders.create(requestBody.getContent())
        return UploadDelegate(provider)
    }

    /**
     * Utility for observing calls from [UploadDataProvider].
     * Needed to define write timeouts
     */
    @CronetThread
    private inner class UploadDelegate(
        val provider: UploadDataProvider
    )  : UploadDataProvider() {

        @CronetThread
        override fun getLength(): Long {
            return provider.length
        }

        @CronetThread
        override fun read(uploadDataSink: UploadDataSink, byteBuffer: ByteBuffer) {
            updateState(Event.WRITE_REQUEST)
            provider.read(uploadDataSink, byteBuffer)
        }

        @CronetThread
        override fun rewind(uploadDataSink: UploadDataSink) {
            updateState(Event.WRITE_REQUEST)
            provider.rewind(uploadDataSink)
        }

        @CronetThread
        override fun close() {
            provider.close()
        }
    }

    /**
     * Callback that reads the request.
     * https://chromium.googlesource.com/chromium/src/+/master/components/cronet/README.md
     *
     * Cronet itself, in my opinion, is made a little stupid in some places. Why do callback architecture,
     * when Java already has an understandable and widely supported [InputStream] for reading the server response.
     * In the realities of ordinary applications, you need [InputStream], since many tools / libraries work on its basis.
     * As a result, with the help of [BodyInputStream], a mini-crutch was made that allows you to return a response in the form of [InputStream].
     *
     * Similarly, there is no way to set timeout for requests in Cronet.
     * Therefore, we are forced to [onResponseStarted], [onReadCompleted], etc. notify the calling thread.
     * Based on this, he can understand whether there is any activity or not, i.e. manage timeout.
     *
     * All callbacks and all functions are called on a thread independent of the thread that made the request.
     * Therefore, you need to remember about correctly throwing errors into the calling stream (for the most part, via [InputStream]).
     */
    @CronetThread
    private inner class CallbackDelegate(
        val httpRequest: HttpRequest,
        val redirect: Redirect
    ) : UrlRequest.Callback() {

        /**
         * Callback cronet about receiving redirect. Just follow the redirects further.
         * Called on the [Executor] specified when creating [request] ([CronetEngine.newUrlRequestBuilder]].
         *
         * This method should ONLY be called by Cronet, we cannot touch it.
         */
        @CronetThread
        override fun onRedirectReceived(request: UrlRequest, info: UrlResponseInfo, newLocationUrl: String) {
            val result = redirect.onRedirect(newLocationUrl, httpRequest)
            if (result is Redirect.Result.Error) {
                throw result.error
            }

            request.followRedirect()
        }

        /**
        * Callback cronet about receiving a response (connection status, headers, etc.).
        * Called on the [Executor] specified when creating [request] ([CronetEngine.newUrlRequestBuilder]].
        * The body of the response itself is not readable, we must subtract it ourselves later using [BodyInputStream],
        * which will implicitly yank [onReadCompleted], [onSucceeded], etc. via [awaitChunk].
        * The result itself is written to [responseInfo], which is needed in [awaitResponseInfo]
        *
        * This method should ONLY be called by Cronet, we cannot touch it.
        */
        @CronetThread
        override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
            CronetLogger.debug(CronetHttpLogger.DebugType.CLIENT_CALLBACK, "[cronet] Request callback of ${httpRequest.url} started!")

            response = info
            updateState(Event.RESPONSE_READY)
        }

        /**
         * Callback cronet to receive a chunk response.
         * Called on the [Executor] specified when creating [request] ([CronetEngine.newUrlRequestBuilder]].
         * Used in [awaitChunk].
         *
         * This method should ONLY be called by Cronet, we cannot touch it.
         */
        @CronetThread
        override fun onReadCompleted(request: UrlRequest, info: UrlResponseInfo, buffer: ByteBuffer) {
            buffer.flip()
            updateState(Event.READ_RESPONSE)
        }

        /**
         * Callback cronet about the completion of the request (the server gave all the data and the entire body).
         * Called on the [Executor] specified when creating [request] ([CronetEngine.newUrlRequestBuilder]].
         * Used in [awaitChunk].
         *
         * This method should ONLY be called by Cronet, we cannot touch it.
         */
        @CronetThread
        override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo?) {
            CronetLogger.debug(CronetHttpLogger.DebugType.CLIENT_CALLBACK, "[cronet] Request callback of ${httpRequest.url} succeeded!")
            CronetLogger.debug(CronetHttpLogger.DebugType.CLIENT_CALLBACK, "onSucceeded ${info?.url} | ${info?.httpStatusCode} | ${info?.allHeaders}")

            updateState(Event.SUCCESS)
        }

        /**
         * Callback cronet about the completion of the request (some kind of error occurred).
         * Called on the [Executor] specified when creating [request] ([CronetEngine.newUrlRequestBuilder]].
         * Used in [awaitChunk].
         *
         * This method should ONLY be called by Cronet, we cannot touch it.
         */
        @CronetThread
        override fun onFailed(request: UrlRequest?, info: UrlResponseInfo?, err: CronetException) {
            var errorText = "[cronet] Request callback of ${httpRequest.url} failed: " +
                "Class: ${err.cause?.javaClass?.simpleName} | Message: ${err.message}!"

            if (err is NetworkException) {
                errorText += " | Code: ${err.errorCode}"
            }

            CronetLogger.error(TAG, errorText)

            error.set(err.toNormalJavaException())
            updateState(Event.ERROR)
        }

        /**
         * Callback cronet about the completion of the request (due to a manual call to cancel on UrlRequest).
         * Called on the [Executor] specified when creating [request] ([CronetEngine.newUrlRequestBuilder]].
         * Used in [awaitChunk].
         *
         * This method should ONLY be called by Cronet, we cannot touch it.
         */
        @CronetThread
        override fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) {
            CronetLogger.info(TAG, "[cronet] Request callback of ${httpRequest.url} canceled!")

            error.compareAndSet(null, InterruptedException("Request cancelled via manual call of #cancel"))
            updateState(Event.CANCEL)
        }
    }

    companion object {
        private const val TAG = "Cronet"
        private const val BACKOFF_INIT = 2000L
    }
}
