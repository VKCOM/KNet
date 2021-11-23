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

import com.vk.knet.core.KnetEngine
import com.vk.knet.core.utils.ByteArrayPool

interface HttpPipeline {

    /**
     * [HttpRequest] to be executed.
     * May be the same as [Env.original], or different if the original request
     * has been modified by any of the previous Interceptors.
     */
    val request: HttpRequest

    /**
     * Meta info about current Pipeline
     */
    val env: Env

    /**
     * Sends a request execution operation to the next [HttpInterceptor] in the chain.
     */
    fun proceed(request: HttpRequest) : HttpResponse

    interface Context {
        val error: Throwable?
        fun addCause(throwable: Throwable): Throwable
    }

    class Helpers(
        val pool: ByteArrayPool
    )

    data class Env(
        val helper: Helpers,
        val context: Context,
        val engine: KnetEngine,
        val original: HttpRequest,
        val interceptors: List<HttpInterceptor>
    )
}