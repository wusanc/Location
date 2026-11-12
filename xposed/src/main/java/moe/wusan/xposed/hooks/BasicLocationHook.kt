package moe.wusan.xposed.hooks

import android.location.Location
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import moe.wusan.xposed.BaseLocationHook
import moe.wusan.xposed.hooks.blindhook.BlindHookLocation
import moe.wusan.xposed.utils.FakeLoc
import moe.wusan.xposed.utils.Logger
import moe.wusan.xposed.utils.hookAllMethodsAfter
import moe.wusan.xposed.utils.hookAllMethodsBefore
import moe.wusan.xposed.utils.toClass
import moe.wusan.xposed.utils.toClassOrThrow

object BasicLocationHook: BaseLocationHook() {
    private val transformingSet = ThreadLocal.withInitial { false }
    operator fun invoke(classLoader: ClassLoader) {
//        val hookSetLatitude = object: XC_MethodHook() {
//            override fun beforeHookedMethod(param: MethodHookParam?) {
//                if (param == null) return
//                if (!FakeLocationConfig.enable) return
//
//            }
//        }
//        val hookSetLongitude = object: XC_MethodHook() {
//            override fun beforeHookedMethod(param: MethodHookParam?) {
//                if (param == null) return
//
//
//            }
//        }
//        XposedHelpers.findAndHookMethod("android.location.Location", classLoader, "setLatitude", Double::class.java, hookSetLatitude)
//        XposedHelpers.findAndHookMethod("android.location.Location", classLoader, "setLongitude", Double::class.java, hookSetLongitude)

        kotlin.runCatching {
            val cLocationResult = "android.location.LocationResult".toClassOrThrow(classLoader)
            BlindHookLocation(cLocationResult, classLoader)

            cLocationResult.hookAllMethodsAfter("asList") {
                if (!FakeLoc.enable) return@hookAllMethodsAfter

                val locations = result as? List<*> ?: return@hookAllMethodsAfter
                runCatching {
                    require(locations.all { it is Location }) { "Invalid LocationResult batch" }
                    result = injectLocations(locations.filterIsInstance<Location>())
                }.onFailure { Logger.warn("Failed to transform LocationResult.asList", it) }
            }

            cLocationResult.hookAllMethodsBefore("writeToParcel") {
                if (!FakeLoc.enable) return@hookAllMethodsBefore

                runCatching {
                    val locationResult = thisObject
                    val field = XposedHelpers.findFieldIfExists(locationResult.javaClass, "mLocations")
                        ?: throw IllegalStateException("LocationResult.mLocations not found")
                    field.isAccessible = true
                    val locations = field.get(locationResult) as? List<*>
                        ?: throw IllegalStateException("LocationResult.mLocations is not a List")
                    require(locations.all { it is Location }) { "Invalid LocationResult batch" }
                    field.set(locationResult, ArrayList(injectLocations(locations.filterIsInstance<Location>(), realLocation = false)))
                }.onFailure { Logger.warn("Failed to transform LocationResult.writeToParcel", it) }
            }
        }.onFailure {
           Logger.error("Failed to hook LocationResult", it)
        }

        Location::class.java.hookAllMethodsBefore("set") {
            if (!FakeLoc.enable || transformingSet.get() == true) return@hookAllMethodsBefore
            val location = args.firstOrNull() as? Location ?: return@hookAllMethodsBefore
            transformingSet.set(true)
            try {
                runCatching { args[0] = injectLocation(location) }
                    .onFailure { Logger.warn("Failed to transform Location.set", it) }
            } finally {
                transformingSet.remove()
            }
        }
//        if (FakeLocationConfig.DEBUG) {
//            // Track the invocation of AutoNavi map system services
//            val cBundle = XposedHelpers.findClass("android.os.Bundle", classLoader)
//            XposedBridge.hookAllMethods(cBundle, "putInt", object : XC_MethodHook() {
//                override fun beforeHookedMethod(param: MethodHookParam) {
//                    val key = param.args[0] as? String
//                    val value = param.args[1] as Int
//
//                    if (key == "amap" || key == "resubtype" || key == "maxCn0") {
//                        XposedBridge.log(RuntimeException())
//                    }
//                }
//            })
//            XposedBridge.hookAllMethods(Location::class.java, "setExtras", object : XC_MethodHook() {
//                override fun beforeHookedMethod(param: MethodHookParam) {
//                    XposedBridge.log(RuntimeException())
//                }
//            })
//        }


    }
}