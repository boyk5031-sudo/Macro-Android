# Phase 10 — Hardening, target-SDK gate and release preparation

Status legend: ✅ done in repo · 🟡 done, needs the CI run id recorded · ⬜ manual step outside the repo.

## 1. Build hardening

| Item | State | Where |
|---|---|---|
| R8 full mode, resource shrinking, optimized default rules | ✅ | `app/build.gradle.kts` `release` |
| Keep rules limited to kotlinx.serialization + line numbers; no reflection elsewhere | ✅ | `app/proguard-rules.pro` |
| `-Werror` for Kotlin, lint `warningsAsErrors`, detekt `maxIssues=0` | ✅ | `build-logic`, `config/detekt` |
| Module boundary check | ✅ | `checkModuleBoundaries` (CI step) |
| Forbidden-permission check on the merged manifest (INTERNET, REQUEST_INSTALL_PACKAGES, MANAGE_EXTERNAL_STORAGE, QUERY_ALL_PACKAGES, SYSTEM_ALERT_WINDOW, exact alarms, battery-optimisation request) | ✅ | `:app:check<Variant>ForbiddenPermissions` (CI step) |
| No backup / no device transfer of app data (Keystore-bound secrets) | ✅ | `data_extraction_rules.xml`, `allowBackup=false` (ADR-0009) |
| StrictMode in debug | ✅ | `MacroApplication` |
| LeakCanary in debug | ✅ | `debugImplementation` |
| Baseline profile | ⬜ post-MVP | needs a managed device run; `profileinstaller` dependency already present |

## 2. targetSdk 37 gate (Android 17)

Current: `compileSdk 37`, `targetSdk 36`. Moving `Sdk.TARGET` to 37 (`build-logic/.../ProjectExtensions.kt`) is a
one-line change; the checklist below must be green on an API 37 emulator first (behaviour changes verified 2026-09):

- [ ] `RemoteViews` bitmap limit — the app posts only standard `NotificationCompat` layouts; verify execution
      notifications still render (no custom RemoteViews are used → expected pass).
- [ ] Static finals immutable via reflection — no reflection in the codebase (R8 rules confirm) → expected pass.
- [ ] `ACCESS_LOCAL_NETWORK` runtime permission — not requested; the app has no networking → not applicable.
- [ ] Android 18 preview: implicit URI grants on `ACTION_SEND` removed — every share intent already sets
      `FLAG_GRANT_READ_URI_PERMISSION` (`feature:apkimport`, `feature:execution` exports).
- [ ] Re-run the instrumented suite on API 37 (`ci.yml` matrix `api: [37]`) and the manual journeys in doc 03.
- [ ] Update Play listing "target API" and this document with the run id.

Play requirement: target 36 is mandatory from 2026-08-31; target 37 becomes mandatory one year after Android 17's
release. There is no policy pressure to flip before the checklist passes.

## 3. Signing and CI secrets

`app/build.gradle.kts` reads `keystore.properties` (git-ignored) or environment variables:

```
release.storeFile=…/upload-keystore.jks     RELEASE_STOREFILE
release.storePassword=…                     RELEASE_STOREPASSWORD
release.keyAlias=upload                     RELEASE_KEYALIAS
release.keyPassword=…                       RELEASE_KEYPASSWORD
```

- ⬜ Generate the upload key (`keytool -genkeypair -v -keystore upload-keystore.jks -alias upload -keyalg RSA -keysize 4096 -validity 10000`).
- ⬜ Enrol in Play App Signing; upload the certificate; store the four secrets in the GitHub environment `release`.
- ✅ `release.yml` (tag `v*`, environment `release`) re-runs the verification gates, decodes
      `RELEASE_KEYSTORE_BASE64` into the runner temp dir, builds `bundleRelease` + `assembleRelease`, uploads the AAB,
      APK and `mapping.txt`, and opens a draft GitHub release. Secrets: `RELEASE_KEYSTORE_BASE64`,
      `RELEASE_STOREPASSWORD`, `RELEASE_KEYALIAS`, `RELEASE_KEYPASSWORD`.

## 4. Play Console checklist (manual)

- ⬜ **Accessibility Service permission declaration form** — describe: user-authored deterministic macros, no
      autonomous behaviour, prominent disclosure (`DisclosureRoute`), consent stored/versioned, revocation path,
      `isAccessibilityTool` intentionally absent. Attach a screen recording of: disclosure → consent → enabling the
      service → running a macro → withdrawing consent.
- ⬜ **Foreground service (specialUse) declaration** — subtype `user_initiated_macro_execution`; explain it is used
      only for runs the user started/scheduled and only while a run is active.
- ⬜ **Data safety** — no data collected or shared; data stored on device (macros, logs, audit); encryption at rest
      for sensitive macro values; users can delete data in Settings.
- ⬜ **Privacy policy URL** — host the text of Help → Privacy (`feature/settings/.../strings.xml`, `help_privacy_body`)
      and link it in the listing.
- ⬜ **Content**: screenshots for phone + 7"/10" tablets (adaptive navigation renders a rail on large screens),
      feature graphic, short/long description that repeats the limitations (no installation, no background control
      of other apps, inexact schedules).
- ⬜ Internal testing track → closed testing (12 testers / 14 days rule for new personal accounts) → production.

## 5. Release checklist (every release)

1. CI green on `main` (configure, detekt, assembleDebug, unit tests, lint, boundaries, permissions, assembleRelease).
2. Bump `versionCode`/`versionName` in `app/build.gradle.kts`; add a CHANGELOG entry.
3. Room schema: if `MacroDatabase.VERSION` changed, the migration test in `core:database` must cover the new step
      and the exported schema JSON must be committed (`core/database/schemas`).
4. Macro JSON schema: if `MacroDocument.SCHEMA_VERSION` changed, a migration exists in
      `automation/engine/.../serialization` and `docs/schema/` is updated.
5. Disclosure text changed? Bump `CURRENT_A11Y_DISCLOSURE_VERSION` so users re-consent.
6. Manual smoke on the lowest (API 26) and highest (API 37) supported images: onboarding, import APK, create macro,
      run manually, schedule + reboot reconciliation, withdraw consent.
7. Tag `vX.Y.Z`, let `release.yml` build the AAB, upload to the internal track, promote.

## 6. Known limitations shipped in 1.0

- Scheduled runs wait for the device to be unlocked when they need accessibility steps; WorkManager may defer
  them further under Doze/OEM battery managers (Help → Battery).
- A scheduled run that exceeds ~9 minutes is cancelled when WorkManager stops the worker, unless the app is visible
  or the foreground service could be started; long macros should be started manually.
- No OSS licence screen generated from the dependency graph yet (About lists the licence family; a generated
  `NOTICE` page is a post-1.0 item).
- Single language (English). `locales_config.xml` is in place for per-app language settings.
- No APK installation by design (ADR-0007).
