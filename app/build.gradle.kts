import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) load(f.inputStream())
}

android {
    namespace = "org.WenuLink"
    compileSdk = 35
    useLibrary("org.apache.http.legacy")

    defaultConfig {
        applicationId = "org.WenuLink"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

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

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.runtime.livedata)
    implementation(libs.androidx.foundation)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
    // DJI
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.multidex)
    implementation(libs.dji.sdk) {
        /**
         * Uncomment the "library-anti-distortion" if your app does not need Anti Distortion for Mavic 2 Pro and Mavic 2 Zoom.
         * Uncomment the "fly-safe-database" if you need database for release, or we will download it when DJISDKManager.getInstance().registerApp
         * is called.
         * Both will greatly reduce the size of the APK.
         */
        exclude(module = "library-anti-distortion")
        exclude(module = "fly-safe-database")
    }
    compileOnly(libs.dji.sdk.provided)
    //implementation("com.dji:dji-uxsdk:4.18")
    // WebRTC and WebSocket
    implementation(libs.stream.webrtc.android)
    implementation(libs.okhttp)
    // Log
    implementation(libs.stream.log)
}
