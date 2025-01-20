plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)

   // id("com.android.application")
    id("com.google.gms.google-services")


}

android {
    namespace = "com.ssbycode.bly"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ssbycode.bly"
        minSdk = 31
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

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
    kotlinOptions {
        jvmTarget = "11"
        freeCompilerArgs += listOf(
            "-Xjvm-default=all",
            "-opt-in=kotlin.RequiresOptIn"
        )
    }
    buildFeatures {
        viewBinding = true
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.compose.compiler.get()
    }
}

buildscript {
    repositories {
        maven {
            url = uri("https://maven.google.com")
            url = uri("https://raw.githubusercontent.com/pristineio/webrtc-build-scripts/master/android/repository")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)

//    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.runtime)

    implementation(libs.kotlinx.coroutines.android)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:${libs.versions.coroutines.get()}")

    implementation(libs.androidx.lifecycle.viewmodel.ktx)


    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    debugImplementation(libs.androidx.compose.ui.tooling)
    //Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:${libs.versions.lifecycle.ktx.get()}")
    // WebRTC
    implementation("io.pristine:libjingle:11139@aar")
    //Firebase
    implementation(platform("com.google.firebase:firebase-bom:32.7.1"))


    // Dependências do Firebase necessárias
    implementation("com.google.firebase:firebase-database-ktx")
    implementation("com.google.firebase:firebase-analytics-ktx")
    implementation("com.google.firebase:firebase-analytics")
}