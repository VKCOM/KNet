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
package com.vk.knet.core.http.body.response

import com.vk.knet.core.utils.RcArray
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream

class HttpResponseBody(
    private val inputStream: InputStream,
    private val array: RcArray,
    private val contentLength: Long?,
    private val contentType: String?
) : Closeable {

    private var body: ByteArray? = null
    private var isClosed = false
    private var isDone = false

    fun clone(inputStream: InputStream): HttpResponseBody {
        return HttpResponseBody(inputStream, array.clone(), contentLength, contentType)
    }

    fun contentType(): String? {
        return contentType
    }

    fun contentLength(): Long {
        return contentLength ?: -1
    }

    fun asStream(): InputStream {
        if (isClosed) {
            throw IOException("Body is closed")
        }

        val safeBody = body
        if (safeBody != null && isDone) {
            return safeBody.inputStream()
        }

        return inputStream
    }

    fun asBytes(): ByteArray {
        if (isClosed) {
            throw IOException("Body is closed")
        }

        val safeBody = body
        if (safeBody != null && !isClosed) {
            return safeBody
        }

        val length = contentLength()
        val outStream = if (length > 0) {
            ByteArrayOutputStream(length.toInt()) // TODO to Pool?
        } else {
            ByteArrayOutputStream()
        }

        try {
            val buffer = array.retain()
            inputStream.use {
                var bytesCopied: Long = 0
                var bytes = inputStream.read(buffer, 0, buffer.size)
                while (bytes >= 0) {
                    outStream.write(buffer, 0, bytes)
                    bytesCopied += bytes
                    bytes = inputStream.read(buffer, 0, buffer.size)
                }
            }
        } catch (error: Throwable) {
            close()
            throw error
        } finally {
            array.release()
            outStream.flush()
            outStream.close()
            inputStream.close()
        }

        val result = outStream.toByteArray()
        body = result

        return result
    }

    fun asString(): String {
        val bytes = asBytes()
        return bytes.decodeToString()
    }

    override fun close() {
        if (isClosed) {
            return
        }

        isClosed = true
        isDone = true
        body = null
        inputStream.close()
    }
}
