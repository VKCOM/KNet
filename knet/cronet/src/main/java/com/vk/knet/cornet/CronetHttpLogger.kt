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
package com.vk.knet.cornet

import com.vk.knet.core.log.HttpLogger

interface CronetHttpLogger {

    enum class DebugType {
        NATIVE_BUFFER,
        EXEC_POOL,
        CLIENT_TIMEOUTS,
        CLIENT_QUEUE,
        CLIENT_CALLBACK,
        CLIENT_BUILDER,
        CLIENT_STATE;

        companion object {
            val ALL by lazy { values().toSet() }
            val RELEASE by lazy { setOf(CLIENT_BUILDER, NATIVE_BUFFER, EXEC_POOL) }
        }
    }

    fun error(vararg obj: Any)
    fun info(vararg obj: Any)
    fun debug(type: DebugType, vararg obj: Any)

    companion object {
        fun from(logger: HttpLogger, types: Set<DebugType>): CronetHttpLogger {
            return object : CronetHttpLogger {
                override fun error(vararg obj: Any) {
                    logger.error(*obj)
                }

                override fun info(vararg obj: Any) {
                    logger.info(*obj)
                }

                override fun debug(type: DebugType, vararg obj: Any) {
                    if (types.contains(type)) {
                        logger.info(type, *obj)
                    }
                }
            }
        }

        val Empty = object : CronetHttpLogger {
            override fun error(vararg obj: Any) {}
            override fun info(vararg obj: Any) {}
            override fun debug(type: DebugType, vararg obj: Any) {}
        }
    }
}