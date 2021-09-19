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
import com.vk.knet.cornet.CronetDispatcher
import com.vk.knet.cornet.CronetEngineBuilder
import com.vk.knet.cornet.CronetHttpLogger
import com.vk.knet.cornet.CronetLog
import com.vk.knet.cornet.config.CronetCache
import com.vk.knet.cornet.config.CronetCoreConfig
import com.vk.knet.cornet.config.CronetQuic
import com.vk.knet.cornet.ext.toHttpProtocol
import com.vk.knet.cornet.experiment.CronetClient.Builder.Default.FILE_SIZE
import com.vk.knet.cornet.experiment.CronetClient.Builder.Default.IS_REDIRECT
import com.vk.knet.cornet.experiment.CronetClient.Builder.Default.MAX_PER_REQUEST
import com.vk.knet.cornet.experiment.CronetClient.Builder.Default.MAX_REQUEST
import com.vk.knet.cornet.experiment.CronetClient.Builder.Default.TIMEOUT
import com.vk.knet.cornet.pool.Pools
import com.vk.knet.cornet.pool.buffer.CronetNativeByteBufferPool
import com.vk.knet.cornet.pool.thread.CronetExecutor
import com.vk.knet.cornet.pool.thread.CronetExecutorsPool
import com.vk.knet.cornet.utils.BodyInputStream
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
 * Реализация сетевого слоя с помощью библиотеки Cronet:
 * https://developer.android.com/guide/topics/connectivity/cronet
 * https://chromium.googlesource.com/chromium/src/+/master/components/cronet/README.md
 * https://chromium.googlesource.com/chromium/src/+/master/components/cronet/android/
 *
 * Общая информация:
 * - https://blog.cloudflare.com/the-road-to-quic/
 * - https://eng.uber.com/employing-quic-protocol/
 *
 * Можно немного подглянуть в реализацию в ExoPlayer (нам не очень подходит):
 * https://github.com/google/ExoPlayer/tree/release-v2/extensions/cronet/src/main/java/com/google/android/exoplayer2/ext/cronet
 *
 * Есть ещё реализация как Interceptor для OkHttp (нам не очень подходит):
 * https://github.com/akshetpandey/react-native-cronet/tree/master/android/src/main/java/com/akshetpandey/rncronet
 */
class CronetClient(
    private val engine: ExperimentalCronetEngine,
    private val config: CronetCoreConfig,
    private val pools: Pools,
    private val netlog: CronetLog,
    private val metric: HttpMetricsListener?
) {
    private val isShutdown = AtomicBoolean(false)

    private val activeRequests = ConcurrentHashMap<Long, RequestController>()

    private val dispatcher: CronetDispatcher = CronetDispatcher(
        config.maxConcurrentRequests,
        config.maxConcurrentRequestsPerHost
    )

    private val executorPool = CronetExecutorsPool(config.maxConcurrentRequests)

    /**
     * Отправка запроса на выполнение.
     * Все запросы являются блокирующими, потому вызывающий поток будет ожидать завершения запроса.
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
     * Обертка над запросом к сети. Больше нужен для обработки каких-либо ошибок
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
     * Непосредственная реализация запроса к сети.
     * Внимание! Этот класс полон лямб и прочей шляпы, так как иначе завернуть callback-архитектуру Cronet
     * в обычный синхронный запрос и InputStream нельзя (либо я не придумал нормального способа).
     */
    @WorkerThread
    private fun launchRequestAndAwaitImpl(request: HttpRequest): HttpResponse {
        return executeRequest(request)
    }

    private val redirects = DefaultRedirect(config.followRedirects, config.followSslRedirects)
    private val builder = CronetConnectionBuilder(engine, metric)

    private fun executeRequest(request: HttpRequest): HttpResponse {
        // Native buffer передается в Cronet и используется для чтения из inputStream
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

        // Create Request
        try {
            cronetController.setupSession(request, executor)
        } catch (e: Throwable) {
            // Освобождаем Semaphore доступа к хосту сами, т.к. onRequestFinished пока не вызовется
            CronetLogger.error(TAG, "[cronet] Error while create request ${request.url}!")
            executorPool.recycle(executor)
            throw e
        }

        // Prepare session
        try {
            startSession(request, cronetController)
        } catch (e: Throwable) {
            // Освобождаем Semaphore доступа к хосту сами, т.к. onRequestFinished пока не вызовется
            CronetLogger.error(TAG, "[cronet] Error while start session ${request.url}!")
            closeSession(request, executor)
            throw e
        }

        // Await connection
        try {
            val startConnectionTime = System.currentTimeMillis()
            cronetController.startConnection()
            arcBuffer.retain() // Если был вызвн startConnection, обязательно в итоге вызовется onTerminate
            cronetController.awaitConnection(request, config.connectTimeoutMs)
            val endConnectionTime = System.currentTimeMillis()
            CronetLogger.debug(
                CronetHttpLogger.DebugType.CLIENT_TIMEOUTS,
                "[cronet] Connection time  ${endConnectionTime - startConnectionTime} ms to ${request.url}"
            )
        } catch (error: Throwable) {
            // Освобождаем Semaphore доступа к хосту сами, т.к. onRequestFinished пока не вызовется
            CronetLogger.error(TAG, "[cronet] Error while await of ${request.url} connection!")
            cronetController.closeConnection()
            throw error
        }

        // Read/Write
        try {
            // Важно! В результате мы получим базовый ответ сервера (статус, headers и т.д.)
            // После чего можем это обернуть в понятные нам [HttpResponse] и [InputStream] (для body)
            val urlResponseInfo = cronetController.awaitResponse()

            // ArrayBuffer с Reference Count
            val buffer = arcBuffer.retain()

            // Обертка над body ответа. Позволяет перевести callback-архитектуру Cronet в InputStream
            // Все данные body читаются лениво и 'by demand'.
            // Важно помнить, что в процессе чтения могут возникнуть ошибки (например, таймауты),
            // потому их следует ловить и отменять запрос самостоятельно
            val responseInputStream = BodyInputStream(
                delegate = object : BodyInputStream.Delegate {
                    override fun onError(error: Throwable) {
                        cronetController.closeWithError(error)
                        throw error
                    }

                    override fun onClosed() {
                        arcBuffer.release()
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

            // Получили ответ от сервера, формируем его в более понятный нам вид
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
            CronetLogger.error(TAG, "[cronet] Error while await of ${request.url} response!")
            cronetController.closeConnection()
            throw e
        }
    }

    private fun startSession(request: HttpRequest, connection: RequestController) {
        onRequestBegin(request.id, connection)

        // Пытаемся получить доступ на выполнение запросов через Semaphore
        // Не забываем потом в onComplete освободить его (через try/finally сделать нерельно)
        // Вызов close необзателен, т.к. в случае ошкбки startAsyncSession сам закроет все семафоры
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
            // ВАЖНО! Вызывать не больше 1 раза!
            // Оповещаем о завершении запроса
            dispatcher.closeAsyncSession(request.uri)

            // Освобождаем Semaphore доступа к хосту
            executorPool.recycle(executor)
        }
    }

    /**
     * Завершение работы. Все выполняющиеся запросы будут прерваны
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
        private var cache: CronetCache = CronetCache.Empty

        private var netlog: CronetLog.Config = CronetLog.Config(File("${context.filesDir}/cronet_netlog"), FILE_SIZE)
        private var logger: CronetHttpLogger = CronetHttpLogger.Empty
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

        fun logger(logger: CronetHttpLogger) = apply {
            this.logger = logger
        }


        // Stat
        fun addMetricListener(metric: HttpMetricsListener) = apply {
            metrics.add(metric)
        }

        fun removeMetricListener(metric: HttpMetricsListener) = apply {
            metrics.remove(metric)
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
                metric = createMetric()
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
                    CronetLogger.global(logger) // TODO: Move to another place

                    if (config.isClearHosts) {
                        withClearBroken(true)
                    }

                    if (config.isEnableHttp2) {
                        withHttp2()
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
            const val FILE_SIZE = 100 * 1024 * 1024 // 100 MB
            const val IS_REDIRECT = true

            const val MAX_REQUEST = 64
            const val MAX_PER_REQUEST = 16
        }
    }

    companion object {
        private const val TAG = "Cronet"
    }
}
