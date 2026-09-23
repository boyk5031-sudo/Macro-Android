# 06 — Permission Matrix

Legend: **R** = runtime prompt; **S** = special app access (Settings toggle, no prompt API); **N** = normal (install-time, no UI); **Q** = manifest `<queries>` (not a permission); **—** = not applicable on that version.

## 1. Declared

| Capability / manifest element | API 26–28 | 29–30 | 31–32 | 33 | 34 | 35 | 36 | 37 | Feature(s) | Behaviour when denied/unavailable | Play declaration |
|---|---|---|---|---|---|---|---|---|---|---|---|
| `POST_NOTIFICATIONS` | — (channels only) | — | — | R | R | R | R | R | Execution progress, blocked-run prompt, schedule results, `SendNotification` action | All features work; status only in-app; `SendNotification` step → `FAILED(NOTIFICATIONS_DENIED)` (category `PERMISSION`, not retried) | none |
| `FOREGROUND_SERVICE` | N | N | N | N | N | N | N | N | `ExecutionForegroundService` | — | — |
| `FOREGROUND_SERVICE_SPECIAL_USE` | — | — | — | — | N | N | N | N | same; `foregroundServiceType="specialUse"` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE="user_initiated_macro_execution"` | On 34+ without it the service start throws; we declare it. If the start is disallowed at runtime (`ForegroundServiceStartNotAllowedException`, e.g. started from background), run continues in-process, record flagged `fgsUnavailable` | **Yes** — FGS declaration form with subtype justification and demo video |
| `shortService` type (no permission) | — | — | — | — | used | used | used | used | Runs with static budget ≤ 3 min | `onTimeout` → we stop the service; execution continues in-process | none |
| `BIND_ACCESSIBILITY_SERVICE` (on `<service>`) | S | S | S | S (+ restricted-settings gate for sideloads) | S | S | S (+ blocked during calls) | S | Click/Scroll/EnterText/Back/Home steps | Macro with a11y steps cannot run; shows "Needs accessibility"; other macros unaffected | **Yes** — Accessibility declaration (non-tool), prominent disclosure video |
| `RECEIVE_BOOT_COMPLETED` | N | N | N | N | N | N | N | N | `BootReconcileReceiver` → `ReconcileWorker` | — (never starts an FGS or an execution) | none |
| `WAKE_LOCK` | N | N | N | N | N | N | N | N | transitively by WorkManager | — | none |
| `<queries>` `ACTION_MAIN`+`CATEGORY_LAUNCHER` | Q (no effect < 30) | Q | Q | Q | Q | Q | Q | Q | Installed apps list, LaunchApp resolution | Without it (30+) the list would be empty | none |
| `<queries>` `ACTION_VIEW` + `http`/`https` | Q | Q | Q | Q | Q | Q | Q | Q | OpenUrl resolution error message | — | none |
| `<queries>` `ACTION_OPEN_DOCUMENT`/`ACTION_CREATE_DOCUMENT` | not needed (system picker) | | | | | | | | | | |
| Persistable URI grants (`takePersistableUriPermission`) | API | API | API | API | API | API | API | API | Imported APKs | Revoked → `UNAVAILABLE` state | none |
| Pinned shortcuts (`ShortcutManagerCompat`) | API 26+ | | | | | | | | Pin app/macro | Launcher may not support → button disabled | none |
| Exact alarms | **not used** | | | | | | | | | | |
| Battery optimization exemption | **not requested**; Permission center links to the system list screen (`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`) | | | | | | | | Reliability of schedules | Informational | none |

## 2. Explicitly not declared

`INTERNET`, `ACCESS_NETWORK_STATE`, `QUERY_ALL_PACKAGES`, `REQUEST_INSTALL_PACKAGES`, `REQUEST_DELETE_PACKAGES`, `MANAGE_EXTERNAL_STORAGE`, `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`, `READ_MEDIA_*`, `SCHEDULE_EXACT_ALARM`, `USE_EXACT_ALARM`, `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `SYSTEM_ALERT_WINDOW`, `PACKAGE_USAGE_STATS`, `READ_SMS`/`RECEIVE_SMS`, `RECORD_AUDIO`, `CAMERA`, location, contacts, calendar, `BIND_DEVICE_ADMIN`, `BIND_NOTIFICATION_LISTENER_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`/`MEDIA_PROJECTION`/any other FGS type, `ACCESS_LOCAL_NETWORK` (37), `USE_FULL_SCREEN_INTENT`.

A Phase 2 CI check asserts that the merged release manifest contains no permission outside the allow-list in §1.

## 3. Accessibility service configuration (`res/xml/accessibility_service_config.xml`)

| Attribute | Value | Reason |
|---|---|---|
| `accessibilityEventTypes` | `typeWindowStateChanged\|typeWindowContentChanged` | Enough to know when to re-query nodes; no text-change/keystroke events |
| `accessibilityFeedbackType` | `feedbackGeneric` | Required attribute; we give no feedback |
| `accessibilityFlags` | `flagReportViewIds\|flagIncludeNotImportantViews` (the latter **off** by default; user-toggleable in Settings with explanation) | View ids make matching robust; not-important views are sometimes needed but widen visibility |
| `canRetrieveWindowContent` | `true` | Required to find nodes |
| `canPerformGestures` | `false` (MVP) | No coordinate gestures |
| `canTakeScreenshot` | `false` (MVP) | Post-MVP feature |
| `canRequestFilterKeyEvents`, `canRequestTouchExplorationMode`, `canRequestFingerprintGestures`, `canControlMagnification` | `false` | Not needed |
| `notificationTimeout` | `200` ms | Batches content-changed events |
| `packageNames` | absent (all) | The user chooses targets per macro; restricting at the manifest level would require a rebuild per target. The gateway ignores events when idle. |
| `isAccessibilityTool` | **absent** | We are not an accessibility tool (Play policy) |
| `settingsActivity` | `…settings.ConsentActivityAlias` (routes into the app's consent/permission screen) | System Accessibility settings show a link to our disclosure |
| `description` | `@string/a11y_service_description` (plain-language, mirrors the disclosure) | Shown in system settings |
| `summary` | `@string/a11y_service_summary` | Shown in system settings list |

## 4. Runtime gates implemented in code

| Gate | Check | Where |
|---|---|---|
| `NotificationsGate` | `NotificationManagerCompat.areNotificationsEnabled()` and channel importance ≠ NONE | before any `notify` |
| `AccessibilityGate` | `AccessibilityServiceRegistry.isConnected` **and** `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` contains our component (the latter catches "enabled but not yet bound") | before UI segments; Permission center |
| `ConsentGate` | DataStore `consentVersion == CURRENT` | before enabling the a11y row and before running/scheduling macros with a11y steps |
| `ForegroundGate` | `ProcessLifecycleOwner.lifecycle.currentState ≥ STARTED` **or** `lastNotificationTapAt` within 10 s | before `startActivity` from the engine and before starting the FGS |
| `ScreenGate` | `PowerManager.isInteractive && !KeyguardManager.isKeyguardLocked` | before UI segments |
| `UriGrantGate` | `contentResolver.persistedUriPermissions` contains the URI with read | before reading an imported APK |
