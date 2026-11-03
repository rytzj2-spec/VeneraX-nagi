@file:Suppress("UnstableApiUsage")

import java.time.Year
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Nagisaland suite：H 分区。Han1meViewer（Mlr111/Han1meViewer @3d9050e，Apache-2.0）原来的 app 模块，
// 改成 library 由 :app 依赖。去掉了 Firebase、buildSrc（版本号）、签名配置，插件版本统一在 settings.gradle。
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.parcelize")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.yenaly.han1meviewer"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("proguard-rules.pro")

        buildConfigField("String", "COMMIT_SHA", "\"3d9050e\"")
        buildConfigField("String", "VERSION_NAME", "\"0.15.5-nagi\"")
        buildConfigField("int", "VERSION_CODE", "1")
        buildConfigField("String", "HA_GITHUB_TOKEN", "\"\"")
        buildConfigField("String", "VERSION_SOURCE", "\"nagi\"")
        buildConfigField("int", "SEARCH_YEAR_RANGE_END", "${Year.now().value}")
        // library 的 BuildConfig 没有 APPLICATION_ID，原代码里只用来拼 FileProvider authority（已改成运行时取包名）
        buildConfigField("String", "APPLICATION_ID", "\"com.yenaly.han1meviewer\"")
        buildConfigField("boolean", "NAGI_SUITE", "true")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    buildFeatures {
        //noinspection DataBindingWithoutKapt
        dataBinding = true
        viewBinding = true
        buildConfig = true
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        disable += setOf("EnsureInitializerMetadata")
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll("-opt-in=kotlin.RequiresOptIn", "-Xjvm-default=all-compatibility")
    }
}

dependencies {
    implementation(libs.androidx.appcompat.v161)
    implementation(libs.androidx.window)
    implementation(libs.androidx.window.java)

    implementation(project(":yenaly_libs"))

    // android related

    implementation(libs.bundles.android.base)
    implementation(libs.bundles.android.jetpack)
    implementation(libs.palette)
    implementation("com.google.android.material:material:1.11.0")

    // datetime

    implementation(libs.datetime)

    // parse

    implementation(libs.serialization.json)
    implementation(libs.jsoup)

    // network

    implementation(libs.retrofit)
    implementation(libs.converter.serialization)

    // pic

    implementation(libs.coil)

    // popup

    implementation(libs.xpopup)
    implementation(libs.xpopup.ext)

    // video

    implementation(libs.jiaozi.video.player)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)

    // view

    implementation(libs.refresh.layout.kernel)
    implementation(libs.refresh.header.material)
    implementation(libs.refresh.footer.classics)
    implementation(libs.multitype)
    implementation(libs.base.recyclerview.adapter.helper4)
    implementation(libs.expandable.textview)
    implementation(libs.spannable.x)
    implementation(libs.about)
    implementation(libs.statelayout)
    implementation(libs.circular.reveal.switch)

    ksp(libs.room.compiler)

    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
