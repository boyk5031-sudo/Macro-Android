plugins {
    alias(libs.plugins.macro.android.library)
    alias(libs.plugins.macro.android.hilt)
    alias(libs.plugins.macro.android.room)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.macroandroid.core.database"
    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
}

dependencies {
    api(projects.core.common)
    implementation(projects.core.security)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
}
