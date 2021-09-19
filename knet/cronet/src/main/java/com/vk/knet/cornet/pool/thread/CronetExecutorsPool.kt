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
package com.vk.knet.cornet.pool.thread

import com.vk.knet.cornet.CronetHttpLogger
import com.vk.knet.cornet.utils.CronetLogger
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

class CronetExecutorsPool(
    max: Int
) {
    private val counter = AtomicInteger(0)

    private val pool = LinkedBlockingDeque<CronetExecutor>(max)

    private val recycler = Executors.newSingleThreadExecutor { run ->
        Thread(run, "vk-cronet-executors-pool")
    }

    init {
        if (recycler is ThreadPoolExecutor) {
            recycler.allowCoreThreadTimeOut(true)
        }

        repeat(max) {
            pool.add(createBuffer())
        }
    }

    fun recycle(executor: CronetExecutor) {
        enqueueRecycler(executor)
    }

    fun obtain(): CronetExecutor {
        CronetLogger.debug(CronetHttpLogger.DebugType.EXEC_POOL, "obtain ${pool.size}")
        return pool.takeLast()
    }

    private fun enqueueRecycler(executor: CronetExecutor) {
        recycler.execute {
            recycleInner(executor)
        }
    }

    private fun recycleInner(executor: CronetExecutor) {
        pool.add(executor)
        CronetLogger.debug(CronetHttpLogger.DebugType.EXEC_POOL, "recycle ${pool.size}")
    }

    private fun createBuffer(): CronetExecutor {
        return CronetExecutor(
            0,
            1,
            1000L,
            TimeUnit.MILLISECONDS,
            LinkedBlockingQueue()
        ) { runnable ->
           Thread(runnable, "Cronet-Requests-Executor-${counter.getAndIncrement()}")
        }
    }
}