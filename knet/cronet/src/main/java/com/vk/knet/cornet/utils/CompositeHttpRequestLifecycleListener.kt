package com.vk.knet.cornet.utils

import com.vk.knet.core.http.HttpRequest
import com.vk.knet.core.utils.HttpRequestLifecycleListener

internal class CompositeHttpRequestLifecycleListener(
    private vararg val listeners: HttpRequestLifecycleListener
): HttpRequestLifecycleListener {

    override fun onRequestLaunched(request: HttpRequest) {
        listeners.forEach { it.onRequestLaunched(request) }
    }

    override fun onSessionSetup(request: HttpRequest) {
        listeners.forEach { it.onSessionSetup(request) }
    }

    override fun onSessionSetupFailed(request: HttpRequest, e: Throwable) {
        listeners.forEach { it.onSessionSetupFailed(request, e) }
    }

    override fun onSessionStarted(request: HttpRequest) {
        listeners.forEach { it.onSessionStarted(request) }
    }

    override fun onSessionStartFailed(request: HttpRequest, e: Throwable) {
        listeners.forEach { it.onSessionStartFailed(request, e) }
    }

    override fun onStartConnection(request: HttpRequest) {
        listeners.forEach { it.onStartConnection(request) }
    }

    override fun onConnectionStarted(request: HttpRequest, connectionTime: Long) {
        listeners.forEach { it.onConnectionStarted(request, connectionTime) }
    }

    override fun onStartConnectionFailed(request: HttpRequest, error: Throwable) {
        listeners.forEach { it.onStartConnectionFailed(request, error) }
    }

    override fun onResponseInfoReceived(request: HttpRequest) {
        listeners.forEach { it.onResponseInfoReceived(request) }
    }

    override fun onErrorResponseInfo(request: HttpRequest, e: Throwable) {
        listeners.forEach { it.onErrorResponseInfo(request, e) }
    }

    override fun onStartSession(request: HttpRequest) {
        listeners.forEach { it.onStartSession(request) }
    }

    override fun onCloseSession(request: HttpRequest) {
        listeners.forEach { it.onCloseSession(request) }
    }
}