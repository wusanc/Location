package com.wusan.location

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

class LocationXposedModule : XposedModule() {
    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        log(android.util.Log.INFO, "Location", "LSPosed module loaded")
    }
}