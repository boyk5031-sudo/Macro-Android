plugins {
    alias(libs.plugins.macro.android.application)
    alias(libs.plugins.macro.android.compose)
    alias(libs.plugins.macro.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.macroandroid.app"

    defaultConfig {
        applicationId = "com.macroandroid"
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "com.macroandroid.app.HiltTestRunner"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signing is configured from CI secrets / local keystore.properties in release prep; unsigned otherwise.
        }
    }

    buildFeatures {
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/LICENSE*", "META-INF/versions/9/OSGI-INF/MANIFEST.MF")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(projects.core.common)
    implementation(projects.core.ui)
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    implementation(projects.core.security)
    implementation(projects.core.platform)
    implementation(projects.automation.engine)
    implementation(projects.automation.android)
    implementation(projects.feature.apps)
    implementation(projects.feature.apkimport)
    implementation(projects.feature.macros)
    implementation(projects.feature.execution)
    implementation(projects.feature.scheduling)
    implementation(projects.feature.settings)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.compose.material3.adaptive.navigationSuite)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    ksp(libs.androidx.hilt.compiler)

    debugImplementation(libs.leakcanary.android)

    testImplementation(projects.core.testing)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockk)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.android.compiler)
    debugImplementation(libs.compose.ui.test.manifest)
}

/*
 * NFR-SEC-1 / ADR-0007 guard: fail the build if any dependency sneaks a forbidden permission into the merged manifest.
 * Runs as part of `check` for every variant.
 */
val forbiddenPermissions = listOf(
    "android.permission.INTERNET",
    "android.permission.REQUEST_INSTALL_PACKAGES",
    "android.permission.MANAGE_EXTERNAL_STORAGE",
    "android.permission.QUERY_ALL_PACKAGES",
    "android.permission.SYSTEM_ALERT_WINDOW",
    "android.permission.SCHEDULE_EXACT_ALARM",
    "android.permission.USE_EXACT_ALARM",
    "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
)

androidComponents {
    onVariants { variant ->
        val manifest = variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.MERGED_MANIFEST)
        val task = tasks.register("check${variant.name.replaceFirstChar(Char::uppercase)}ForbiddenPermissions") {
            group = "verification"
            description = "Asserts the merged manifest of ${variant.name} declares none of the forbidden permissions."
            inputs.file(manifest)
            doLast {
                val text = manifest.get().asFile.readText()
                val found = forbiddenPermissions.filter { text.contains("\"$it\"") }
                check(found.isEmpty()) { "Merged manifest declares forbidden permission(s): $found" }
            }
        }
        tasks.named("check").configure { dependsOn(task) }
    }
}
