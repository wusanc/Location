pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        maven { url = uri("https://maven.aliyun.com/nexus/content/repositories/google") }
        maven { url = uri("https://maven.aliyun.com/nexus/content/groups/public") }
        google()
        mavenCentral()
        maven { url = uri("https://api.xposed.info/") }
        maven { url = uri("https://jitpack.io") }
        // 腾讯地图 SDK 发布在 sonatype 公共仓库
        maven { url = uri("https://oss.sonatype.org/content/groups/public") }
    }
}
rootProject.name = "Location"
include(":app")
include(":xposed")
include(":system-api")
include(":nmea")