package com.macroandroid.buildlogic

import com.android.build.api.dsl.CommonExtension
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.jetbrains.kotlin.compose.compiler.gradle.ComposeCompilerGradlePluginExtension
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension

internal fun Project.configureAndroidCompose(commonExtension: CommonExtension) {
    commonExtension.apply {
        buildFeatures.apply {
            compose = true
        }
    }

    dependencies {
        val bom = libs.findLibrary("compose-bom").get()
        "implementation"(platform(bom))
        "androidTestImplementation"(platform(bom))
        "implementation"(libs.findLibrary("compose-ui").get())
        "implementation"(libs.findLibrary("compose-ui-graphics").get())
        "implementation"(libs.findLibrary("compose-foundation").get())
        "implementation"(libs.findLibrary("compose-runtime").get())
        "implementation"(libs.findLibrary("compose-material3").get())
        "implementation"(libs.findLibrary("compose-ui-tooling-preview").get())
        "implementation"(libs.findLibrary("androidx-lifecycle-runtime-compose").get())
        "implementation"(libs.findLibrary("kotlinx-collections-immutable").get())
        "debugImplementation"(libs.findLibrary("compose-ui-tooling").get())
        "debugImplementation"(libs.findLibrary("compose-ui-test-manifest").get())
        "androidTestImplementation"(libs.findLibrary("compose-ui-test-junit4").get())
    }

    // Material 3 still marks bottom sheets, exposed dropdowns and top-bar scroll behaviour as experimental.
    extensions.configure<KotlinAndroidProjectExtension> {
        compilerOptions.freeCompilerArgs.add("-opt-in=androidx.compose.material3.ExperimentalMaterial3Api")
    }

    extensions.configure<ComposeCompilerGradlePluginExtension> {
        stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("config/compose/stability.conf"))
        val reportsDir = rootProject.layout.buildDirectory.dir("compose-reports/${project.name}")
        if (providers.gradleProperty("composeCompilerReports").map(String::toBoolean).getOrElse(false)) {
            reportsDestination.set(reportsDir)
            metricsDestination.set(reportsDir)
        }
    }
}
