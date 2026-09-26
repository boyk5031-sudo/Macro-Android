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

            extensions.configure<LibraryExtension> {
                configureKotlinAndroid(this)
                testOptions.targetSdk = Sdk.TARGET
                lint.targetSdk = Sdk.TARGET
                // Resource names use short per-module prefixes (apps_, apk_, macro(s)_, exe_, sch_, set_/disc_/perm_,
                // trg_, automation_android_, core-ui action_/err_) – see docs/phase-1/05-architecture.md. A Gradle
                // resourcePrefix is deliberately not enforced: lint's ResourceName would demand the long module path.
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
