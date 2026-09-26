plugins {
    alias(libs.plugins.macro.android.feature)
}

android {
    namespace = "com.macroandroid.feature.macros"
}

dependencies {
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    implementation(projects.automation.engine)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    testImplementation(libs.robolectric)
}
