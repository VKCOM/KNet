package com.vk.knet.cornet

import android.content.Context
import com.vk.knet.cornet.utils.CronetLogger
import org.chromium.net.CronetEngine
import org.chromium.net.ExperimentalCronetEngine
import org.chromium.net.ICronetEngineBuilder
import org.chromium.net.impl.NativeCronetEngineBuilderWithLibraryLoaderImpl
import org.chromium.net.impl.NativeCronetProvider

@Suppress("unused")
class KnetCronetProvider(context: Context) : NativeCronetProvider(context) {

    init {
        CronetLogger.debug(CronetHttpLogger.DebugType.CLIENT_BUILDER, "Initialization KnetCronetProvider")
    }

    override fun createBuilder(): CronetEngine.Builder {
        CronetLogger.debug(CronetHttpLogger.DebugType.CLIENT_BUILDER, "Create engine builder by KnetCronetProvider")
        val impl: ICronetEngineBuilder = NativeCronetEngineBuilderWithLibraryLoaderImpl(mContext)
        return ExperimentalCronetEngine.Builder(impl)
    }

    override fun getName(): String {
        return "Knet-App-Packaged-Cronet-Provider"
    }

    override fun isEnabled(): Boolean {
        return true
    }

    override fun hashCode(): Int {
        return arrayOf(KnetCronetProvider::class.java, mContext).contentHashCode()
    }

    override fun equals(other: Any?): Boolean {
        return other === this || other is KnetCronetProvider && mContext == other.mContext
    }
}