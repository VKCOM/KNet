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
 * Simple wrapper for waiting for [value] changes with the ability to reset (update) the timeout.
 * Required to work with request timeouts:
 * every time when some chunk of data is sent or read - we restart the timeout.
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
     * Changing [ConditionLock.value] to [value]
     */
    fun change(value: Boolean) {
        lock.withLock {
            this.value = value
            condition.signalAll()
        }
    }

    /**
     * Restart previously started [await] with updated timeout.
     * Ie new thresholds in time will change to now () + timeout
     */
    fun restartAwaitTimeouts() {
        lock.withLock {
            restartAwaitTimeouts = true
            condition.signalAll()
        }
    }

    /**
     * Start waiting until [ConditionLock.value] equals [value].
     */
    fun await(value: Boolean) {
        lock.withLock {
            while (this.value != value) {
                condition.await()
            }
        }
    }

    /**
     * Start waiting until [value] equals [value] with a timeout.
     * @return true if waited. false if not waited (reached [timeoutMs])
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