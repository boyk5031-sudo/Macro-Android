import com.android.build.api.dsl.CommonExtension
import com.macroandroid.buildlogic.configureAndroidCompose
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.getByType

class AndroidComposeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            apply(plugin = "org.jetbrains.kotlin.plugin.compose")
            val extension = extensions.getByType<CommonExtension>()
            configureAndroidCompose(extension)
        }
    }
}
