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
 * Класс для автоматического ресайклинга, не предназначен для
 * - использования вне библиотки
 * - использования между 2 и более потоками
 *
 * Начальное состояние [counter] == 0, чтобы отчистить, необходимо
 * Необходимо сделать один или более retain и такое же количество release
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
