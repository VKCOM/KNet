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
package com.vk.knet.core.http

import com.vk.knet.core.ext.fromMultimap
import com.vk.knet.core.ext.getHeader
import com.vk.knet.core.http.body.request.HttpRequestBody
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

data class HttpRequest(
    val method: HttpMethod,
    val url: String,
    val headers: Map<String, List<String>> = emptyMap(),
    val body: HttpRequestBody? = null,
    val payload: Map<HttpPayload, Any>? = null
) {
    companion object {
        private val requestCounter = AtomicLong(0)
        fun get(
            url: String,
            headers: Map<String, List<String>> = emptyMap(),
            body: HttpRequestBody? = null
        ) : HttpRequest = HttpRequest(HttpMethod.GET, url, headers, body)

        fun post(
            url: String,
            headers: Map<String, List<String>> = emptyMap(),
            body: HttpRequestBody? = null
        ) : HttpRequest = HttpRequest(HttpMethod.POST, url, headers, body)
    }

    val id: Long = requestCounter.getAndIncrement()

    val uri: HttpUri by lazy(LazyThreadSafetyMode.NONE) { HttpUri.from(url) }

    val isHttp: Boolean by lazy { uri.scheme == "http" }
    val isHttps: Boolean by lazy { uri.scheme == "https" }

    val isGet: Boolean = method.isGet
    val isPost: Boolean = method.isPost

    fun foldHeaders(): Map<String, String> {
        return headers.mapValues { values -> values.value.fromMultimap() }
    }

    fun getHeaders(header: String): List<String>? {
        return headers.getHeader(header)
    }

    fun getHeader(header: String): String? {
        return headers.getHeader(header)?.fromMultimap()
    }

    fun <V : Any> withPayload(key: HttpPayload, value: V): HttpRequest {
        val pays = payload?.toMutableMap()
            ?: mutableMapOf()

        pays[key] = value

        return copy(payload = pays)
    }

    @Suppress("UNCHECKED_CAST")
    fun <V : Any> getPayload(key: HttpPayload): V? {
        val value = payload?.get(key)

        return try {
            value as V
        } catch (e: Exception) {
            null
        }
    }
}
