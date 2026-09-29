buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        // AGP provides Kotlin support; explicitly select the current stable compiler.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

plugins {
    id("com.android.application") version "9.4.1" apply false
}
