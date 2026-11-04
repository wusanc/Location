package moe.wusan.xposed.hooks.oplus

import android.location.LocationListener
import android.os.Bundle
import de.robv.android.xposed.XposedHelpers
import moe.wusan.xposed.BaseLocationHook
import moe.wusan.xposed.hooks.blindhook.BlindHookLocation
import moe.wusan.xposed.hooks.blindhook.BlindHookLocation.invoke
import moe.wusan.xposed.hooks.fused.ThirdPartyLocationHook
import moe.wusan.xposed.utils.FakeLoc
import moe.wusan.xposed.utils.Logger
import moe.wusan.xposed.utils.hookMethodAfter
import moe.wusan.xposed.utils.onceHookMethodBefore
import moe.wusan.xposed.utils.toClass
import java.lang.reflect.Modifier

object OplusLocationHook: BaseLocationHook() {
    operator fun invoke(classLoader: ClassLoader) {
        ThirdPartyLocationHook(classLoader)
    }
}