plugins {
    alias(libs.plugins.macro.android.library)
    alias(libs.plugins.macro.android.hilt)
}

android {
    namespace = "com.macroandroid.automation.android"
}

dependencies {
    api(projects.automation.engine)
    api(projects.core.common)
    implementation(projects.core.database)
    implementation(projects.core.datastore)
    implementation(projects.core.security)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
