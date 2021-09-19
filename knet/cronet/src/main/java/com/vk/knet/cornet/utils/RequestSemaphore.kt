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
package com.vk.knet.cornet.utils

import com.vk.knet.core.utils.GuardedBy
import com.vk.knet.cornet.CronetHttpLogger
import java.util.concurrent.Semaphore

/**
 * Управляет возможностью допуска запроса к исполнению согласно лимитам
 */
internal class RequestSemaphore(
    maxConcurrentRequests: Int,
    private val maxConcurrentRequestsPerHost: Int
) {

    /**
     * Семафор на общее количество запросов
     */
    private val overallSemaphore = Semaphore(maxConcurrentRequests)

    /**
     * Семафоры на запросы к определенному хосту
     */
    @GuardedBy("this")
    private val hostsSemaphore = mutableMapOf<String, Semaphore>()

    fun acquire(host: String) {
        getHostSemaphore(host)
            .apply {
                acquire()
                CronetLogger.debug(CronetHttpLogger.DebugType.CLIENT_QUEUE, "[cronet] Host queue - $host | availablePermits - ${availablePermits()} | queueLength $queueLength")
            }

        try {
            overallSemaphore.apply {
                acquire()
                CronetLogger.debug(CronetHttpLogger.DebugType.CLIENT_QUEUE, "[cronet] Common queue | availablePermits - ${availablePermits()} | queueLength $queueLength")
            }
        } catch (e: Throwable) {
            // Если произошла ошибка при взятии потока, необходимо осободить предыдущий Semaphore
            getHostSemaphore(host).release()
        }
    }

    fun release(host: String) {
        overallSemaphore.release()
        CronetLogger.debug(CronetHttpLogger.DebugType.CLIENT_QUEUE, "[cronet] Release common queue")
        getHostSemaphore(host).release()
        CronetLogger.debug(CronetHttpLogger.DebugType.CLIENT_QUEUE, "[cronet] Release host queue - $host")
    }

    @Synchronized
    private fun getHostSemaphore(host: String): Semaphore {
        var semaphore = hostsSemaphore[host]
        if (semaphore == null) {
            semaphore = Semaphore(maxConcurrentRequestsPerHost)
            hostsSemaphore[host] = semaphore
        }
        return semaphore
    }
}