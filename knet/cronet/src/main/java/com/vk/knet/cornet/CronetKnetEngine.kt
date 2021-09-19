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
package com.vk.knet.cornet

import android.content.Context
import com.vk.knet.core.KnetEngine
import com.vk.knet.core.http.*


class CronetKnetEngine
private constructor(
    private val client: com.vk.knet.cornet.experiment.CronetClient,
    private val interceptors: List<HttpInterceptor>
) : KnetEngine {

    companion object {
        @Suppress("FunctionName")
        fun Build(
            context: Context,
            apply: Builder.() -> Unit
        ): CronetKnetEngine {
            val builder = Builder(context)
            builder.apply()
            return builder.build()
        }
    }

    override val id: String = "CRONET"

    val netlog: KnetNetlog get() = client.netlog()

    override fun shutdown() {
        client.shutdown()
    }

    override fun execute(request: HttpRequest, global: HttpPipeline.Env): HttpResponse {
        val pipeline = HttpPipelineFactory.create(global.helper, this, request, interceptors)
        return pipeline.proceed(request)
    }

    class Builder(
        context: Context,
    ) {
        private var builder: com.vk.knet.cornet.experiment.CronetClient.Builder = com.vk.knet.cornet.experiment.CronetClient.Builder(context)
        private val interceptors: MutableList<HttpInterceptor> = mutableListOf()

        fun client(apply: com.vk.knet.cornet.experiment.CronetClient.Builder.() -> com.vk.knet.cornet.experiment.CronetClient.Builder) {
            builder.apply()
        }

        // Interceptors
        fun addInterceptor(interceptor: HttpInterceptor) = apply {
            interceptors.add(interceptor)
        }

        fun removeInterceptor(interceptor: HttpInterceptor) = apply {
            interceptors.remove(interceptor)
        }

        internal fun build(): CronetKnetEngine {
            val httpClient = builder.build()

            val execution = HttpInterceptor { pipeline -> httpClient.execute(pipeline.request) }
            addInterceptor(execution)

            return CronetKnetEngine(httpClient, interceptors)
        }
    }
}
