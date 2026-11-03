@file:Suppress("UnstableApiUsage")

import java.time.Year

// Nagisaland suite：Han1meViewer（Mlr111/Han1meViewer，Apache-2.0，基于 commit 3d9050e）作为「H」分区，
// 从 application 改成 library 模块编进 suite；去掉了 Firebase，版本号写死，自带的检查更新关掉。
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
    }
    buildTypes {
        release { isMinifyEnabled = false }
    }
    buildFeatures {
        //noinspection DataBindingWithoutKapt
        dataBinding = true
        buildConfig = true
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        disable += setOf("EnsureInitializerMetadata")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.addAll("-opt-in=kotlin.RequiresOptIn", "-jvm-default=enable")
    }
}

dependencies {
    implementation(libs.androidx.appcompat.v161)
    implementation(libs.androidx.window)
    implementation(libs.androidx.window.java)

    implementation(project(":yenaly_libs"))

    implementation(libs.bundles.android.base)
    implementation(libs.bundles.android.jetpack)
    implementation(libs.palette)
    implementation("com.google.android.material:material:1.11.0")

    implementation(libs.datetime)
    implementation(libs.serialization.json)
    implementation(libs.jsoup)
    implementation(libs.retrofit)
    implementation(libs.converter.serialization)
    implementation(libs.coil)
    implementation(libs.xpopup)
    implementation(libs.xpopup.ext)
    implementation(libs.jiaozi.video.player)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
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
