plugins {
    alias(libs.plugins.macro.android.library)
    alias(libs.plugins.macro.android.hilt)
}

android {
    namespace = "com.macroandroid.core.testing"
}

dependencies {
    api(projects.core.common)
    api(projects.core.database)
    api(projects.core.datastore)
    api(projects.core.security)
    api(projects.automation.engine)
    api(testFixtures(projects.automation.engine))
    api(libs.junit4)
    api(libs.kotlinx.coroutines.test)
    api(libs.truth)
    api(libs.turbine)
    api(libs.androidx.test.core)
    api(libs.androidx.test.ext.junit)
    api(libs.androidx.test.runner)
    api(libs.androidx.test.rules)
    api(libs.hilt.android.testing)
    api(libs.androidx.room.testing)
    implementation(libs.kotlinx.coroutines.android)
}
