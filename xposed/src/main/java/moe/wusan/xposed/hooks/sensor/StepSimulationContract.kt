package moe.wusan.xposed.hooks.sensor

/** Only these two non-personal settings are exposed to scoped client processes. */
object StepSimulationContract {
    const val AUTHORITY = "com.wusan.location.stepconfig"
    const val URI = "content://$AUTHORITY/config"
    const val MODULE_PACKAGE = "com.wusan.location"
    const val ACCESS_RECEIVER = "com.wusan.location.service.StepConfigAccessReceiver"
    const val PACKAGE_EXTRA = "requesting_package"
    const val ENABLED = "enabled"
    const val CADENCE = "cadence"
    const val DEFAULT_CADENCE = 160
    const val MIN_CADENCE = 1
    const val MAX_CADENCE = 300
}
