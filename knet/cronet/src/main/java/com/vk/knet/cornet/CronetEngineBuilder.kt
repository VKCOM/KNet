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
import com.vk.knet.cornet.config.CronetCache
import com.vk.knet.cornet.config.CronetQuic
import com.vk.knet.cornet.utils.CronetLogger
import org.chromium.net.CronetEngine
import org.chromium.net.ExperimentalCronetEngine
import org.json.JSONObject
import java.io.File

// Quic options:
// No docs about experimental options
// See possible info/samples in chromium sources:
// 1) https://chromium.googlesource.com/chromium/src/+/refs/heads/master/components/cronet/android/test/javatests/src/org/chromium/net/QuicTest.java
// 2) https://chromium.googlesource.com/chromium/src/+/refs/heads/master/net/http/http_network_session.cc
// 3) Примерный список, что там может быть:
//    https://chromium.googlesource.com/chromium/src/+/master/components/cronet/url_request_context_config.cc
//    https://chromium.googlesource.com/chromium/src/+/master/components/cronet/url_request_context_config_unittest.cc
//    https://chromium.googlesource.com/chromium/src/net/+/master/quic/quic_stream_factory_fuzzer.cc
//    https://chromium.googlesource.com/chromium/src/net/+/master/quic/quic_context.h
//
// Params:
// supported_versions - List - Versions of QUIC which may be used.
//
// max_packet_length - Limit on the size of QUIC packets.
//
// Maximum number of server configs that are to be stored in
// HttpServerProperties, instead of the disk cache.
// max_server_configs_stored_in_properties - int
//
// Specifies QUIC idle connection state lifetime.
// idle_connection_timeout_seconds - int = kIdleConnectionTimeout = base::TimeDelta::FromSeconds(30);
//
// Reduce PING timeout when connection blackholes after the handshake
// Specifies the reduced ping timeout subsequent connections should use when
// a connection was timed out with open streams.
// reduced_ping_timeout_seconds - int
//
// Retry requests which fail with QUIC_PROTOCOL_ERROR, and mark QUIC
// broken if the retry succeeds.
// retry_without_alt_svc_on_quic_errors - bool
//
// If true, bidirectional streams over QUIC will be disabled.
// disable_bidirectional_streams = false - bool
//
// If true, all QUIC sessions are closed when any local IP address changes.
// close_sessions_on_ip_change - bool
//
// If true, all QUIC sessions are marked as goaway when any local IP address
// changes.
// goaway_sessions_on_ip_change - bool
//
// If true, connection migration v2 will be used to migrate existing
// sessions to network when the platform indicates that the default network
// is changing.
// migrate_sessions_on_network_change_v2 - bool
//
// If true, connection migration v2 may be used to migrate active QUIC
// sessions to alternative network if current network connectivity is poor.
// migrate_sessions_early_v2 - bool
//
// Maximum time that a session can have no retransmittable packets on the
// wire. Set to zero if not specified and no retransmittable PING will be
// sent to peer when the wire has no retransmittable packets.
// retransmittable_on_wire_timeout_milliseconds - int
//
// If true, a new connection may be kicked off on an alternate network when
// a connection fails on the default network before handshake is confirmed.
// retry_on_alternate_network_before_handshake - bool
//
// If true, an idle session will be migrated within the idle migration
// period.
// migrate_idle_sessions - bool
//
// A session can be migrated if its idle time is within this period.
// idle_session_migration_period_seconds - int
//
// Maximum time the session could be on the non-default network before
// migrates back to default network. Defaults to
// kMaxTimeOnNonDefaultNetwork.
// max_time_on_non_default_network_seconds - int
//
// If true, allows migration of QUIC connections to a server-specified
// alternate server address.
// bool allow_server_migration = false;
//
// If true, allows QUIC to use alternative services with a different
// hostname from the origin.
// bool allow_remote_alt_svc = true;
//
// If true, the quic stream factory may race connection from stale dns
// result with the original dns resolution
// bool race_stale_dns_on_connection = false;
//
// If true, the quic session may mark itself as GOAWAY on path degrading.
// bool go_away_on_path_degrading = false;
//
// If true, bidirectional streams over QUIC will be disabled.
// bool disable_bidirectional_streams = false;
//
// If true, estimate the initial RTT for QUIC connections based on network.
// bool estimate_initial_rtt = false;
//
// If true, client headers will include HTTP/2 stream dependency info
// derived from the request priority.
// bool headers_include_h2_stream_dependency = false;
//
// The initial rtt that will be used in crypto handshake if no cached
// smoothed rtt is present.
// initial_rtt_for_handshake
//
// If true, QUIC with TLS will not try 0-RTT connection.
// bool disable_tls_zero_rtt = false;
//
// If true, gQUIC requests will always require confirmation.
// bool disable_gquic_zero_rtt = false;
//
class CronetEngineBuilder(
    private val context: Context
) {

    private var cache: CronetCache? = null
    private var loader: CronetLibLoader? = null
    private var isClearBroken: Boolean = false
    private var hasHttp2: Boolean = false
    private var hasBrotli: Boolean = false
    private var quicOptions: CronetQuic? = null

    fun withLibLoader(loader: CronetLibLoader?) = apply { this.loader = loader }

    fun withCache(cache: CronetCache) = apply { this.cache = cache }

    fun withHttp2() = apply { this.hasHttp2 = true }

    fun withBrotli() = apply { this.hasBrotli = true }

    fun withQuic(options: CronetQuic) = apply { this.quicOptions = options }

    /**
     * Если сервер не получает от бэка ответ по UDP за 4 секнуды QUIC хост помечается как broken.
     * При отметке хостка как broken, проставляется expiration, запрещающий его использование.
     * Expiration рабоатет в виде Backoff, начинается с 300 sec до 300 * 2 ^ broken_count ~ 42 hours
     */
    fun withClearBroken(isClear: Boolean) = apply {
        this.isClearBroken = isClear
    }

    fun build(): ExperimentalCronetEngine {
        if (isClearBroken) {
            clearBrokenHosts(context)
        }

        val builder = ExperimentalCronetEngine.Builder(context)

        // Setup storage for cache & config.
        // Storage is required for 0-RTT
        cache?.let { cache ->
            when (cache) {
                is CronetCache.Disk -> {
                    try {
                        if (!cache.path.exists()) {
                            cache.path.mkdirs()
                        }

                        builder.setStoragePath(cache.path.absolutePath)
                        builder.enableHttpCache(CronetEngine.Builder.HTTP_CACHE_DISK_NO_HTTP, cache.size)
                    } catch (e: Exception) { }
                }
                is CronetCache.InMemory -> {
                    builder.enableHttpCache(CronetEngine.Builder.HTTP_CACHE_IN_MEMORY, cache.size)
                }
                is CronetCache.Empty -> {}
            }
        }

        if (loader != null) {
            builder.setLibraryLoader(object : CronetEngine.Builder.LibraryLoader() {
                override fun loadLibrary(lib: String) {
                    loader?.loadLibrary(lib)
                }
            })
        }

        builder.enableHttp2(hasHttp2)
        builder.enableBrotli(hasBrotli)

        val options = quicOptions
        val isQuicEnabled = options != null

        CronetLogger.debug(CronetHttpLogger.DebugType.CLIENT_BUILDER,"[cronet] QuicOptions - $options")

        builder.enableQuic(isQuicEnabled)
        if (options != null) {
            options.hints.forEach {
                builder.addQuicHint(it.host, it.port, it.port)
            }

            val experimentalOptions = JSONObject().apply {
                put("QUIC", JSONObject().apply {
                    put("max_server_configs_stored_in_properties", options.maxServerConfigs) // Allows to use 0-RTT on cold start
                    put("idle_connection_timeout_seconds", options.idleConnectionTimeout) // QUIC-session will be dropped after specified time of inactivity
                    put("reduced_ping_timeout_seconds", options.reducedPingTimeout) // Ping interval of QUIC-connection
                    put("close_sessions_on_ip_change", options.closeSessionOnIpChange)
                    put("goaway_sessions_on_ip_change", options.goAwaySessionOnIpChange)
                    put("migrate_sessions_on_network_change_v2", options.migrateSessionOnNetworkChange)
                    put("migrate_sessions_early_v2", options.migrateSessionEarly)
                    put("migrate_idle_sessions", options.migrateIdleSession)
                    put("origins_to_force_quic_on", options.originsToForceQuicOn)
                    put("enable_socket_recv_optimization", options.originsToForceQuicOn)
                    put("disable_tls_zero_rtt", options.disableTlsZeroRtt)
                    put("retry_on_alternate_network_before_handshake", options.retryAlternateNetworkBeforeHandshake)
                    put("quic_version", options.version)

                    options.additional.entries.forEach { (name, value) ->
                        put(name, value.toString())
                    }
                })
            }
            builder.setExperimentalOptions(experimentalOptions.toString())
        }
        return builder.build()
    }

    private fun clearBrokenHosts(context: Context) {
        val path = File(context.filesDir, "network_internal/cronet/prefs/local_prefs.json")
        if (!path.exists()) {
            return
        }

        val rootJson = try {
            val text = path.bufferedReader().readText()
             JSONObject(text)
        } catch (th: Throwable) {
            CronetLogger.error(th)
            try {
                path.delete()
            } catch (th: Throwable) {
                CronetLogger.error(th)
            }
            return
        }

        val json = rootJson.optJSONObject("net") ?: return

        var isRemoved = false

        val httpServers = json.optJSONObject("http_server_properties")
        if (httpServers != null) {
            val broken = httpServers.remove("broken_alternative_services")
            if (broken != null) {
                CronetLogger.error("Startup QUIC executor found broken hosts: $broken")
                isRemoved = true
            }
        }

        val broken = json.remove("broken_alternative_services")
        if (broken != null) {
            CronetLogger.error("Startup QUIC executor found broken hosts: $broken")
            isRemoved = true
        }

        if (isRemoved) {
            path.writeText(rootJson.toString(), Charsets.UTF_8)
        }
    }
}
