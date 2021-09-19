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
package com.vk.knet.cornet.utils.redirect

import com.vk.knet.core.exceptions.HttpRedirectException
import com.vk.knet.core.http.HttpRequest

class DefaultRedirect(
    private val followRedirects: Boolean,
    private val followSslRedirects: Boolean,
) : Redirect {

    override fun onRedirect(location: String, httpRequest: HttpRequest): Redirect.Result {
        if (!followRedirects) {
            throw HttpRedirectException("All redirects disabled. Trying to redirect from origin '${httpRequest.url}' to '$location'")
        }
        if (httpRequest.isHttp && location.startsWith("https://") && !followSslRedirects) {
            throw HttpRedirectException("SSL redirects disabled. Trying to redirect from origin '${httpRequest.url}' to '$location'")
        }
        if (httpRequest.isHttps && location.startsWith("http://") && !followSslRedirects) {
            throw HttpRedirectException("SSL redirects disabled. Trying to redirect from origin '${httpRequest.url}' to '$location'")
        }

        return Redirect.Result.Allow
    }
}