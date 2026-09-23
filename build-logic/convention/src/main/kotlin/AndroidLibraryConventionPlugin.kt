import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import com.macroandroid.buildlogic.Sdk
import com.macroandroid.buildlogic.configureKotlinAndroid
import com.macroandroid.buildlogic.configureModuleBoundaries
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.configure

class AndroidLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            apply(plugin = "com.android.library")
            apply(plugin = "org.jetbrains.kotlin.android")

            extensions.configure<LibraryExtension> {
                configureKotlinAndroid(this)
                testOptions.targetSdk = Sdk.TARGET
                lint.targetSdk = Sdk.TARGET
                // Resources in ":feature:apps" must be prefixed "feature_apps_" to avoid merge collisions.
                resourcePrefix = path.split("""\W""".toRegex()).drop(1).distinct()
                    .joinToString(separator = "_").lowercase() + "_"
                file("consumer-rules.pro").takeIf { it.exists() }?.let { defaultConfig.consumerProguardFiles(it) }
            }
            extensions.configure<LibraryAndroidComponentsExtension> {
                // Library modules only need androidTest for the debug build type.
                beforeVariants { variant ->
                    if (variant.buildType == "release") {
                        variant.androidTest.enable = false
                    }
                }
            }
            configureModuleBoundaries()
        }
    }
}
