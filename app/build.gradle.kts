import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ktlint)
}

val localProps =
    Properties().apply {
        val f = rootProject.file("local.properties")
        if (f.exists()) load(f.inputStream())
    }

ktlint {
    version.set(libs.versions.ktlintEngine)
    android.set(true)
    outputToConsole.set(true)
    ignoreFailures.set(false)

    reporters {
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
    }

    filter {
        exclude("**/com/MAVLink/**")
    }
}

android {
    namespace = "org.WenuLink"
    compileSdk = 36
    useLibrary("org.apache.http.legacy")

    defaultConfig {
        applicationId = "org.WenuLink"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0-alpha"

        manifestPlaceholders["djiApiKey"] = localProps.getProperty("dji.api.key", "")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        multiDexEnabled = true

        ndk {
            // DJI SDK no longer supports x86
            abiFilters.addAll(listOf("armeabi-v7a", "arm64-v8a"))
        }
    }

    packaging {
        jniLibs.pickFirsts.addAll(listOf("META-INF/LICENSE.txt"))
        jniLibs.excludes.addAll(listOf("META-INF/rxjava.properties"))

        // Keep debug symbols for specific libraries
        jniLibs.keepDebugSymbols.addAll(
            listOf(
                "*/*/libdjivideo.so",
                "*/*/libSDKRelativeJNI.so",
                "*/*/libFlyForbid.so",
                "*/*/libduml_vision_bokeh.so",
                "*/*/libyuv2.so",
                "*/*/libGroudStation.so",
                "*/*/libFRCorkscrew.so",
                "*/*/libUpgradeVerify.so",
                "*/*/libFR.so",
                "*/*/libDJIFlySafeCore.so",
                "*/*/libdjifs_jni.so",
                "*/*/libsfjni.so",
                "*/*/libDJICommonJNI.so",
                "*/*/libDJICSDKCommon.so",
                "*/*/libDJIUpgradeCore.so",
                "*/*/libDJIUpgradeJNI.so",
                "*/*/libDJIWaypointV2Core.so",
                "*/*/libAMapSDK_MAP_v6_9_2.so",
                "*/*/libDJIMOP.so",
                "*/*/libDJISDKLOGJNI.so"
            )
        )
    }

    signingConfigs {
        create("release") {
            val keystorePath = localProps.getProperty("release.keystore.path")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = localProps.getProperty("release.keystore.password")
                keyAlias = localProps.getProperty("release.key.alias")
                keyPassword = localProps.getProperty("release.key.password")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile != null) {
                signingConfig = releaseSigning
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    implementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)

    // --- Testing ---
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)

    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // --- AndroidX ---
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.multidex)

    // --- DJI ---
    implementation(libs.dji.sdk) {
        // Uncomment the "library-anti-distortion" if your app does not need Anti Distortion for Mavic 2 Pro and Mavic 2 Zoom.
        // Uncomment the "fly-safe-database" if you need database for release, or we will download it when DJISDKManager.getInstance().registerApp
        // is called.
        // Both will greatly reduce the size of the APK.
        exclude(module = "library-anti-distortion")
        exclude(module = "fly-safe-database")
    }
    compileOnly(libs.dji.sdk.provided)
    // implementation("com.dji:dji-uxsdk:4.18")

    // --- Networking ---
    implementation(libs.okhttp)
    implementation(libs.stream.log)
    implementation(libs.stream.webrtc.android)

    // ---UI Navigation ---
    implementation(libs.androidx.navigation.compose)

    // --- Icons ---
    implementation(libs.androidx.compose.material.icons.extended)
}
