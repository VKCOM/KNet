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
package com.vk.knet.okcronet

import com.vk.knet.core.ext.toMultimap
import com.vk.knet.core.http.HttpMethod
import com.vk.knet.core.http.HttpProtocol
import com.vk.knet.core.http.HttpRequest
import com.vk.knet.core.http.HttpResponse
import com.vk.knet.core.http.body.request.HttpRequestBody
import com.vk.knet.core.http.body.request.HttpRequestBodyBinary
import okhttp3.Headers.Companion.toHeaders
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.internal.http.RealResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.buffer
import okio.source

interface OkHttpToKnetAdapter {

    fun Request.toKnetRequest(): HttpRequest? {
        val request = this

        // Mapping HttpUrl to String
        val url = request.url.toString()

        // Mapping Request to HttpRequest
        val method = HttpMethod.from(request.method) ?: return null
        val requestBody = request.body
        val contentType = requestBody?.contentType()

        var headers = request.headers.toMultimap()

        if (contentType != null) {
            headers = headers.plus(
                "Content-Type" to contentType.toString().toMultimap()
            )
        }

        val body = if (method == HttpMethod.POST || method == HttpMethod.PUT || method == HttpMethod.PATCH) {
            requestBody.toKnetBody()
        } else {
            null
        }

        return HttpRequest(method, url, headers, body)
    }

    /**
     * Mapping OkHttp [RequestBody] to KNet [HttpRequestBody]
     */
    private fun RequestBody?.toKnetBody(): HttpRequestBody? {
        val requestBody = this  ?: return null

        val mimeType = requestBody.contentType()?.toString()

        // TODO
        val buffer = Buffer()
        requestBody.writeTo(buffer)
        val content = buffer.readByteArray()

        return HttpRequestBodyBinary(content, mimeType)
    }
}


interface KnetToOkHttpAdapter {

    fun HttpResponse.toOkResponse(request: Request): Response {
        val response = this

        val code = response.statusCode
        val message = response.statusText

        val rawHeaders = response.foldHeaders()
        val headers = rawHeaders.toHeaders()
        val protocol = response.protocol.toOkHttpProtocol()

        val mimeType = response.getContentType() ?: HttpRequestBody.DEFAULT_CONTENT_TYPE
        val mediaType = mimeType.toMediaType()
        val content = response.body
        val responseBody = RealResponseBody(
            contentTypeString = mediaType.toString(),
            contentLength = -1,
            source = content!!.asStream().source().buffer()
        )

        return Response.Builder()
            .code(code)
            .message(message)
            .protocol(protocol)
            .headers(headers)
            .body(responseBody)
            .request(request)
            .receivedResponseAtMillis(System.currentTimeMillis())
            .build()
    }

    @Suppress("DEPRECATION")
    private fun HttpProtocol.toOkHttpProtocol(): Protocol {
        return when (this) {
            HttpProtocol.HTTP_1_0 -> Protocol.HTTP_1_0
            HttpProtocol.HTTP_1_1 -> Protocol.HTTP_1_1
            HttpProtocol.HTTP_2 -> Protocol.HTTP_2
            HttpProtocol.SPDY -> Protocol.SPDY_3
            HttpProtocol.QUIC -> Protocol.QUIC
        }
    }
}
