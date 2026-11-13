package com.pox.colab.engine

import com.pox.colab.model.ColabVM

/**
 * Backwards-compatible generator delegating to [ColabBootstrapGenerator].
 * Google Colab runtimes run standard cloud Ubuntu with NVIDIA GPU drivers, Python 3, and CUDA;
 * Alpine Linux chroot is deprecated and replaced by native Ubuntu bootstrap.
 */
object AlpineBootstrapGenerator {

    /**
     * Generates a bootstrap script by delegating directly to [ColabBootstrapGenerator].
     */
    fun generateBootstrapScript(vm: ColabVM, accountTokenOrCookie: String? = null): String {
        return ColabBootstrapGenerator.generateBootstrapScript(vm, accountTokenOrCookie)
    }
}
