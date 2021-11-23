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
package com.vk.knet.cornet.ext

import android.util.Pair
import com.vk.knet.core.exceptions.NoNetworkException
import com.vk.knet.core.exceptions.QuicException
import com.vk.knet.core.ext.addSuppressedSafe
import com.vk.knet.core.ext.fromMultimap
import com.vk.knet.core.http.HttpProtocol
import com.vk.knet.core.http.metric.HttpMetrics
import com.vk.knet.core.http.metric.HttpMetricIntervals
import com.vk.knet.core.http.metric.HttpMetricPoints
import com.vk.knet.core.http.metric.HttpResponseMeta
import org.chromium.net.*
import org.chromium.net.impl.CallbackExceptionImpl
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.*

/**
 * Adding encoding HttpHeaders to Request
 */
internal fun UrlRequest.Builder.addHeaders(headers: Map<String, List<String>>): UrlRequest.Builder {
    if (headers.isNotEmpty()) {
        headers.forEach { (header, values) ->
            // It's not necessary to set Accept-Encoding on requests
            // cronet will do this automatically for you, and setting it yourself has no effect.
            // See https://crbug.com/581399 for details.
            if (header.equals("Accept-Encoding", ignoreCase = true)) {
                // we try to workaround that restriction before we start compile our own cronet
                try {
                    val field = javaClass.getDeclaredField("mRequestHeaders")
                    field.isAccessible = true
                    @Suppress("UNCHECKED_CAST")
                    (field.get(this) as? ArrayList<Pair<String, String>>)?.add(
                        Pair.create(header, values.fromMultimap())
                    )
                } catch (th: Throwable) {
                    //ignored
                }
                return@forEach
            }

            if (values.isNotEmpty()) {
                addHeader(header, values.fromMultimap())
            }
        }
    }
    return this
}

/**
 * Конвертация события завершения запроса и его метрик в [HttpMetrics]
 */
internal fun RequestFinishedInfo.toHttpMetrics(
    requestInitTime: Long,
    requestInitTimestamp: Long,
    response: HttpResponseMeta?
): HttpMetrics {
    val total = metrics.totalTimeMs ?: 0
    val tls = if (response != null) {
        when (response.protocol) {
            HttpProtocol.QUIC -> "1.3"
            HttpProtocol.HTTP_2 -> "1.2"
            else -> ""
        }
    } else {
        ""
    }

    val proxy = responseInfo?.proxyServer
    val proxyServer = if (proxy != null && proxy != ":0") proxy else "" // :0 - default cronet value

    return HttpMetrics(
        source = HttpMetrics.Source.CRONET,
        socketReused = metrics.socketReused,
        tlsVersion = tls,
        isProxy = proxyServer.isNotBlank(),
        proxy = proxyServer,
        intervals = toIntervals(metrics),
        totalTimeMs = total,
        isFailed = finishedReason == RequestFinishedInfo.FAILED,
        failReason = exception?.message,
        requestStartTime = requestInitTime,
        protocol = response?.protocol,
        requestStartupTimestamp = requestInitTimestamp
    )
}

internal fun toIntervals(metrics: RequestFinishedInfo.Metrics): HttpMetricIntervals {
    return HttpMetricPoints(
        dnsStart = metrics.dnsStart?.time ?: 0,
        dnsEnd = metrics.dnsEnd?.time ?: 0,

        connectStart = metrics.connectStart?.time ?: 0,
        connectEnd = metrics.connectEnd?.time ?: 0,

        secureStart = metrics.sslStart?.time ?: 0,
        secureEnd = metrics.sslEnd?.time ?: 0,

        requestStart = metrics.sendingStart?.time ?: 0,
        requestEnd = metrics.sendingEnd?.time ?: 0,

        responseStart = metrics.responseStart?.time ?: 0,
        responseEnd = metrics.requestEnd?.time ?: 0,
    ).intervals
}

/**
 * Convert Protocol Name to [HttpProtocol]
 */
internal fun String.toHttpProtocol(): HttpProtocol {
    return when {
        equals("h2", ignoreCase = true) -> HttpProtocol.HTTP_2
        equals("http/2", ignoreCase = true) -> HttpProtocol.HTTP_2
        equals("http/1.1", ignoreCase = true) -> HttpProtocol.HTTP_1_1
        contains("quic", ignoreCase = true) -> HttpProtocol.QUIC
        contains("h3-", ignoreCase = true) -> HttpProtocol.QUIC
        contains("spdy", ignoreCase = true) -> HttpProtocol.SPDY
        else -> HttpProtocol.getDefault()
    }
}

/**
 * Cronet masks all errors in its own format, but in terms of versatility, we need generally accepted Exceptions.
 */
internal fun CronetException.toNormalJavaException(): Throwable {
    val th = when (this) {
        is NetworkException -> when (this.errorCode) {
            NetworkException.ERROR_HOSTNAME_NOT_RESOLVED -> UnknownHostException(this.message
                ?: "ERROR_HOSTNAME_NOT_RESOLVED")
            NetworkException.ERROR_TIMED_OUT -> SocketTimeoutException(this.message
                ?: "ERROR_TIMED_OUT")
            NetworkException.ERROR_CONNECTION_TIMED_OUT -> SocketTimeoutException(this.message
                ?: "ERROR_CONNECTION_TIMED_OUT")
            NetworkException.ERROR_CONNECTION_CLOSED -> ConnectException(this.message
                ?: "ERROR_CONNECTION_CLOSED")
            NetworkException.ERROR_CONNECTION_REFUSED -> ConnectException(this.message
                ?: "ERROR_CONNECTION_REFUSED")
            NetworkException.ERROR_NETWORK_CHANGED -> ConnectException(this.message
                ?: "ERROR_NETWORK_CHANGED")
            NetworkException.ERROR_CONNECTION_RESET -> ConnectException(this.message
                ?: "ERROR_CONNECTION_RESET")
            NetworkException.ERROR_ADDRESS_UNREACHABLE -> ConnectException(this.message
                ?: "ERROR_ADDRESS_UNREACHABLE")
            NetworkException.ERROR_QUIC_PROTOCOL_FAILED -> QuicException(this.message
                ?: "ERROR_QUIC_PROTOCOL_FAILED")
            NetworkException.ERROR_INTERNET_DISCONNECTED -> NoNetworkException(this.message
                ?: "ERROR_INTERNET_DISCONNECTED")
            NetworkException.ERROR_OTHER -> IOException("CRONET_ERROR_OTHER", this)
            else -> IOException(this)
        }
        is CallbackExceptionImpl -> this.cause ?: this
        else -> IOException(this)
    }
    if (th.cause != this) {
        // Not all Exceptions can have a cause, so we add them as suppressed
        th.addSuppressedSafe(this)
    }
    return th
}
