import java.security.SecureRandom
import java.util.Base64
import java.util.Properties
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

plugins {
    alias(libs.plugins.android.application)
}


// Keep the plaintext key in local.properties or CI environment variables, never in source.
@DisableCachingByDefault(because = "Generated key material must not enter shared build caches")
abstract class GenerateTencentMapKeyTask : DefaultTask() {
    @get:Input
    abstract val apiKey: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val plaintext = apiKey.get().trim()
        require(plaintext.isNotEmpty()) {
            "请在 local.properties 或环境变量中设置 TENCENT_MAP_KEY 后再构建"
        }
        val random = SecureRandom()
        val material = ByteArray(32).also(random::nextBytes)
        val partA = ByteArray(32).also(random::nextBytes)
        val partB = ByteArray(32) { (material[it].toInt() xor partA[it].toInt()).toByte() }
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(material, "AES"), GCMParameterSpec(128, nonce))
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val encoder = Base64.getEncoder()
        val source = """
            package com.wusan.location.tmap;

            // Generated at build time. No plaintext SDK key is written here.
            final class GeneratedTencentMapKey {
                static final String CIPHERTEXT = "${encoder.encodeToString(encrypted)}";
                static final String NONCE = "${encoder.encodeToString(nonce)}";
                static final String PART_A = "${encoder.encodeToString(partA)}";
                static final String PART_B = "${encoder.encodeToString(partB)}";
                private GeneratedTencentMapKey() {}
            }
        """.trimIndent()
        val file = outputDirectory.file("com/wusan/location/tmap/GeneratedTencentMapKey.java").get().asFile
        file.parentFile.mkdirs()
        file.writeText(source, Charsets.UTF_8)
        material.fill(0)
        partA.fill(0)
        partB.fill(0)
    }
}

val localTencentMapKey = providers.fileContents(
    rootProject.layout.projectDirectory.file("local.properties")
).asText.orElse("").map { content ->
    Properties().apply { load(content.reader()) }.getProperty("TENCENT_MAP_KEY", "").trim()
}
val configuredTencentMapKey = providers.environmentVariable("TENCENT_MAP_KEY")
    .orElse("").zip(localTencentMapKey) { environment, local -> environment.trim().ifEmpty { local } }

androidComponents {
    onVariants(selector().all()) { variant ->
        val task = tasks.register<GenerateTencentMapKeyTask>(
            "generate${variant.name.replaceFirstChar { it.uppercaseChar() }}TencentMapKey"
        ) {
            apiKey.set(configuredTencentMapKey)
            outputDirectory.set(layout.buildDirectory.dir("generated/source/tencentMapKey/${variant.name}"))
        }
        variant.sources.java?.addGeneratedSourceDirectory(task, GenerateTencentMapKeyTask::outputDirectory)
    }
}

android {
    namespace = "com.wusan.location"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.wusan.location"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "2.0"
        manifestPlaceholders["BUGLY_APPID"] = "222f9ef298"
        ndk {
            abiFilters.add("arm64-v8a")
            abiFilters.add("x86_64")
        }
    }

    buildTypes {
        debug {
            manifestPlaceholders["APP_VERSION"] = "2.0-debug"
            manifestPlaceholders["BUGLY_ENABLE_DEBUG"] = "true"
        }
        release {
            isMinifyEnabled = false
            manifestPlaceholders["APP_VERSION"] = "2.0"
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
