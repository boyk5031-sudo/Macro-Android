plugins {
    alias(libs.plugins.macro.android.library)
    alias(libs.plugins.macro.android.hilt)
}

android {
    namespace = "com.macroandroid.core.datastore"
}

dependencies {
    api(projects.core.common)
    api(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
}
