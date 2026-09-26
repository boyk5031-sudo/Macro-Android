plugins {
    alias(libs.plugins.macro.android.library)
    alias(libs.plugins.macro.android.hilt)
}

android {
    namespace = "com.macroandroid.core.platform"
}

dependencies {
    api(projects.core.common)
    implementation(libs.androidx.core.ktx)
}
