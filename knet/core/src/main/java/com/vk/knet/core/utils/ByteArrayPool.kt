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

import java.util.*
import java.util.concurrent.locks.ReentrantLock
import kotlin.collections.ArrayDeque
import kotlin.concurrent.withLock

class ByteArrayPool(
    private val max: Int,
    private val bufferSize: Int
) {
    private val lock = ReentrantLock()
    private val pool = ArrayDeque<ByteArray>(max)

    fun obtain(): ByteArray {
        return lock.withLock {
            pool.removeFirstOrNull() ?: createBuffer()
        }
    }

    fun recycle(buffer: ByteArray) {
        lock.withLock {
            Arrays.fill(buffer, 0)

            if (pool.size < max) {
                pool.add(buffer)
            }
        }
    }

    fun rc(): RcArray {
        return RcArray(this)
    }

    private fun createBuffer(): ByteArray {
        return ByteArray(bufferSize)
    }

     companion object {
         val DEFAULT = ByteArrayPool(10, 1024 * 32)
     }
}
