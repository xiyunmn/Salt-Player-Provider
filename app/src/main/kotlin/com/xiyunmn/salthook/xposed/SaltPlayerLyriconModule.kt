package com.xiyunmn.salthook.xposed

import android.util.Log
import com.xiyunmn.salthook.diagnostics.SaltDiagnostics
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam

class SaltPlayerLyriconModule : XposedModule() {
    override fun onModuleLoaded(param: ModuleLoadedParam) {
        SaltDiagnostics.setXposed(this)
        log(Log.INFO, TAG, "Module loaded in process " + param.processName)
        SaltDiagnostics.log("module", "loaded process=" + param.processName)
    }

    override fun onPackageReady(param: PackageReadyParam) {
        if (SaltPlayerHooks.SALT_PLAYER_PACKAGE != param.packageName) {
            return
        }
        if (!param.isFirstPackage) {
            return
        }

        try {
            val start = SaltDiagnostics.now()
            SaltPlayerHooks(this, param.classLoader).install()
            SaltDiagnostics.log("module", "hooks installed in " + SaltDiagnostics.elapsedMs(start) + "ms")
        } catch (throwable: Throwable) {
            log(Log.ERROR, TAG, "Failed to install SaltPlayer hooks", throwable)
            SaltDiagnostics.warn("module", "failed to install hooks", throwable)
        }
    }

    private companion object {
        const val TAG = "SaltLyricon"
    }
}
