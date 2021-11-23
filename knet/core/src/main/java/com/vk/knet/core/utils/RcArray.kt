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

import java.lang.IllegalStateException

/**
 * Reference Counter implementation.
 *
 * Class for auto recycling, not intended to:
 * - use outside the library
 * - use between 2 or more threads
 *
 * Initial state [counter] == 0, in order to clean up, you need
 * one or more retains and the same number of releases must be made
 */
class RcArray(
    private val pool: ByteArrayPool
) {
    private var counter = 0
    private val array: Lazy<ByteArray> = lazy(LazyThreadSafetyMode.NONE) { pool.obtain() }

    fun retain(): ByteArray {
        if (isDealloc()) {
            throw IllegalStateException("Array has already dealloc!")
        }

        counter++
        return array.value
    }

    fun release() {
        if (counter == 0) {
            return
        }

        counter--


        if (isDealloc()) {
            pool.recycle(array.value)
        }
    }

    fun isDealloc(): Boolean {
        return counter == 0 && array.isInitialized()
    }

    fun clone(): RcArray {
        return RcArray(pool)
    }
}
