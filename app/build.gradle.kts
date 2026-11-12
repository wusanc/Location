plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.wusan.location"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.wusan.location"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        manifestPlaceholders["BUGLY_APPID"] = "222f9ef298"
        ndk {
            abiFilters.add("arm64-v8a")
            abiFilters.add("x86_64")
        }
    }

    buildTypes {
        debug {
            manifestPlaceholders["APP_VERSION"] = "1.0-debug"
            manifestPlaceholders["BUGLY_ENABLE_DEBUG"] = "true"
        }
        release {
            isMinifyEnabled = false
            manifestPlaceholders["APP_VERSION"] = "1.0"
            manifestPlaceholders["BUGLY_ENABLE_DEBUG"] = "false"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            // 多个 SDK 都带 libc++_shared.so，打包时取第一个即可
            pickFirsts += "lib/*/libc++_shared.so"
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/NOTICE*"
            excludes += "/META-INF/LICENSE*"
            excludes += "/META-INF/DEPENDENCIES*"
            excludes += "/META-INF/*.kotlin_module"
            excludes += "/META-INF/INDEX.LIST"
        }
    }
    sourceSets {
        getByName("main").jniLibs.directories.add("libs")
    }
}

dependencies {
    testImplementation(libs.junit)
    implementation(project(":xposed"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.lifecycle.livedata.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)

    implementation(libs.okhttp)
    implementation(libs.fastjson)
    implementation(libs.kotlin.reflect)
    implementation(libs.bugly)
    implementation(libs.geotools)

    // 腾讯地图 SDK（地图显示）
    // 注意：6.x 需要额外的 com.tencent.openmap:foundation，而它内置了 Bugly，
    // 会和本项目自己的 com.tencent.bugly:crashreport 产生重复类，故使用 5.x 版本线。
    implementation(libs.tencent.map.vector)
    implementation(libs.tencent.map.utilities)

    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar"))))
}
