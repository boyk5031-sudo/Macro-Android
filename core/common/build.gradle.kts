plugins {
    alias(libs.plugins.macro.jvm.library)
    alias(libs.plugins.macro.android.hilt)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.datetime)
}
