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
package com.vk.knet.core.utils

import com.vk.knet.core.http.HttpRequest

/**
 * [HttpRequest] lifecycle listener for stat or logging purposes
 */
interface HttpRequestLifecycleListener {
    /**
     * called when [HttpRequest] was launched by the client
     */
    fun onRequestLaunched(request: HttpRequest) {}

    /**
     * called when the client successfully set up the session
     */
    fun onSessionSetup(request: HttpRequest) {}

    /**
     * called when session setup failed
     */
    fun onSessionSetupFailed(request: HttpRequest, e: Throwable) {}

    /**
     * called when the session successfully started for the request
     */
    fun onSessionStarted(request: HttpRequest) {}

    /**
     * called when session start failed
     */
    fun onSessionStartFailed(request: HttpRequest, e: Throwable) {}

    /**
     * called on request connection start
     */
    fun onStartConnection(request: HttpRequest) {}

    /**
     * called on request connection started
     */
    fun onConnectionStarted(request: HttpRequest, connectionTime: Long) {}

    /**
     * called when connection start failed
     */
    fun onStartConnectionFailed(request: HttpRequest, error: Throwable) {}

    /**
     * called when we receive response info for the request and ready to read response data
     */
    fun onResponseInfoReceived(request: HttpRequest) {}

    /**
     * called when we can't get response info
     */
    fun onErrorResponseInfo(request: HttpRequest, e: Throwable) {}

    /**
     * called on before the request session started
     */
    fun onStartSession(request: HttpRequest) {}

    /**
     * called just after the request session ended
     */
    fun onCloseSession(request: HttpRequest) {}
}