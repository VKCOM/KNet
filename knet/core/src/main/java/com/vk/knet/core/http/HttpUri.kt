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

import android.net.Uri
import java.lang.IllegalStateException

class HttpUri(private val uri: Uri) {

    companion object {
        fun from(uri: String) = HttpUri(Uri.parse(uri))
    }

    val host by lazy(LazyThreadSafetyMode.NONE) {
        uri.host ?: throw IllegalStateException("Uri $uri doesn't have host!")
    }

    val scheme by lazy(LazyThreadSafetyMode.NONE) {
        uri.scheme ?: throw IllegalStateException("Uri $uri doesn't have scheme!")
    }

    fun edit(editor: Uri.Builder.() -> Uri.Builder): HttpUri {
        return HttpUri(
            uri.buildUpon()
                .editor()
                .build()
        )
    }

    fun toUri(): Uri = uri.buildUpon().build()

    override fun toString(): String {
        return uri.toString()
    }
}