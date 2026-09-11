import java.util.Locale

plugins {
    alias(libs.plugins.android.application)
    id("com.google.gms.google-services")
}

android {
    namespace = "net.harbroi.hikaraoke"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "net.harbroi.hikaraoke"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.1"
        buildConfigField("String", "YOUTUBE_API_KEY", "\"AIzaSyDOuyf7Xt1r5Csck5cCn7-VEiBw8fcbdmI\"")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    buildFeatures {
        buildConfig = true
    }
}

androidComponents {
    onVariants { variant ->
        val versionName = variant.outputs.single().versionName.orNull ?: "0.0"
        variant.outputs.forEach { output ->
            output.outputFileName.set(
                String.format(
                    Locale.US,
                    "HIKaraoke_v%s.apk",
                    versionName
                )
            )
        }
    }
}

dependencies {
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.youtube.player.core)
    implementation("com.squareup.picasso:picasso:2.8")
    implementation(platform("com.google.firebase:firebase-bom:34.4.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-database")
    implementation("com.google.android.gms:play-services-auth:21.2.0")
    testImplementation(libs.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}