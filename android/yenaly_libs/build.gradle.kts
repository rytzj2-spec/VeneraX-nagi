import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Nagisaland suite：Han1meViewer 自带的工具库（Apache-2.0），插件版本统一在 settings.gradle。
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.yenaly.yenaly_libs"
    compileSdk = 36
    resourcePrefix = "yenaly_"

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    buildFeatures {
        //noinspection DataBindingWithoutKapt
        dataBinding = true
        buildConfig = true
    }
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=kotlin.RequiresOptIn",
            "-Xskip-prerelease-check",
            "-opt-in=kotlin.ExperimentalStdlibApi"
        )
    }
}

dependencies {
    implementation(libs.recyclerview)
    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.coroutines.android)

    implementation(libs.navigation.fragment.ktx)
    implementation(libs.lifecycle.livedata.ktx)
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.preference.ktx)
    implementation(libs.startup.runtime)
    implementation(libs.gson)
}
