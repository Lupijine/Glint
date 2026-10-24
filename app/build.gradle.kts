plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
}

val abiOverride = providers.gradleProperty("glint.abi").orNull?.split(",")?.map { it.trim() }

android {
    namespace = "app.glint"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "io.github.lupijine.glint"
        minSdk = 24
        targetSdk = 35
        versionCode = 13
        versionName = "0.9.2"
        testInstrumentationRunner = "app.glint.ui.theme.GlassTestRunner"
        // -Pglint.abi=x86_64 builds a single APK for one processor, such as an emulator's.
        abiOverride?.let { ndk { abiFilters += it } }
    }

    // One APK per processor type keeps each download small: the yt-dlp engine ships Python and
    // ffmpeg as native libraries. The universal APK holds all of them, for when in doubt.
    splits {
        abi {
            isEnable = abiOverride == null
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    // A fixed key lets each new build install over the last one. CI reads it from repository
    // secrets; without one, builds fall back to the debug key, which CI creates fresh every run.
    val stableKeystore =
        providers.environmentVariable("GLINT_KEYSTORE_PATH").orNull?.let(::file)?.takeIf { it.isFile }
    signingConfigs {
        if (stableKeystore != null) {
            create("stable") {
                storeFile = stableKeystore
                storePassword = providers.environmentVariable("GLINT_KEYSTORE_PASSWORD").orNull
                // The key was created when the app was called Xlight; its alias inside the keystore stays.
                keyAlias = providers.environmentVariable("GLINT_KEY_ALIAS").orNull ?: "xlight"
                keyPassword =
                    providers.environmentVariable("GLINT_KEY_PASSWORD").orNull
                        ?: providers.environmentVariable("GLINT_KEYSTORE_PASSWORD").orNull
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            signingConfig = signingConfigs.findByName("stable") ?: signingConfigs.getByName("debug")
            // Branch test builds pass -Pglint.versionSuffix=-preview so they are easy to tell apart.
            versionNameSuffix = providers.gradleProperty("glint.versionSuffix").orNull
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures { compose = true }

    // A new string can land before every language has it; Android shows English until then.
    lint { warning += "MissingTranslation" }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        // The yt-dlp library unpacks Python from its native libraries at run time.
        jniLibs.useLegacyPackaging = true
        // 32-bit Intel Android devices are all but gone; leaving them out saves the universal APK
        // about 28 MB.
        jniLibs.excludes += "lib/x86/**"
    }

    kotlinOptions { freeCompilerArgs = freeCompilerArgs + "-opt-in=kotlin.RequiresOptIn" }
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtimeCompose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.animation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.youtubedl.android.library)
    implementation(libs.youtubedl.android.ffmpeg)
    implementation(libs.coil.compose)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

