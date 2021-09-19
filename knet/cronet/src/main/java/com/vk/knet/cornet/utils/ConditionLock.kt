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

import android.os.SystemClock
import com.vk.knet.core.utils.GuardedBy
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.Lock
import kotlin.concurrent.withLock

/**
 * Простая обертка для ожидания изменения [value] с возможностью сброса (обновления) таймаута.
 * Необходим для работы с таймаутами запросов:
 * каждый раз когда какой-то чанк данных отправляется или читается - мы перезапускаем таймаут.
 */
@Suppress("MemberVisibilityCanBePrivate")
internal class ConditionLock(
    private val lock: Lock,
    @GuardedBy("lock")
    private var value: Boolean = false,
) {

    @GuardedBy("lock")
    private var restartAwaitTimeouts = false

    private val condition = lock.newCondition()

    /**
     * Смена значения [ConditionLock.value] на [value]
     */
    fun change(value: Boolean) {
        lock.withLock {
            this.value = value
            condition.signalAll()
        }
    }

    /**
     * Рестарт ранее запущенных [await] с обновленным таймаутом.
     * Т.е. новые threshold по времени изменится на now() + timeout
     */
    fun restartAwaitTimeouts() {
        lock.withLock {
            restartAwaitTimeouts = true
            condition.signalAll()
        }
    }

    /**
     * Запуск ожидания до тех пор, пока [ConditionLock.value] не станет равным [value].
     */
    fun await(value: Boolean) {
        lock.withLock {
            var i = 0
            while (this.value != value) {
                if (i > 0) {
                    CronetLogger.error("ALARM ALARM ALARM ALARM ALARM ALARM ALARM!")
                }
                condition.await(10000, TimeUnit.MILLISECONDS)
                i++
            }
        }
    }

    /**
     * Запуск ожидания до тех пор, пока [value] не станет равным [value] с таймаутом.
     * @return true, если дождались. false, если не дождались (достигли [timeoutMs])
     */
    fun await(value: Boolean, timeoutMs: Long): Boolean {
        if (timeoutMs <= 0) {
            await(value)
            return true
        }

        lock.withLock {
            var now = now()
            var end = now + timeoutMs
            while (this.value != value && now < end) {
                condition.await(end - now, TimeUnit.MILLISECONDS)
                if (restartAwaitTimeouts) {
                    now = now()
                    end = now + timeoutMs
                    restartAwaitTimeouts = false
                } else {
                    now = now()
                }
            }
            return (this.value == value)
        }
    }

    private fun now(): Long {
        return SystemClock.elapsedRealtime()
    }
}