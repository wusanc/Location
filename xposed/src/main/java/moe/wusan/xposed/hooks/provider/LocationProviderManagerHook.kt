package moe.wusan.xposed.hooks.provider

import android.location.Location
import android.os.Build
import android.telephony.CellIdentity
import android.telephony.CellInfo
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import moe.wusan.xposed.hooks.BasicLocationHook.injectLocation
import moe.wusan.xposed.hooks.BasicLocationHook.injectLocations
import moe.wusan.xposed.hooks.blindhook.BlindHookLocation
import moe.wusan.xposed.utils.FakeLoc
import moe.wusan.xposed.utils.Logger
import moe.wusan.xposed.utils.beforeHook
import moe.wusan.xposed.utils.diyHook
import moe.wusan.xposed.utils.hook
import moe.wusan.xposed.utils.onceHook
import moe.wusan.xposed.utils.onceHookAllMethod
import moe.wusan.xposed.utils.onceHookMethodBefore
import java.util.Collections

object LocationProviderManagerHook {
    private val hookOnFetchLocationResult = beforeHook {
        if (!FakeLoc.enable) return@beforeHook
        val locationResult = args.firstOrNull() ?: return@beforeHook
        replaceLocationResult(locationResult)
    }

    private fun replaceLocationResult(locationResult: Any) {
        runCatching {
            val field = XposedHelpers.findFieldIfExists(locationResult.javaClass, "mLocations")
                ?: throw IllegalStateException("LocationResult.mLocations not found")
            field.isAccessible = true
            val locations = field.get(locationResult) as? List<*>
                ?: throw IllegalStateException("LocationResult.mLocations is not a List")
            require(locations.all { it is Location }) { "Invalid location batch" }
            // Preserve every sample. The system retains ownership of registrations and delivery.
            field.set(locationResult, ArrayList(injectLocations(locations.filterIsInstance<Location>(), realLocation = false)))
        }.onFailure {
            Logger.warn("Failed to transform provider location batch", it)
        }
    }

    operator fun invoke(classLoader: ClassLoader) {
        hookLocationProviderManager(classLoader)
        hookDelegateLocationProvider(classLoader)
        hookPassiveLocationProvider(classLoader)
        hookProxyLocationProvider(classLoader)
        hookAbstractLocationProvider(classLoader)
        hookOtherProvider(classLoader)
        hookGeofenceProvider(classLoader)
    }

    private fun hookAbstractLocationProvider(classLoader: ClassLoader) {
        run {
            val cAbstractLocationProvider = XposedHelpers.findClassIfExists("com.android.server.location.provider.AbstractLocationProvider", classLoader)
                ?: return@run
            val cLocationResult = XposedHelpers.findClassIfExists("android.location.LocationResult", classLoader)
                ?: return@run
            val mReportLocation = XposedHelpers.findMethodExactIfExists(cAbstractLocationProvider, "reportLocation", cLocationResult)
                ?: return@run

            mReportLocation.onceHook(hookOnFetchLocationResult)
        }

        run {
            val cInternalState = XposedHelpers.findClassIfExists("com.android.server.location.provider.AbstractLocationProvider\$InternalState", classLoader)
                ?: return@run

            XposedBridge.hookAllConstructors(cInternalState, object: XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    val listener = param.args[0] ?: return

                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("AbstractLocationProvider.InternalState: injected!")
                    }

                    // will hook class AbstractLocationProvider.Listener, Be careful not to repeat the hooker!
                    listener.javaClass.onceHookAllMethod("onReportLocation", hookOnFetchLocationResult)
                }
            })
        }

    }

    private fun hookProxyLocationProvider(classLoader: ClassLoader) {
        val cProxyLocationProvider = XposedHelpers.findClassIfExists("com.android.server.location.provider.proxy.ProxyLocationProvider", classLoader)
            ?: return


    }

    private fun hookPassiveLocationProvider(classLoader: ClassLoader) {
        val cPassiveLocationProvider = XposedHelpers.findClassIfExists("com.android.server.location.provider.PassiveLocationProvider", classLoader)
            ?: return
        val cLocationResult = XposedHelpers.findClassIfExists("android.location.LocationResult", classLoader)
            ?: return
        val updateLocation = XposedHelpers.findMethodExactIfExists(cPassiveLocationProvider, "updateLocation", cLocationResult)
            ?: return

        updateLocation.hook(hookOnFetchLocationResult)
    }

    private fun hookDelegateLocationProvider(classLoader: ClassLoader) {
        val cDelegateLocationProvider = XposedHelpers.findClassIfExists("com.android.server.location.provider.DelegateLocationProvider", classLoader)
            ?: return

        val waitForInitialization = XposedHelpers.findMethodExactIfExists(cDelegateLocationProvider, "waitForInitialization") ?: return
        waitForInitialization.diyHook(
            hookOnce = true,
            before = {
                if (FakeLoc.enableDebugLog) {
                    Logger.debug("DelegateLocationProvider.waitForInitialization: injected!")
                }

                val cLocationResult = XposedHelpers.findClassIfExists("android.location.LocationResult", classLoader)
                    ?: return@diyHook true
                XposedHelpers.findMethodExactIfExists(thisObject.javaClass, "onReportLocation", cLocationResult)?.onceHook(hookOnFetchLocationResult)
                XposedHelpers.findMethodExactIfExists(thisObject.javaClass, "reportLocation", cLocationResult)?.onceHook(hookOnFetchLocationResult)

                return@diyHook true
            }
        )
    }

    private fun hookLocationProviderManager(classLoader: ClassLoader) {
        val cLocationProviderManager = XposedHelpers.findClassIfExists("com.android.server.location.provider.LocationProviderManager", classLoader)
            ?: return
        BlindHookLocation(cLocationProviderManager, classLoader)

        XposedBridge.hookAllMethods(cLocationProviderManager, "setRealProvider", object: XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val locationProvider = param.args[0]
                if (FakeLoc.enableDebugLog) {
                    Logger.debug("setRealProvider: $locationProvider")
                }
            }
        })
        XposedBridge.hookAllMethods(cLocationProviderManager, "setMockProvider", object: XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                val locationProvider = param.args[0]
                if (FakeLoc.enableDebugLog) {
                    Logger.debug("setMockProvider: $locationProvider")
                }
            }
        })
        XposedBridge.hookAllMethods(cLocationProviderManager, "sendExtraCommand", object: XC_MethodHook() {
            override fun beforeHookedMethod(param: MethodHookParam) {
                if(param.args.size < 4) return
                val command = param.args[2]

                if (command == "force_xtra_injection" || command == "CMD_SHOW_GPS_TIPS_CONFIG") {
                    param.result = null
                    return
                }

                val extras = param.args[3]
                if (FakeLoc.enableDebugLog) {
                    Logger.debug("sendExtraCommand: $command, $extras")
                }
            }
        })

        run {
            val hookedListeners = Collections.synchronizedSet(HashSet<String>())
            if(cLocationProviderManager.onceHookAllMethod("getCurrentLocation", object: XC_MethodHook() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    if (param.args.size < 4 || param.args[3] == null) return

                    val callback = param.args[3]
                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("getCurrentLocation injected: $callback")
                    }

                    if(FakeLoc.disableGetCurrentLocation) {
                        param.result = null
                        return
                    }

                    val classCallback = callback.javaClass
                    if (hookedListeners.contains(classCallback.name)) return // Prevent repeated hooking
                    if (XposedBridge.hookAllMethods(classCallback, "onLocation", object: XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam?) {
                            if (param == null || param.args.isEmpty()) return
                            val location = param.args[0] as? Location ?: return

                            if (FakeLoc.enableDebugLog)
                                Logger.debug("onLocation(LocationProviderManager.getCurrentLocation): injected!")
                            runCatching { param.args[0] = injectLocation(location) }
                                .onFailure { Logger.warn("Failed to transform current location", it) }
                        }
                    }).isEmpty()) {
                        Logger.error("hook onLocation(LocationProviderManager.getCurrentLocation) failed")
                    }

                    hookedListeners.add(classCallback.name)
                }
            }).isEmpty()) {
                Logger.error("hook LocationProviderManager.getCurrentLocation failed")
            }
        }

        cLocationProviderManager.onceHookAllMethod("onReportLocation", hookOnFetchLocationResult)

    }

    private fun hookGeofenceProvider(classLoader: ClassLoader) {
        val cGeofenceManager = XposedHelpers.findClassIfExists("com.android.server.geofence.GeofenceManager", classLoader)
            ?: return
        BlindHookLocation(cGeofenceManager, classLoader)
    }

    private fun hookOtherProvider(classLoader: ClassLoader) {
        kotlin.runCatching {
            val cGnssLocationProvider = XposedHelpers.findClassIfExists("com.android.location.provider.LocationProviderBase", classLoader)
                ?: return@runCatching
            if(BlindHookLocation(cGnssLocationProvider, classLoader) == 0) {
                cGnssLocationProvider.onceHookMethodBefore("reportLocation", Location::class.java) {
                    if (!FakeLoc.enable) return@onceHookMethodBefore
                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("LocationProviderBase.reportLocation: injected!")
                    }
                    val location = args.firstOrNull() as? Location ?: return@onceHookMethodBefore
                    runCatching { args[0] = injectLocation(location) }
                        .onFailure { Logger.warn("Failed to transform provider location", it) }
                }
            }
            cGnssLocationProvider.onceHookMethodBefore("reportLocations", List::class.java) {
                if (!FakeLoc.enable) return@onceHookMethodBefore
                if (FakeLoc.enableDebugLog) {
                    Logger.debug("LocationProviderBase.reportLocations: injected!")
                }
                val locations = args.firstOrNull() as? List<*> ?: return@onceHookMethodBefore
                runCatching {
                    require(locations.all { it is Location }) { "Invalid provider location batch" }
                    args[0] = injectLocations(locations.filterIsInstance<Location>())
                }.onFailure { Logger.warn("Failed to transform provider locations", it) }
            }
        }.onFailure {
            Logger.warn("Failed to hook LocationProviderBase", it)
        }

        kotlin.runCatching {
            val cGnssLocationProvider = XposedHelpers.findClass("com.android.server.location.gnss.GnssLocationProvider", classLoader)
            cGnssLocationProvider.onceHookMethodBefore("onReportLocation", Boolean::class.java, Location::class.java) {
                if (!FakeLoc.enable) return@onceHookMethodBefore

                val location = args.getOrNull(1) as? Location ?: return@onceHookMethodBefore
                runCatching { args[1] = injectLocation(location) }
                    .onFailure { Logger.warn("Failed to transform GNSS location", it) }

                if (FakeLoc.enableDebugLog) {
                    Logger.debug("GnssLocationProvider.onReportLocation: injected! ${args[1]}")
                }
            }

            cGnssLocationProvider.onceHookMethodBefore("onReportLocations", Boolean::class.java, Array<Location>::class.java) {
                if (!FakeLoc.enable) return@onceHookMethodBefore

                // The first parameter is hasLatLong; the locations are the second parameter.
                val locations = args.getOrNull(1) as? Array<*> ?: return@onceHookMethodBefore
                runCatching {
                    require(locations.all { it is Location }) { "Invalid GNSS location batch" }
                    args[1] = injectLocations(locations.filterIsInstance<Location>()).toTypedArray()
                }.onFailure { Logger.warn("Failed to transform GNSS locations", it) }

                if (FakeLoc.enableDebugLog) {
                    Logger.debug("GnssLocationProvider.onReportLocations: injected! ${args[1]}")
                }
            }

            cGnssLocationProvider.onceHookMethodBefore("getCellType", CellInfo::class.java) {
                if (!FakeLoc.enable) return@onceHookMethodBefore
                if (FakeLoc.enableDebugLog) {
                    Logger.debug("GnssLocationProvider.getCellType: injected!")
                }

                result = 0
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                cGnssLocationProvider.onceHookMethodBefore("getCidFromCellIdentity", CellIdentity::class.java) {
                    if (!FakeLoc.enable) return@onceHookMethodBefore
                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("GnssLocationProvider.getCidFromCellIdentity: injected!")
                    }

                    result = -1L
                }

                cGnssLocationProvider.onceHookMethodBefore("setRefLocation", Int::class.java, CellIdentity::class.java) {
                    if (!FakeLoc.enable) return@onceHookMethodBefore
                    if (FakeLoc.enableDebugLog) {
                        Logger.debug("GnssLocationProvider.setRefLocation: injected!")
                    }

                    args[0] = 114514 // disable AGPS
                }
            }
        }.onFailure {
            Logger.warn("Failed to hook GnssLocationProvider", it)
        }
    }
}