plugins {
    alias(libs.plugins.macro.android.feature)
}

android {
    namespace = "com.macroandroid.feature.execution"
}

dependencies {
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    implementation(projects.automation.engine)
    implementation(projects.automation.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockk)
}
