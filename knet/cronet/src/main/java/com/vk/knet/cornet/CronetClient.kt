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

import android.content.Context
import com.vk.knet.core.http.*
import com.vk.knet.core.http.body.response.HttpResponseBody
import com.vk.knet.core.http.metric.HttpMetricsListener
import com.vk.knet.core.utils.*
import com.vk.knet.cornet.*
import com.vk.knet.cornet.config.CronetCache
import com.vk.knet.cornet.config.CronetCoreConfig
import com.vk.knet.cornet.config.CronetQuic
import com.vk.knet.cornet.experiment.CronetClient.Builder.Default.FILE_SIZE
import com.vk.knet.cornet.experiment.CronetClient.Builder.Default.IS_REDIRECT
import com.vk.knet.cornet.experiment.CronetClient.Builder.Default.MAX_PER_REQUEST
import com.vk.knet.cornet.experiment.CronetClient.Builder.Default.MAX_REQUEST
import com.vk.knet.cornet.experiment.CronetClient.Builder.Default.TIMEOUT
import com.vk.knet.cornet.ext.toHttpProtocol
import com.vk.knet.cornet.pool.Pools
import com.vk.knet.cornet.pool.buffer.CronetNativeByteBufferPool
import com.vk.knet.cornet.pool.thread.CronetExecutor
import com.vk.knet.cornet.pool.thread.CronetExecutorsPool
import com.vk.knet.cornet.utils.BodyInputStream
import com.vk.knet.cornet.utils.CompositeHttpRequestLifecycleListener
import com.vk.knet.cornet.utils.CronetLogger
import com.vk.knet.cornet.utils.redirect.DefaultRedirect
import org.chromium.net.ExperimentalCronetEngine
import java.io.File
import java.io.InterruptedIOException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 *  Implementation of the network layer using the Cronet library:
 * https://developer.android.com/guide/topics/connectivity/cronet
 * https://chromium.googlesource.com/chromium/src/+/master/components/cronet/README.md
 * https://chromium.googlesource.com/chromium/src/+/master/components/cronet/android/
 *
 * General information:
 * - https://blog.cloudflare.com/the-road-to-quic/
 * - https://eng.uber.com/employing-quic-protocol/
 *
 * You can peek a little at the implementation in ExoPlayer (not very suitable for us):
 * https://github.com/google/ExoPlayer/tree/release-v2/extensions/cronet/src/main/java/com/google/android/exoplayer2/ext/cronet
 *
 * There is also an implementation as an Interceptor for OkHttp (not very suitable for us):
 * https://github.com/akshetpandey/react-native-cronet/tree/master/android/src/main/java/com/akshetpandey/rncronet
 */
class CronetClient(
    private val engine: ExperimentalCronetEngine,
    private val config: CronetCoreConfig,
    private val pools: Pools,
    private val netlog: CronetLog,
    private val metric: HttpMetricsListener?,
    private val requestLifecycleListener: HttpRequestLifecycleListener?
) {
    private val isShutdown = AtomicBoolean(false)

    private val activeRequests = ConcurrentHashMap<Long, RequestController>()

    private val dispatcher: CronetDispatcher = CronetDispatcher(
        config.maxConcurrentRequests,
        config.maxConcurrentRequestsPerHost
    )

    private val executorPool = CronetExecutorsPool(config.maxConcurrentRequests)

    /**
     * Sending a request for execution.
     * All requests are blocking, so the calling thread will wait for the request to complete.
     */
    @WorkerThread
    fun execute(request: HttpRequest): HttpResponse {
        return launchRequestAndAwait(request)
    }

    @AnyThread
    fun netlog(): CronetLog {
        return netlog
    }

    /**
     * Wrapper over a network request. More needed to handle any errors
     */
    private fun launchRequestAndAwait(request: HttpRequest): HttpResponse {
        try {
            return launchRequestAndAwaitImpl(request)
        } catch (th: Throwable) {
            if (th is InterruptedException) {
                Thread.currentThread().interrupt()
            }

            val th2 = when (th) {
                is InterruptedException -> InterruptedIOException("Executing thread is interrupted")
                else -> th
            }

            throw th2
        }
    }

    /**
     * Direct implementation of the network request.
     * Attention! This class is full of lamb and other hats, since otherwise it would wrap the Cronet callback architecture
     * in a normal synchronous request and InputStream is impossible (or I have not come up with a normal way).
     */
    @WorkerThread
    private fun launchRequestAndAwaitImpl(request: HttpRequest): HttpResponse {
        return executeRequest(request)
    }

    private val redirects = DefaultRedirect(config.followRedirects, config.followSslRedirects)
    private val builder = CronetConnectionBuilder(engine, metric)

    private fun executeRequest(request: HttpRequest): HttpResponse {
        // Native buffer is passed to Cronet and used to read from inputStream
        val arcBuffer = pools.native.arc()
        val executor = executorPool.obtain()

        val cronetController = RequestController(
            redirect = redirects,
            builder = builder,
            delegate = object : RequestController.Delegate {
                override fun onTerminate(throwable: Throwable?) {
                    closeSession(request, executor)
                    arcBuffer.release()
                }
            }
        )
        requestLifecycleListener?.onRequestLaunched(request)
        // Create Request
        try {
            cronetController.setupSession(request, executor)
            requestLifecycleListener?.onSessionSetup(request)
        } catch (e: Throwable) {
            // Release the Semaphore access to the host ourselves, since onRequestFinished is not called yet
            CronetLogger.error(TAG, "[cronet] Error while create request ${request.url}!")
            executorPool.recycle(executor)
            requestLifecycleListener?.onSessionSetupFailed(request, e)
            throw e
        }

        // Prepare session
        try {
            startSession(request, cronetController)
            requestLifecycleListener?.onSessionStarted(request)
        } catch (e: Throwable) {
            // Release the Semaphore access to the host ourselves, since onRequestFinished is not called yet
            CronetLogger.error(TAG, "[cronet] Error while start session ${request.url}!")
            requestLifecycleListener?.onSessionStartFailed(request, e)
            closeSession(request, executor)
            throw e
        }

        // Await connection
        try {
            val startConnectionTime = System.currentTimeMillis()
            requestLifecycleListener?.onStartConnection(request)
            cronetController.startConnection()
            arcBuffer.retain() // If startConnection was called, onTerminate will be called eventually
            cronetController.awaitConnection(request, config.connectTimeoutMs)
            val endConnectionTime = System.currentTimeMillis()
            val connectionTime = endConnectionTime - startConnectionTime
            requestLifecycleListener?.onConnectionStarted(request, connectionTime)
            CronetLogger.debug(
                CronetHttpLogger.DebugType.CLIENT_TIMEOUTS,
                "[cronet] Connection time  $connectionTime ms to ${request.url}"
            )
        } catch (error: Throwable) {
            // Release the Semaphore access to the host ourselves, since onRequestFinished is not called yet
            CronetLogger.error(TAG, "[cronet] Error while await of ${request.url} connection!")
            requestLifecycleListener?.onStartConnectionFailed(request, error)
            cronetController.closeConnection()
            throw error
        }

        // Read/Write
        try {
            // Important! As a result, we will receive the basic server response (status, headers, etc.)
            // Then we can wrap it in [HttpResponse] and [InputStream] (for the body) that are clear to us
            val urlResponseInfo = cronetController.awaitResponse()
            requestLifecycleListener?.onResponseInfoReceived(request)

            // ArrayBuffer with Reference Count
            val buffer = arcBuffer.retain()

            // Wrapper over the response body. Allows to translate Cronet callback architecture into InputStream
            // All body data is read lazily and 'by demand'.
            // It is important to remember that during the reading process, errors may occur (for example, timeouts),
            // so you should catch them and cancel the request yourself
            val responseInputStream = BodyInputStream(
                delegate = object : BodyInputStream.Delegate {
                    override fun onError(error: Throwable) {
                        cronetController.closeWithError(error)
                        throw error
                    }

                    override fun onClosed() {
                        cronetController.closeConnection()
                    }

                    override fun onRead(): ByteBuffer? {
                        return cronetController.awaitChunk(buffer, config.readTimeoutMs)
                    }
                }
            )

            val headers = urlResponseInfo.allHeaders
            val contentType = headers.getHeader("Content-Type")
            val contentLength = headers.getHeader("Content-Length")?.toLongOrNull()

            // Received a response from the server, we form it in a more understandable form for us
            return HttpResponse(
                protocol = urlResponseInfo.negotiatedProtocol.toHttpProtocol(),
                url = urlResponseInfo.url,
                statusCode = urlResponseInfo.httpStatusCode,
                statusText = urlResponseInfo.httpStatusText,
                headers = headers,
                body = HttpResponseBody(
                    responseInputStream,
                    pools.array.rc(),
                    contentLength,
                    contentType,
                )
            )
        } catch (e: Throwable) {
            requestLifecycleListener?.onErrorResponseInfo(request, e)
            CronetLogger.error(TAG, "[cronet] Error while await of ${request.url} response!")
            cronetController.closeConnection()
            throw e
        }
    }

    private fun startSession(request: HttpRequest, connection: RequestController) {
        onRequestBegin(request.id, connection)
        requestLifecycleListener?.onStartSession(request)

        // Trying to get access to execute requests through Semaphore
        // Do not forget to release it later in onComplete (it is not practical to do it through try / finally)
        // Calling close is optional, because in case of error startAsyncSession will close all semaphores by itself
        try {
            dispatcher.startAsyncSession(request.uri)
        } catch (e: InterruptedException) {
            CronetLogger.error(TAG, "[cronet] Error while acquire async session ${request.url}!")
            throw InterruptedException("Request acquire interrupted for host - ${request.uri.host}!")
                .apply {
                    addSuppressed(e)
                }
        }
    }

    private fun closeSession(request: HttpRequest, executor: CronetExecutor) {
        if (onRequestComplete(request.id)) {
            // IMPORTANT! Call no more than 1 time!
            // Notify about the completion of the request
            dispatcher.closeAsyncSession(request.uri)

            // Free up the Semaphore access to the host
            executorPool.recycle(executor)

            requestLifecycleListener?.onCloseSession(request)
        }
    }

    /**
     * Completion of work. All running requests will be aborted
     */
    @AnyThread
    fun shutdown() {
        if (isShutdown.compareAndSet(false, true)) {
            shutdownIfNeeded()
        }
    }

    @Synchronized
    private fun shutdownIfNeeded() {
        if (activeRequests.isEmpty()) {
            engine.shutdown()
        } else {
            activeRequests
                .values
                .forEach(RequestController::closeConnection)

            engine.shutdown()
        }
    }

    @Synchronized
    private fun onRequestBegin(id: Long, request: RequestController) {
        if (isShutdown.get()) {
            request.closeConnection()
            return
        }

        activeRequests[id] = request
    }

    @Synchronized
    private fun onRequestComplete(id: Long): Boolean {
        return activeRequests.remove(id) != null
    }

    private fun Map<String, List<String>>.getHeader(name: String): String? {
        return get(name)?.joinToString()
            ?: get(name.lowercase())?.joinToString()
    }


    class Builder(
        private val context: Context
    ) {

        private var quic: CronetQuic? = null
        private var libLoader: CronetLibLoader? = null
        private var cache: CronetCache = CronetCache.Empty

        private var netlog: CronetLog.Config = CronetLog.Config(File("${context.filesDir}/cronet_netlog"), FILE_SIZE)
        private var nativePool: CronetNativeByteBufferPool? = null
        private var arrayPool: ByteArrayPool? = null

        private var isEnableHttp2: Boolean = true

        private var connectTimeoutMs: Long = TIMEOUT
        private var readTimeoutMs: Long = TIMEOUT
        private var writeTimeoutMs: Long = TIMEOUT

        private var maxConcurrentRequests: Int = MAX_REQUEST
        private var maxConcurrentRequestsPerHost: Int = MAX_PER_REQUEST

        private var followRedirects: Boolean = IS_REDIRECT
        private var followSslRedirects: Boolean = IS_REDIRECT

        private var isClear: Boolean = true

        private var isUseBrotli: Boolean = false

        private val metrics: MutableList<HttpMetricsListener> = mutableListOf()

        private val requestLifecycleListeners: MutableList<HttpRequestLifecycleListener> = mutableListOf()

        // Protocols
        fun enableQuic(options: CronetQuic) = apply {
            quic = options
        }

        fun disableQuic() = apply {
            quic = null
        }

        fun enableHttp2(isEnabled: Boolean) = apply {
            isEnableHttp2 = isEnabled
        }


        // Cache
        fun setCache(options: CronetCache) = apply {
            cache = options
        }

        fun isClearBrokenHosts(isClear: Boolean) {
            this.isClear = isClear
        }


        // Encoding
        fun useBrotli(isUse: Boolean) {
            isUseBrotli = isUse
        }


        // Timeout
        fun connectTimeout(timeout: Long, unit: TimeUnit) = apply {
            connectTimeoutMs = unit.toMillis(timeout)
        }

        fun writeTimeout(timeout: Long, unit: TimeUnit) = apply {
            writeTimeoutMs = unit.toMillis(timeout)
        }

        fun readTimeout(timeout: Long, unit: TimeUnit) = apply {
            readTimeoutMs = unit.toMillis(timeout)
        }

        // Lib lodaer
        fun setLibLoader(loader: CronetLibLoader?) {
            libLoader = loader
        }


        // Pools
        fun nativePool(pool: CronetNativeByteBufferPool) = apply {
            this.nativePool = pool
        }

        fun arrayPool(pool: ByteArrayPool) = apply {
            this.arrayPool = pool
        }


        // Redirect
        fun followRedirects(isFollow: Boolean) = apply {
            followRedirects = isFollow
        }

        fun followSslRedirects(isFollow: Boolean) = apply {
            followSslRedirects = isFollow
        }


        // Request
        fun maxConcurrentRequests(count: Int) = apply {
            maxConcurrentRequests = count
        }

        fun maxConcurrentRequestsPerHost(count: Int) = apply {
            maxConcurrentRequestsPerHost = count
        }


        // Logs
        fun netlog(options: CronetLog.Config) = apply {
            netlog = options
        }

        // Stat
        fun addMetricListener(metric: HttpMetricsListener) = apply {
            metrics.add(metric)
        }

        fun removeMetricListener(metric: HttpMetricsListener) = apply {
            metrics.remove(metric)
        }

        fun addRequestLifecycleListener(listener: HttpRequestLifecycleListener) = apply {
            requestLifecycleListeners += listener
        }

        fun removeRequestLifecycleListener(listener: HttpRequestLifecycleListener) = apply {
            requestLifecycleListeners -= listener
        }

        internal fun build(): CronetClient {
            val config = cronetConfig()
            val engine = cronetEngine(config)
            val nativePool = nativePool
                ?: CronetNativeByteBufferPool.DEFAULT

            val arrayPool = arrayPool
                ?: ByteArrayPool.DEFAULT

            return CronetClient(
                netlog = CronetLog(netlog, engine),
                pools = Pools(
                    native = nativePool,
                    array = arrayPool
                ),
                config = config,
                engine = engine,
                metric = createMetric(),
                requestLifecycleListener = when {
                    requestLifecycleListeners.isNotEmpty() ->
                        CompositeHttpRequestLifecycleListener(*requestLifecycleListeners.toTypedArray())

                    else -> null
                }
            )
        }

        private fun createMetric(): HttpMetricsListener? {
            if (metrics.isEmpty()) {
                return null
            }

            return HttpMetricsListener { metric, request, response  ->
                metrics.forEach { listener ->
                    listener.onMetricsCollected(metric, request, response)
                }
            }
        }

        private fun cronetConfig() = CronetCoreConfig(
            quicConfig = quic,
            isEnableHttp2 = isEnableHttp2,
            isUseBrotli = isUseBrotli,
            connectTimeoutMs = connectTimeoutMs,
            writeTimeoutMs = writeTimeoutMs,
            readTimeoutMs = readTimeoutMs,
            maxConcurrentRequests = maxConcurrentRequests,
            maxConcurrentRequestsPerHost = maxConcurrentRequestsPerHost,
            followRedirects = followRedirects,
            followSslRedirects = followSslRedirects,
            isClearHosts = isClear
        )

        private fun cronetEngine(config: CronetCoreConfig): ExperimentalCronetEngine {
            return CronetEngineBuilder(context)
                .run {
                    if (config.isClearHosts) {
                        withClearBroken(true)
                    }

                    if (config.isEnableHttp2) {
                        withHttp2()
                    }

                    if (libLoader != null) {
                        withLibLoader(libLoader)
                    }

                    val quic = config.quicConfig
                    if (quic != null) {
                        withQuic(quic)
                    }

                    if (config.isUseBrotli) {
                        withBrotli()
                    }

                    withCache(cache)

                    build()
                }
        }

        object Default {
            const val TIMEOUT = 30_000L
            const val FILE_SIZE = 10 * 1024 * 1024 // 10 MB
            const val IS_REDIRECT = true

            const val MAX_REQUEST = 64
            const val MAX_PER_REQUEST = 16
        }
    }

    companion object {
        private const val TAG = "Cronet"
    }
}
