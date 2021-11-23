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

/**
 * Factory for [HttpPipeline]. Intended to use with [HttpInterceptor] chain.
 */
@Suppress("CanBeParameter", "FoldInitializerAndIfToElvis")
object HttpPipelineFactory {

    private const val START_ID = -1

    fun create(
        helpers: HttpPipeline.Helpers,
        engine: KnetEngine,
        request: HttpRequest,
        interceptors: List<HttpInterceptor>
    ): HttpPipeline {
        val context = HttpContext()
        return PipelineImpl(
            START_ID,
            request,
            HttpPipeline.Env(helpers, context, engine, request, interceptors)
        )
    }

    private class PipelineImpl(
        private val index: Int,
        override val request: HttpRequest,
        override val env: HttpPipeline.Env,
    ) : HttpPipeline {

        override fun proceed(request: HttpRequest): HttpResponse {
            val next = index + 1
            val nextInterceptor = env.interceptors.getOrNull(next)

            if (nextInterceptor == null) {
                throw IllegalStateException(
                    "Seem's like there's no interceptor, which really executes the request, " +
                            "so chain cannot be completed. Interceptors: ${env.interceptors}"
                )
            }

            return nextInterceptor.intercept(PipelineImpl(next, request, env))
        }
    }
}