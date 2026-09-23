# ADR-0004 — Navigation-Compose 2.10 with type-safe routes instead of Navigation 3

- Status: Accepted (revisit trigger below)

## Context
Navigation 3 (`androidx.navigation3`) is at 1.2.0-rc01 / 1.1.7 stable as of 2026-09. It offers a developer-owned back stack that fits Compose well, and `material3-adaptive-navigation3` exists. However: Hilt ViewModel scoping per back-stack entry requires the `lifecycle-viewmodel-navigation3` integration and its patterns are still settling; the team's risk budget is better spent on the engine and policy work. Navigation-Compose 2.10.1 with `@Serializable` routes is mature, integrates with `hiltViewModel()`, deep links, and predictive back out of the box.

## Decision
Use `androidx.navigation:navigation-compose:2.10.1` with `@Serializable` route classes, one nested graph per feature module (`fun NavGraphBuilder.macrosGraph(...)`), `material3-adaptive-navigation-suite` for the top-level navigation container, and `ListDetailPaneScaffold` from `material3-adaptive-layout` inside features on expanded widths.

Revisit when: Navigation 3 ships a stable adaptive + Hilt story and Navigation-Compose 2.x is announced as maintenance-only. Routes are plain data classes so the migration cost is bounded to `AppNavHost` and the per-feature graph builders.
