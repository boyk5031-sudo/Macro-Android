package com.macroandroid.buildlogic

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType

/**
 * Enforces the module dependency rules from docs/phase-1/05-architecture.md §2.2.
 * Every module registers a `checkModuleBoundaries` task wired into `check`.
 */
internal fun Project.configureModuleBoundaries() {
    val productionConfigurations = setOf("implementation", "api", "compileOnly", "runtimeOnly")
    val task = tasks.register<CheckModuleBoundariesTask>("checkModuleBoundaries") {
        modulePath.set(project.path)
        dependencyPaths.set(
            provider {
                configurations
                    .filter { it.name in productionConfigurations }
                    .flatMap { conf -> conf.dependencies.withType<ProjectDependency>().map { it.path } }
                    .distinct()
                    .sorted()
            },
        )
    }
    plugins.withId("base") {
        tasks.named("check").configure { dependsOn(task) }
    }
}

abstract class CheckModuleBoundariesTask : DefaultTask() {
    @get:Input
    abstract val modulePath: Property<String>

    @get:Input
    abstract val dependencyPaths: ListProperty<String>

    init {
        group = "verification"
        description = "Fails when this module declares a project dependency forbidden by the architecture rules."
    }

    @TaskAction
    fun check() {
        val from = modulePath.get()
        val violations = dependencyPaths.get().filterNot { to -> isAllowed(from, to) }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "Module boundary violation(s) in $from: ${violations.joinToString()} " +
                    "(see docs/phase-1/05-architecture.md §2.2)",
            )
        }
    }

    @Suppress("ReturnCount")
    private fun isAllowed(from: String, to: String): Boolean {
        if (to == from) return true
        if (to == ":app") return false
        if (to == ":core:testing") return false // test-only consumer; production configurations may not use it
        return when {
            from == ":app" -> true
            from == ":core:common" -> false
            from == ":core:security" || from == ":core:datastore" || from == ":core:ui" -> to == ":core:common"
            // core:database hosts the Room-backed repositories, so it maps entities <-> engine model.
            from == ":core:database" -> to == ":core:common" || to == ":core:security" || to == ":automation:engine"
            from == ":core:testing" -> to.startsWith(":core:") || to == ":automation:engine"
            from == ":automation:engine" -> to == ":core:common"
            from == ":automation:android" ->
                to == ":automation:engine" || to == ":core:common" || to == ":core:database" ||
                    to == ":core:security" || to == ":core:datastore"
            from == ":feature:execution" ->
                to.startsWith(":core:") || to == ":automation:engine" || to == ":automation:android"
            from.startsWith(":feature:") -> to.startsWith(":core:") || to == ":automation:engine"
            else -> false
        }
    }
}
