plugins {
    id("com.android.application")
}

android {
    namespace = "com.xiyunmn.salthook"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.xiyunmn.salthook"
        minSdk = 27
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        buildConfigField("long", "HOOK_CACHE_REVISION", "${System.currentTimeMillis()}L")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:101.0.0")
    implementation("io.github.proify.lyricon:provider:0.1.70")
    testImplementation("junit:junit:4.13.2")
}
