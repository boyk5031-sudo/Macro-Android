// Root build: declares plugin versions (applied per module by build-logic convention plugins)
// and hosts the repository-wide static analysis task (detekt + formatting rules).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.detekt)
}

// detekt is applied ONLY at the root project, in plain (non-type-resolution) mode, over every
// module's Kotlin sources. This avoids the detekt-gradle-plugin Android hooks that rely on the
// AGP 8 variant API removed in AGP 9 (ADR-0002). Formatting rules come from detekt-formatting
// (ktlint rule engine), so no separate ktlint Gradle plugin is needed.
detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    baseline = rootProject.file("config/detekt/baseline.xml")
    source.setFrom(
        fileTree(rootDir) {
            include("**/src/*/kotlin/**/*.kt", "**/src/*/java/**/*.kt", "**/*.gradle.kts")
            exclude("**/build/**", "**/.gradle/**", "build-logic/build/**")
        },
    )
    parallel = true
    autoCorrect = providers.gradleProperty("detektAutoCorrect").map { it.toBoolean() }.getOrElse(false)
}

dependencies {
    detektPlugins("io.gitlab.arturbosch.detekt:detekt-formatting:${libs.versions.detekt.get()}")
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    jvmTarget = "17"
    reports {
        html.required.set(true)
        sarif.required.set(true)
        xml.required.set(false)
        txt.required.set(false)
        md.required.set(false)
    }
}
