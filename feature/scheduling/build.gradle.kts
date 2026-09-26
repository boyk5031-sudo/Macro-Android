plugins {
    alias(libs.plugins.macro.android.feature)
}

android {
    namespace = "com.macroandroid.feature.scheduling"
}

dependencies {
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    implementation(projects.automation.engine)
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockk)
}
