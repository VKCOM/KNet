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

import java.util.*
import kotlin.collections.HashMap

enum class HttpMethod(
    val methodName: String
) {

    GET("GET"),
    HEAD("HEAD"),
    POST("POST"),
    PUT("PUT"),
    DELETE("DELETE"),
    CONNECT("CONNECT"),
    OPTIONS("OPTIONS"),
    TRACE("TRACE");

    val isGet: Boolean get() = (this == GET)
    val isPost: Boolean get() = (this == POST)

    companion object {
        /**
         * Чтобы каждый раз не проходится по values уменьшаем поиск с O(n) -> O(1)
         */
        private val methodMap = HashMap<String, HttpMethod>()
            .apply {
                put(GET.methodName, GET)
                put(HEAD.methodName, HEAD)
                put(POST.methodName, POST)
                put(PUT.methodName, PUT)
                put(DELETE.methodName, DELETE)
                put(CONNECT.methodName, CONNECT)
                put(OPTIONS.methodName, OPTIONS)
                put(TRACE.methodName, TRACE)
            }

        fun from(name: String): HttpMethod? {
            val upperName = name.uppercase()
            return methodMap[upperName]
        }
    }
}