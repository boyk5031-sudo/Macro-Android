package com.macroandroid.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Project
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinBaseExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension

/** Shared Android + Kotlin configuration for every Android module. */
internal fun Project.configureKotlinAndroid(commonExtension: CommonExtension) {
    commonExtension.apply {
        compileSdk = Sdk.COMPILE

        defaultConfig {
            minSdk = Sdk.MIN
            testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }

        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_17
            targetCompatibility = JavaVersion.VERSION_17
        }

        testOptions {
            animationsDisabled = true
            unitTests {
                isIncludeAndroidResources = true
                isReturnDefaultValues = false
            }
        }

        lint {
            abortOnError = true
            warningsAsErrors = true
            checkReleaseBuilds = true
            lintConfig = rootProject.file("config/lint/lint.xml")
            xmlReport = false
            htmlReport = true
            sarifReport = true
            file("lint-baseline.xml").takeIf { it.exists() }?.let { baseline = it }
        }
    }

    configureKotlin<KotlinAndroidProjectExtension>()

    dependencies {
        "testImplementation"(libs.findLibrary("junit4").get())
        "testImplementation"(libs.findLibrary("kotlinx-coroutines-test").get())
        "testImplementation"(libs.findLibrary("truth").get())
        "testImplementation"(libs.findLibrary("turbine").get())
        "androidTestImplementation"(libs.findLibrary("androidx-test-ext-junit").get())
        "androidTestImplementation"(libs.findLibrary("androidx-test-runner").get())
        "androidTestImplementation"(libs.findLibrary("androidx-test-rules").get())
        "androidTestImplementation"(libs.findLibrary("kotlinx-coroutines-test").get())
        "androidTestImplementation"(libs.findLibrary("truth").get())
    }
}

/** Shared configuration for pure-JVM Kotlin modules (automation:engine, core:common). */
internal fun Project.configureKotlinJvm() {
    extensions.configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    configureKotlin<KotlinJvmProjectExtension>()

    dependencies {
        "testImplementation"(libs.findLibrary("junit4").get())
        "testImplementation"(libs.findLibrary("kotlinx-coroutines-test").get())
        "testImplementation"(libs.findLibrary("truth").get())
        "testImplementation"(libs.findLibrary("turbine").get())
    }
}

private inline fun <reified T : KotlinBaseExtension> Project.configureKotlin() = configure<T> {
    // Warnings are errors by default (NFR-M2). Override locally with -PwarningsAsErrors=false.
    val warningsAsErrors = providers.gradleProperty("warningsAsErrors").map(String::toBoolean).orElse(true)
    when (this) {
        is KotlinAndroidProjectExtension -> compilerOptions
        is KotlinJvmProjectExtension -> compilerOptions
        else -> error("Unsupported Kotlin extension $this")
    }.apply {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(warningsAsErrors)
        freeCompilerArgs.addAll(
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
        )
    }
}
