import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
}

android {
    namespace = "com.mikes.sitelimiter"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.mikes.sitelimiter"
        minSdk = 26
        targetSdk = 34
        val releaseVersion = providers.gradleProperty("releaseVersion").orNull
        if (releaseVersion == null) {
            versionCode = 1
            versionName = "1.0"
        } else {
            val match = Regex("v(0|[1-9][0-9]{0,3})\\.(0|[1-9][0-9]{0,2})\\.(0|[1-9][0-9]{0,2})").matchEntire(releaseVersion)
                ?: error("releaseVersion must be a vMAJOR.MINOR.PATCH tag")
            val (major, minor, patch) = match.destructured
            val code = major.toLong() * 1_000_000 + minor.toLong() * 1_000 + patch.toLong()
            require(code in 2..2_100_000_000L) { "Release versionCode must be between 2 and 2100000000" }
            versionCode = code.toInt()
            versionName = releaseVersion.removePrefix("v")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isDebuggable = false
            // Sign the unsigned release APK separately; private keys never enter Gradle.
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    implementation("androidx.core:core:1.19.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
}
