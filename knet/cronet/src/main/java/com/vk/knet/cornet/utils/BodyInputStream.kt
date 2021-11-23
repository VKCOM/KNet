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
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * This is a very cool crutch class that allows you to link the Cronet callback architecture to [InputStream].
 *
 * The general principle is, on the whole, simple.
 * 1) We catch all calls to read * and read from the intermediate [buffer] (which is given by Cronet)
 * 2) If [buffer] has already been read, then we request a new buffer via [Delegate.onRead].
 * In this case, there will be an additional request for data from the network, so these functions may well throw Exception.
 * All logic for catching timeouts is delegated to byteBufferProvider.
 * 3) And so on until we reach the state [BUFFER_STATE_FINISHED]
 */
internal class BodyInputStream(
    private val delegate: Delegate
) : InputStream() {

    interface Delegate {
        fun onError(error: Throwable)
        fun onClosed()
        fun onRead(): ByteBuffer?
    }

    companion object {

        /**
         * [InputStream] is waiting for updated values [buffer].
         * From this state, you can go to both [BUFFER_STATE_SET] and [BUFFER_STATE_FINISHED].
         */
        private const val BUFFER_STATE_AWAIT = 1

        /**
         * Status indication that fresh [buffer] is set and [InputStream] can read data from it.
         * When it has read the entire buffer, the [bufferState] state will transition to [BUFFER_STATE_AWAIT].
         */
        private const val BUFFER_STATE_SET = 2

        /**
         * Status indication that there will be no new data in [buffer], because the server has already given all the necessary data.
         * In this case, any read * operation from [InputStream] must return -1 (according to its contract).
         */
        private const val BUFFER_STATE_FINISHED = 3

        /**
         * Indication of the state that there will be no new data in [buffer], due to the fact that [BodyInputStream]
         * was closed.
         *
         * In this case, any read * operation from [InputStream] must return -1 (according to its contract).
         */
        private const val BUFFER_STATE_CLOSED = 4

    }

    private val lock = ReentrantLock()
    private val isClosed = AtomicBoolean(false)

    @Volatile
    @GuardedBy("lock")
    private var buffer: ByteBuffer? = null

    @Volatile
    @GuardedBy("lock")
    private var bufferState = BUFFER_STATE_AWAIT

    /**
     * Wrapper over read * operations from [InputStream].
     * Calls [bufferQueueReader], which already does all the magic.
     * Here we just handle the error
     */
    private inline fun bufferQueue(crossinline readFun: (ByteBuffer) -> Int): Int {
        try {
            return bufferQueueReader(readFun)
        } catch (th: Throwable) {
            delegate.onError(th)
            throw th
        }
    }

    /**
     *  Wrapper over read * operations from [InputStream].
     * Implements all the magic of changing [bufferState] and watching [buffer].
     * The implementation is made on the basis of a loop, not recursion, because StackOverflow is fraught.
     */
    private inline fun bufferQueueReader(readFun: (ByteBuffer) -> Int): Int {
        lock.withLock {
            while (true) {
                // No new buffers are expected because the server has already returned all the data.
                // In this case, just return -1.
                if (bufferState >= BUFFER_STATE_FINISHED) {
                    return -1
                }

                if (isClosed.get()) {
                    bufferState = BUFFER_STATE_CLOSED
                    return -1
                }

                // The buffer is not ready yet (or we have already subtracted all the old one). Requesting new data
                if (bufferState == BUFFER_STATE_AWAIT) {
                    buffer = delegate.onRead()
                    bufferState = if (buffer == null) BUFFER_STATE_FINISHED else BUFFER_STATE_SET
                    continue
                }

                // The buffer is ready, you can use it for reading
                // If read operations start returning -1, then we have read the buffer to the end.
                // In this case, you need to request a new one.
                if (bufferState == BUFFER_STATE_SET) {
                    val safeBuffer = buffer
                        ?: throw IllegalStateException("Buffer can't be null with state - $bufferState!")

                    val read = readFun.invoke(safeBuffer)
                    if (read < 0) {
                        bufferState = BUFFER_STATE_AWAIT
                        continue
                    }
                    return read
                }
            }
        }
    }

    /**
     * Implementation of the [read] method from [InputStream]. Required to override.
     */
    override fun read(): Int {
        return bufferQueue { byteBuffer ->
            if (!byteBuffer.hasRemaining()) {
                return@bufferQueue -1
            }

            byteBuffer.get().toInt() // and 0xFF
        }
    }

    /**
     * Implementation of the [read] method from [InputStream]. It is not necessary to override it.
     * Nevertheless, we do this in order to more efficiently read a set of bytes at once.
     */
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        return bufferQueue { byteBuffer ->
            val bytesToRead = minOf(len, byteBuffer.remaining())

            if (bytesToRead <= 0) {
                return@bufferQueue -1
            }

            byteBuffer.get(b, off, bytesToRead)
            bytesToRead
        }
    }

    override fun close() {
        if (isClosed.compareAndSet(false, true)) {
            super.close()
            delegate.onClosed()
        }
    }
}