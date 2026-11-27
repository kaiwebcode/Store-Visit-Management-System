plugins {
    alias(libs.plugins.android.application)
}

val day17BackendHost = providers.gradleProperty("day17BackendHost").orElse("192.168.1.111")
require(day17BackendHost.get().matches(Regex("[A-Za-z0-9.-]+"))) { "Invalid Day 17 backend host" }

android {
    namespace = "com.example.storevisitmanagementsystem"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.storevisitmanagementsystem"
        minSdk = 24
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
        manifestPlaceholders["appName"] = "Store Visit Management System"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        javaCompileOptions {
            annotationProcessorOptions {
                arguments += mapOf("room.schemaLocation" to "$projectDir/schemas")
            }
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    sourceSets.getByName("androidTest").assets.directories.add("schemas")

    buildTypes {
        debug {
            applicationIdSuffix = ".day18"
            manifestPlaceholders["appName"] = "StoreVisit Day18"
            buildConfigField("String", "DAY17_BACKEND_HOST", "\"${day17BackendHost.get()}\"")
        }
        release {
            manifestPlaceholders["appName"] = "Store Visit Management System"
            buildConfigField("String", "DAY17_BACKEND_HOST", "\"\"")
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.cardview)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.metadata.jvm)
    annotationProcessor(libs.androidx.room.compiler)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)
    implementation(libs.gson)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
