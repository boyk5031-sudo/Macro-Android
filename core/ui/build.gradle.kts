plugins {
    alias(libs.plugins.macro.android.library)
    alias(libs.plugins.macro.android.compose)
}

android {
    namespace = "com.macroandroid.core.ui"
}

dependencies {
    api(projects.core.common)
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.ui.graphics)
    api(libs.compose.ui.tooling.preview)
    api(libs.compose.foundation)
    api(libs.compose.runtime)
    api(libs.compose.material3)
    api(libs.compose.material3.windowSizeClass)
    api(libs.compose.material.icons.extended)
    api(libs.androidx.lifecycle.runtime.compose)
    api(libs.kotlinx.collections.immutable)
    implementation(libs.androidx.core.ktx)
    debugApi(libs.compose.ui.tooling)
}
