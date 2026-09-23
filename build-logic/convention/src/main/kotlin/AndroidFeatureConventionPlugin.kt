import com.macroandroid.buildlogic.libs
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.project

class AndroidFeatureConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            apply(plugin = "macro.android.library")
            apply(plugin = "macro.android.compose")
            apply(plugin = "macro.android.hilt")
            apply(plugin = "org.jetbrains.kotlin.plugin.serialization")

            dependencies {
                "implementation"(project(":core:common"))
                "implementation"(project(":core:ui"))
                "implementation"(libs.findLibrary("androidx-hilt-navigation-compose").get())
                "implementation"(libs.findLibrary("androidx-navigation-compose").get())
                "implementation"(libs.findLibrary("androidx-lifecycle-runtime-compose").get())
                "implementation"(libs.findLibrary("androidx-lifecycle-viewmodel-compose").get())
                "implementation"(libs.findLibrary("kotlinx-coroutines-android").get())
                "implementation"(libs.findLibrary("kotlinx-serialization-json").get())
                "testImplementation"(project(":core:testing"))
                "androidTestImplementation"(project(":core:testing"))
            }
        }
    }
}
