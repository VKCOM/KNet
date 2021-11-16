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
 * Это - очень замечательный класс-костыль, позволяющий связать callback-архитектуру Cronet и [InputStream].
 *
 * Общий принцип, же, в целом, простой.
 * 1) Ловим все вызовы на read* и читаем из промежуточного [buffer] (который отдает Cronet)
 * 2) Если [buffer] уже успели прочитать, то запрашиваем новый буфер через [Delegate.onRead].
 *    В этом случае будет дозапрос данных из сети, потому эти функции вполне могут кидать Exception.
 *    Вся логика по отлову timeouts делегирована byteBufferProvider.
 * 3) И так до тех пор, пока не достигнем состояния [BUFFER_STATE_FINISHED]
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
         * [InputStream] находится в состоянии ожидания обновленных значений [buffer].
         * Из этого состояния можно перейти как в [BUFFER_STATE_SET], так и в [BUFFER_STATE_FINISHED].
         */
        private const val BUFFER_STATE_AWAIT = 1

        /**
         * Индикация состояния, что установлен свежий [buffer] и [InputStream] может читать данные из него.
         * Когда он прочтет весь буфер, то состояние [bufferState] перейдет в [BUFFER_STATE_AWAIT].
         */
        private const val BUFFER_STATE_SET = 2

        /**
         * Индикация состояния, что новых данных в [buffer] не будет, ибо сервер уже отдал все нужные данные.
         * В таком случае любая read* операция из [InputStream] должна отдать -1 (согласно его контракту).
         */
        private const val BUFFER_STATE_FINISHED = 3

        /**
         * Индикация состояния, что новых данных в [buffer] не будет, по причине того, что [BodyInputStream]
         * был закрыт.
         *
         * В таком случае любая read* операция из [InputStream] должна отдать -1 (согласно его контракту).
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
     * Обертка над read* операциями из [InputStream].
     * Вызывает [bufferQueueReader], который уже осуществляют всю магию.
     * Здесь же просто обрабатываем ошибку
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
     * Обертка над read* операциями из [InputStream].
     * Реализует всю магию по смене [bufferState] и наблюдению за [buffer].
     * Реализация сделана на основе цикла, а не рекурсии, ибо чревато StackOverflow.
     */
    private inline fun bufferQueueReader(readFun: (ByteBuffer) -> Int): Int {
        lock.withLock {
            while (true) {
                // Новых буферов не предвидится, так как сервер уже вернул все данные.
                // В таком случае, просто возвращаем -1.
                if (bufferState >= BUFFER_STATE_FINISHED) {
                    return -1
                }

                if (isClosed.get()) {
                    bufferState = BUFFER_STATE_CLOSED
                    return -1
                }

                // Буфер пока не готов (либо мы уже вычитали весь старый). Запрашиваем новые данные
                if (bufferState == BUFFER_STATE_AWAIT) {
                    buffer = delegate.onRead()
                    bufferState = if (buffer == null) BUFFER_STATE_FINISHED else BUFFER_STATE_SET
                    continue
                }

                // Буфер готов, можно использовать его для чтения
                // Если операции чтения станут возвращать -1, значит мы прочитали буфер до конца.
                // В таком случае надо запросить новый.
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
     * Реализация [read] метода из [InputStream]. Является обязательным методом для переопределения.
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
     * Реализация [read] метода из [InputStream]. Переопределять её не обязательно.
     * Тем не менее мы это делаем, чтобы эффективнее читать сразу набор байт.
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