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

import com.vk.knet.core.utils.GuardedBy
import com.vk.knet.core.utils.WorkerThread
import org.chromium.net.ExperimentalCronetEngine
import java.io.File
import java.text.SimpleDateFormat
import java.util.*


/**
                            [isFull == false]
 * ------------------------------------------------------------------------------
 * Default logging level, which is expected to be light-weight and
 * does best-effort stripping of privacy/security sensitive data.
 *
 * Includes most HTTP request/response headers, but strips cookies and
 * auth.
 * Does not include the full bytes read/written to sockets.


                            [isFull == true]
 * ------------------------------------------------------------------------------
 * Logging level that includes everything from kDefault, plus sensitive data
 * that it may have strippped.

 * Logging level that includes everything that is possible to be logged.

 * Includes the actual bytes read/written to sockets
 * Will result in large log files.
 */
class CronetLog(
    private val config: Config,
    private val engine: ExperimentalCronetEngine
) : KnetNetlog {

    data class Config(
        val path: File,
        val maxSize: Int,
        val isFull: Boolean = false
    )

    @GuardedBy("lock")
    private var isStarted = false
    private val lock = Object()

    override val isRunning: Boolean get() {
        synchronized(lock) {
            return isStarted
        }
    }

    override val path = config.path
    override val maxSize = config.maxSize

    /**
     * Enable writing detailed logs of all network requests to the directory.
     * @return File / folder to write to. If null, then logging is not supported
     */
    @WorkerThread
    @GuardedBy("lock")
    override fun start(): Boolean {
        synchronized(lock) {
            if (isStarted) {
                return false
            }

            val format = SimpleDateFormat("dd-MMM-yyyy_HH-mm-ss", Locale.US)
            val label = format.format(Date(System.currentTimeMillis()))

            val netlogDir = File("${config.path.absolutePath}/net_log_$label")
            if (!netlogDir.exists()) {
                netlogDir.deleteRecursively()
            }
            netlogDir.mkdirs()
            engine.startNetLogToDisk(netlogDir.absolutePath, false, config.maxSize)
            isStarted = true
            return isStarted
        }
    }

    /**
     * Disable recording of detailed logs of all network requests
     * @return The file / folder where the recording was made. null if logging was not previously started
     */
    @WorkerThread
    @GuardedBy("lock")
    override fun stop(): Boolean {
        synchronized(lock) {
            if (!isStarted) {
                return false
            }

            engine.stopNetLog()
            isStarted = false
            return isStarted
        }
    }


    /**
     * Deleting the previous record of detailed logs.
     * If the function is called after [start], but before [stop] (during recording),
     * then the logs will be cleared, but recording will resume after that.
     */
    @WorkerThread
    @GuardedBy("lock")
    override fun clear() {
        synchronized(lock) {
            val wasRunning = isStarted
            stop()
            config.path.deleteRecursively()
            if (wasRunning) {
                start()
            }
        }
    }
}
