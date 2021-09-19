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
package com.vk.knet.core.http.metric

data class HttpMetricPoints(
    val dnsStart: Long,
    val dnsEnd: Long,

    val connectStart: Long,
    val connectEnd: Long,

    val secureStart: Long,
    val secureEnd: Long,

    val requestStart: Long,
    val requestEnd: Long,

    val responseStart: Long,
    val responseEnd: Long,
) {
    val intervals: HttpMetricIntervals by lazy {
        val ttfbStat = if (connectStart != 0L) connectStart else requestStart
        val ttfb = ttfbStat to responseStart

        HttpMetricIntervals(
            domainLookup = dnsStart to dnsEnd,
            rtt = connectStart to secureStart,
            tls = secureStart to secureEnd,
            connection = connectStart to connectEnd,
            response = requestStart to responseEnd,
            ttfb = ttfb
        )
    }
}

private infix fun Long.to(another: Long): Long {
    if (this > another) {
        return -1
    }

    return diff(this, another)
}

private fun diff(first: Long, second: Long): Long {
    val min = first.coerceAtMost(second)
    val max = first.coerceAtLeast(second)

    if (min == 0L && max == 0L) {
        return 0L
    }

    if (min == 0L || max == 0L) {
        return -1
    }

    return max - min
}