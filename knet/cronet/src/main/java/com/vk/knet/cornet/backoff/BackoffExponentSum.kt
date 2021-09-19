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
package com.vk.knet.cornet.backoff

/**
 * Генерирует экспоненциальную последовательность чисел с остатком
 * @param init начальное значение последовательности
 * @param sum сумма чисел последовательности
 * @example:
 * 2 and 30 and 2 -> 2 4 8 16 done
 * 2 and 29 and 2 -> 2 4 8 15 done
 * 2 and 31 and 2 -> 2 4 8 16 1 done
 */
class BackoffExponentSum(
    private val init: Long,
    private val sum: Long,
    private val multiplier: Int = EXPONENT_MULTIPLIER
) : Backoff<Long> {

    companion object {
        private const val EXPONENT_MULTIPLIER = 2
    }

    private var step = 1
    private var accumulator = 0L
    private var current: Long = init

    override fun next(): Long {
        current = kotlin.math.min(init * step, sum - accumulator)
        step *= multiplier
        accumulator += current
        return current
    }

    override fun isDone(): Boolean {
        return accumulator >= sum
    }

    override fun reset() {
        step = 1
        accumulator = 0
        current = init
    }
}
