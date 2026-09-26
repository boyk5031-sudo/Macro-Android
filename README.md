# MacroAndroid

Local-only Android app for organising installed apps and APK files and for running **user-authored, deterministic
macros** (launch → wait → tap → type → …) by hand, from a shortcut, or on a schedule. No internet permission, no
installation of packages, no autonomous automation.

- Kotlin 2.3 · Jetpack Compose Material 3 · single activity · MVVM / Clean · Hilt · Room · DataStore · WorkManager · SAF
- `minSdk 26` · `targetSdk 36` · `compileSdk 37` · Google Play distribution

## Repository map

| Path | Contents |
|---|---|
| `app/` | `MacroApplication`, `MainActivity`, adaptive navigation shell, intent routing, shortcuts, R8 rules, signing |
| `core/common` | `AppResult`/`ErrorCode`, dispatchers, logging, cross-feature contracts |
| `core/database` | Room entities/DAOs, repositories (`Macro`, `Execution`, `Schedule`), `SecureValueStore` |
| `core/datastore` | `UserPreferencesRepository` (Preferences DataStore) |
| `core/security` | Keystore AES-GCM cipher, SHA-256 |
| `core/ui` | Theme, shared components, error messages |
| `core/platform`, `core/testing` | Android `Logger`; test doubles/rules |
| `automation/engine` | Pure-Kotlin macro model, validator, JSON (de)serialisation + migrations, executor, `NextRunCalculator` |
| `automation/android` | Accessibility service + gateway, precondition gate, notifications, foreground service, `MacroRunner`, WorkManager scheduler/worker, boot reconciliation |
| `feature/apps` | Installed apps: search, sort, favourites, launch, pin shortcuts |
| `feature/apkimport` | SAF import, metadata, checksums, duplicate detection |
| `feature/macros` | Macro list, editor (15 step types), preview, import/export |
| `feature/execution` | Active runs, history, run detail with steps/logs, export |
| `feature/scheduling` | Schedule list/editor, missed-run policy, next-run preview |
| `feature/settings` | Settings, permission center, accessibility disclosure/consent, audit log, help, about, onboarding |
| `build-logic/` | Convention plugins (AGP 9 built-in Kotlin), module-boundary check |
| `docs/` | Phase 0 feasibility, Phase 1 specification + ADRs, JSON schema, Phase 10 hardening/release |
| `.github/` | `ci.yml` (configure, detekt, assemble, tests, lint, boundaries, forbidden permissions, R8), `release.yml` |

## Build

Requirements: JDK 17, Android SDK platform 37 + build-tools 36+, Android Studio Quail 4 (2026.1.4) or newer for
IDE use (AGP 9.4.1).

```bash
./gradlew :app:assembleDebug                       # debug APK (applicationId com.macroandroid.debug)
./gradlew testDebugUnitTest                        # JVM + Robolectric unit tests
./gradlew detekt lintDebug checkModuleBoundaries   # static gates
./gradlew :app:checkDebugForbiddenPermissions      # merged-manifest guard (no INTERNET, no install, …)
./gradlew :app:assembleRelease                     # R8; signed when keystore.properties / RELEASE_* env are present
```

Warnings are errors (`-Werror`, lint `warningsAsErrors`, detekt `maxIssues=0`); pass `-PwarningsAsErrors=false`
for local iteration only.

## Security & policy posture (summary)

- No `INTERNET`, `REQUEST_INSTALL_PACKAGES`, `MANAGE_EXTERNAL_STORAGE`, `QUERY_ALL_PACKAGES`, `SYSTEM_ALERT_WINDOW`,
  exact-alarm or battery-optimisation-request permissions — enforced by a build check.
- Accessibility service: off by default, requires the in-app prominent disclosure + versioned consent, refuses
  password fields and the lock screen, runs only user-defined steps, revocable from the Permission center.
- `RECEIVE_BOOT_COMPLETED` is used solely to reconcile schedules and interrupted executions.
- Sensitive macro values are encrypted with a device-bound Keystore key; app data is excluded from backup/transfer.
- Typed macro model only: no scripts, no downloaded code, no shell/root.

See `docs/phase-0-feasibility-and-constraints.md` for the full feasibility analysis and
`docs/phase-10-hardening-and-release.md` for the release checklist.
