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
package com.vk.knet.core

import com.vk.knet.core.http.*
import com.vk.knet.core.utils.AnyThread
import com.vk.knet.core.utils.ByteArrayPool
import com.vk.knet.core.utils.WorkerThread

class Knet(
    val engine: KnetEngine,
    val pool: ByteArrayPool,
    val interceptors: List<HttpInterceptor>
) {
    companion object {
        @Suppress("FunctionName")
        fun Build(
            engine: KnetEngine,
            apply: (Builder.() -> Builder) = { this }
        ): Knet {
            val builder = Builder(engine).apply()
            return builder.build()
        }
    }

    /**
     * Отправка запроса на выполнение.
     * Все запросы являются блокирующими, потому вызывающий поток будет ожидать завершения запроса.
     */
    @WorkerThread
    fun execute(request: HttpRequest): HttpResponse {
        val pipeline = HttpPipelineFactory.create(HttpPipeline.Helpers(pool), engine, request, interceptors)
        return pipeline.proceed(request)
    }

    /**
     * Завершение работы. Все выполняющиеся запросы будут прерваны
     */
    @AnyThread
    fun shutdown() {
        engine.shutdown()
    }

    fun rebuild(
        apply: Builder.() -> Builder
    ): Knet {
        val builder = Builder(engine).apply()
        return builder.build()
    }

    class Builder
    constructor(
        private val engine: KnetEngine
    ) {

        private var pool: ByteArrayPool? = null
        private val interceptors: MutableList<HttpInterceptor> = mutableListOf()

        fun addGlobalInterceptor(interceptor: HttpInterceptor) = apply {
            interceptors.add(interceptor)
        }

        fun removeInterceptor(interceptor: HttpInterceptor) = apply {
            interceptors.remove(interceptor)
        }

        fun bufferPool(pool: ByteArrayPool) = apply {
            this.pool = pool
        }

        fun build(): Knet {
            val executor = engine
            val pool = this.pool ?: ByteArrayPool.DEFAULT
            val pipeline = ExecuteInterceptor(executor)

            return Knet(
                engine = executor,
                interceptors = interceptors.plus(pipeline),
                pool = pool
            )
        }
    }

    private class ExecuteInterceptor(
        val httpExecutor: HttpExecutor
    ) : HttpInterceptor {
        override fun intercept(pipeline: HttpPipeline): HttpResponse {
            return httpExecutor.execute(pipeline.request, pipeline.env)
        }
    }
}