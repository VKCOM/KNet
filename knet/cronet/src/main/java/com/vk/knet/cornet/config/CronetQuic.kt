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

import com.vk.knet.core.http.Host

data class CronetQuic(
    val hints: List<Host> = emptyList(),
    val maxServerConfigs: Int = DEFAULT_MAX_SERVER_CONFIG,
    val idleConnectionTimeout: Int = DEFAULT_IDLE_CONNECTION,
    val reducedPingTimeout: Int = DEFAULT_PING_INTERVAL,
    val closeSessionOnIpChange: Boolean = true,
    val goAwaySessionOnIpChange: Boolean = true,
    val migrateSessionOnNetworkChange: Boolean = false,
    val migrateSessionEarly: Boolean = false,
    val migrateIdleSession: Boolean = false,
    val originsToForceQuicOn: Boolean = true,
    val recvBufferOptimizations: Boolean = true,
    val disableTlsZeroRtt: Boolean = true,
    val retryAlternateNetworkBeforeHandshake: Boolean = true,
    val version: String = DEFAULT_QUIC_VERSION,
    val additional: Map<String, Any> = emptyMap()
) {
    companion object {
        private const val DEFAULT_MAX_SERVER_CONFIG = 0
        private const val DEFAULT_QUIC_VERSION = "h3-29"
        private const val DEFAULT_IDLE_CONNECTION = 30 // sec
        private const val DEFAULT_PING_INTERVAL = 5 // sec

        val Default = CronetQuic()
    }
}
