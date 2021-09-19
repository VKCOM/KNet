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
import com.vk.knet.core.http.body.response.HttpResponseBody
import java.io.Closeable

@Suppress("MemberVisibilityCanBePrivate", "unused", "LiftReturnOrAssignment")
data class HttpResponse(
    val protocol: HttpProtocol,
    val url: String,
    val statusCode: Int,
    val statusText: String,
    val headers: Map<String, List<String>>,
    val body: HttpResponseBody?
) : Closeable {

    val isSuccessful: Boolean get() = statusCode in 200..299

    fun foldHeaders(): Map<String, String> {
        return headers.mapValues { values -> values.value.fromMultimap() }
    }

    fun getHeaders(header: String): List<String>? {
        return headers.getHeader(header)
    }

    fun getHeader(header: String): String? {
        return headers.getHeader(header)?.fromMultimap()
    }

    fun getContentLength(): Int {
        val length = getHeaders("content-length")?.firstOrNull()
        return when (length == null) {
            true -> -1
            false -> length.toIntOrNull() ?: -1
        }
    }

    fun getContentType(): String? {
        val value = getHeaders("content-type")?.firstOrNull() ?: return null
        return when (val divider = value.indexOf(';')) {
            -1 -> value
            else -> value.substring(0, divider)
        }
    }

    fun getContentCharset(): String? {
        val contentType = getHeaders("content-type")?.firstOrNull() ?: return null
        return CONTENT_TYPE_CHARSET_REGEX.find(contentType)?.groupValues?.getOrNull(1)
    }

    fun isBodyPlainText(): Boolean {
        return when (val contentType = getContentType()) {
            null -> false
            else -> CONTENT_TYPES_OF_PLAIN_TEXT.any { contentType.equals(it, ignoreCase = true) }
        }
    }

    fun readAndClose(): ByteArray? {
        return body?.use {
            it.asBytes()
        }
    }

    override fun close() {
        body?.close()
    }

    companion object {
        private val CONTENT_TYPES_OF_PLAIN_TEXT = listOf("text/html", "application/json")
        private val CONTENT_TYPE_CHARSET_REGEX = "charset=(.*)".toRegex()
    }
}
