package com.wusan.location

import android.app.Application
import android.content.Context
import com.tencent.bugly.crashreport.CrashReport
import com.tencent.tencentmap.mapsdk.maps.TencentMapInitializer
import com.wusan.location.android.Bugly
import com.wusan.location.ui.settings.AppearanceSettings

class Portal: Application() {

    override fun onCreate() {
        super.onCreate()
        AppearanceSettings.apply(this)

        // 腾讯地图隐私合规：必须在任何地图组件初始化之前调用
        TencentMapInitializer.setAgreePrivacy(true)
        CrashReport.initCrashReport(applicationContext)

        CrashReport.setUserId(applicationContext, Bugly.getUniqueDeviceId(applicationContext))
        CrashReport.setDeviceId(applicationContext, Bugly.getUniqueDeviceId(applicationContext))
        CrashReport.setDeviceModel(applicationContext, Bugly.getDeviceModel())
        CrashReport.setCollectPrivacyInfo(applicationContext, true)

        appContext = applicationContext

        //CrashReport.setAllThreadStackEnable(applicationContext, true, true)
    }

    companion object {

        lateinit var appContext: Context
    }
}