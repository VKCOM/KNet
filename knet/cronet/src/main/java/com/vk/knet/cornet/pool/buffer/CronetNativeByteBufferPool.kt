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
package com.vk.knet.cornet.pool.buffer

import com.vk.knet.cornet.CronetHttpLogger
import com.vk.knet.cornet.utils.ArcNative
import com.vk.knet.cornet.utils.CronetLogger
import java.nio.ByteBuffer
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class CronetNativeByteBufferPool(
    private val max: Int,
    private val bufferSize: Int
) {
    private val lock = ReentrantLock()
    private val pool = ArrayDeque<ByteBuffer>(max)

    fun obtain(): ByteBuffer {
        return lock.withLock {
            CronetLogger.debug(CronetHttpLogger.DebugType.NATIVE_BUFFER, "obtain ${pool.size}")
            pool.removeFirstOrNull() ?: createBuffer()
        }
    }

    fun recycle(buffer: ByteBuffer) {
        lock.withLock {
            buffer.clear()

            if (pool.size < max) {
                pool.add(buffer)
                CronetLogger.debug(CronetHttpLogger.DebugType.NATIVE_BUFFER, "recycle ${pool.size}")
            } else {
                CronetLogger.debug(CronetHttpLogger.DebugType.NATIVE_BUFFER, "recycle buffer has max elements ${pool.size}")
            }
        }
    }

    fun arc(): ArcNative {
        return ArcNative(this)
    }

    private fun createBuffer(): ByteBuffer {
        CronetLogger.debug(CronetHttpLogger.DebugType.NATIVE_BUFFER, "createBuffer ${pool.size}")
        return ByteBuffer.allocateDirect(bufferSize)
    }

    companion object {
        val DEFAULT by lazy { CronetNativeByteBufferPool(10, 1024 * 8) }
    }
}
