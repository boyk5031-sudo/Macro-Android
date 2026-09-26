plugins {
    alias(libs.plugins.macro.android.feature)
}

android {
    namespace = "com.macroandroid.feature.apps"
}

dependencies {
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    implementation(libs.androidx.core.ktx)
}
