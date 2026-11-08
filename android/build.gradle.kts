// Top-level build file where you can add configuration options common to all sub-projects/modules.

// Google Services plugin only on the classpath when app/google-services.json exists, so a
// checkout without Firebase builds offline (see appCheckEnabled in app/build.gradle.kts).
buildscript {
    if (file("app/google-services.json").exists()) {
        repositories { google() }
        dependencies { classpath("com.google.gms:google-services:4.4.4") }
    }
}

plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.3.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
    id("com.google.devtools.ksp") version "2.3.6" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
