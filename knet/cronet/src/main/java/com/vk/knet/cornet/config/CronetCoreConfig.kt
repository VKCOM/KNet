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
package com.vk.knet.cornet.config

/**
 * @param connectTimeoutMs Connection timeout
 * @param readTimeoutMs Timeout for reading data from the server
 * @param writeTimeoutMs Timeout for writing data to the socket
 * @param maxConcurrentRequests Number of possible concurrent network requests
 * @param maxConcurrentRequestsPerHost Number of possible concurrent requests to one host
 * @param followRedirects Redirect permission flag
 * @param followSslRedirects Flag to allow redirects between http and https
 */
data class CronetCoreConfig(
    val quicConfig: CronetQuic?,
    val isEnableHttp2: Boolean,

    val isUseBrotli: Boolean,

    val connectTimeoutMs: Long,
    val readTimeoutMs: Long,
    val writeTimeoutMs: Long,

    val maxConcurrentRequests: Int,
    val maxConcurrentRequestsPerHost: Int,

    val followRedirects: Boolean,
    val followSslRedirects: Boolean,

    val isClearHosts: Boolean,
)
