# Phase 12 — Trigger Areas (one tap → multiple target taps)

Status: implemented on branch `arena/01a0cd30-macro-android`; verification happens exclusively in GitHub Actions
(the authoring sandbox has no JDK/SDK). The CI run id that proves a green build is recorded in the PR / final
report, never assumed here.

A **Trigger Area** is a rectangle the user places on screen. While it is active over a game, a single touch-down
inside the rectangle sends taps to a list of **target points** the user placed earlier. It is *not* macro
recording: nothing is recorded, the trigger touch never becomes a target, and the existing macro engine is
untouched (the feature lives in its own packages, DAO, table, ViewModels and nav graph).

---

## 1. Step 1 — Inspection results (what the project already had)

| Topic | Finding |
|---|---|
| Architecture | Multi-module MVVM/Clean, Hilt, Compose M3, Room 2.8.5, DataStore, WorkManager. Convention plugins in `build-logic`, module-boundary matrix in `ModuleBoundaries.kt`. |
| AGP / SDK | AGP 9.4.1, Gradle 9.7.1, Kotlin 2.3.21 (built-in), compileSdk 37, targetSdk 36, **minSdk 26**. |
| Services | `MacroAccessibilityService` (foreground-less, bound by the system), execution FGS (`specialUse`), WorkManager workers. |
| Overlays | None. `SYSTEM_ALERT_WINDOW` is on the forbidden-permission list enforced by `checkForbiddenPermissions`. |
| Macro system | Typed step list (`MacroDefinition`, 15 action types) executed by `automation:engine` through `AccessibilityGateway` (node actions: click/setText/scroll…). No coordinate taps, no gesture injection. |
| Key mapping / input injection | None. `canPerformGestures` was `false`. |
| Accessibility | Play-compliant flow: disclosure screen → DataStore consent (versioned) → Android Settings switch → `AccessibilityServiceRegistry` (connected service, `service` StateFlow). |
| Permissions | `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE(_SPECIAL_USE)`, `RECEIVE_BOOT_COMPLETED`, `USE_BIOMETRIC`. No INTERNET. |
| Coordinate conversion | None (macro steps address nodes, not pixels). |
| Persistence | Room `MacroDatabase` v1 (macros, runs, logs, schedules, audit), DataStore prefs. Export/import JSON with schema versions. |
| Game profiles | None → optional **per-app binding** on each trigger (see §5). |

Conclusion: no existing injection or overlay subsystem to reuse → both were designed (§3), built on the one
component that already had the user's consent: the accessibility service.

---

## 2. Permissions & API requirements (no root)

**Zero new manifest permissions.** Everything rides on the already-declared accessibility service:

| Capability | Mechanism | Min API | Why not something else |
|---|---|---|---|
| Show the trigger zone over the game | `WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY` added through the *bound service's* `WindowManager` | 22 | Needs no `SYSTEM_ALERT_WINDOW`; window is removed automatically when the service unbinds. |
| Receive the trigger tap | The overlay window is exactly the size of the zone, `FLAG_NOT_TOUCH_MODAL`; touches outside pass through untouched | 22 | Only touches *inside* the zone ever reach us (§6 spec: "outside ignored"). |
| Inject taps | `AccessibilityService.dispatchGesture(GestureDescription)` with `android:canPerformGestures="true"` in the service XML | 24 | The only non-root, non-hidden API for synthetic touch input in other apps. |
| Know which app is in front | `TYPE_WINDOW_STATE_CHANGED` events (already subscribed) | 26 | No `QUERY_ALL_PACKAGES`, no usage-stats permission. The app picker uses a `CATEGORY_LAUNCHER` intent query, which is allowed under package-visibility rules. |
| Display geometry | `WindowManager.currentWindowMetrics` (30+) / `Display.getRealMetrics` + `DisplayCutout` (26–29) | 26 | — |

Android-version specifics handled in code: Android 13+ "restricted setting" hint for side-loaded builds
(`InstallSource.restrictedSettingsMayApply`), Android 14 `isAccessibilityDataSensitive` untouched, no hidden
APIs, no `Instrumentation`, no `input` shell, no `/dev/input`. **Root is not required and not used.**

Play policy: the accessibility disclosure text was extended (Trigger Areas named explicitly) and
`CURRENT_A11Y_DISCLOSURE_VERSION` bumped to 2. Users who consented to v1 keep their macros working but must
re-agree before any trigger zone can be shown or any tap injected (`UserPreferences.accessibilityConsentCurrent`).

---

## 3. Components (spec §10) and where they live

```
Trigger Detection ──► Trigger Controller ──► Target Point Resolver ──► Coordinate Converter ──► InputInjectionAdapter ──► Android
TriggerOverlayView    TriggerController       TargetResolver            CoordinateConverter       AccessibilityInput-      dispatchGesture
(automation:android)  TriggerActivationGate   (engine, pure JVM)        (engine, pure JVM)        InjectionAdapter         (a11y service)
                      TriggerPlanExecutor                                                         (automation:android)
                      (engine, pure JVM)
```

| Layer / module | Files | Responsibility |
|---|---|---|
| `core:common` | `display/DisplayGeometry.kt`, `contract/TriggerContracts.kt`, `ErrorCode` (+15 codes) | Display model (px, insets, cutouts, orientation), runtime/access contracts consumed by features, error taxonomy. |
| `automation:engine` `…automation.trigger` | `TriggerModel.kt`, `TriggerValidator.kt`, `CoordinateConverter.kt`, `TargetResolver.kt`, `InjectionPlan.kt`, `TriggerActivationGate.kt`, `TriggerController.kt` (+`TriggerPlanExecutor`), `TriggerRuntimePolicy.kt`, `TriggerJson.kt` | Everything that can be unit-tested without Android: model + limits, validation, coordinate maths, plan building, touch-down/up/cancel state machine + cooldown, sequential/multi-touch execution, activation policy (which config is visible when), JSON document with schema version. |
| `core:database` | `entity/TriggerEntities.kt`, `dao/TriggerDao.kt`, `Migrations.kt` (1→2), `repository/TriggerRepository.kt` | `trigger_configs` table (id, name, enabled, package_name, config_json, timestamps). Config body stored as JSON so the schema evolves via `TriggerJson`, not via Room migrations. Corrupt rows are skipped, never crash. |
| `core:platform` | `DisplayGeometryReader.kt`, `InstallSource.kt` | Real display metrics per API level; side-load detection for the restricted-settings hint. |
| `core:datastore` | `showTriggerIndicator` preference, `accessibilityConsentCurrent` | Gameplay indicator visibility; disclosure-version-aware consent. |
| `automation:android` `…android.trigger` | `AccessibilityInputInjectionAdapter.kt`, `TriggerOverlayView.kt`, `TriggerOverlayController.kt`, `TriggerAccessChecker.kt`, `TriggerRuntime.kt` | Injection through `dispatchGesture`; overlay window per visible trigger; live access status; the runtime that combines DB configs × foreground package × armed id × access × screen state into show/hide decisions and executes activations. |
| `automation:android` (existing, extended) | `AccessibilityServiceRegistry` (+`foregroundPackage`, `displayChanges`, `canPerformGestures`), `MacroAccessibilityService` (starts/stops `TriggerRuntime`, forwards `onConfigurationChanged`), service XML (`canPerformGestures="true"`), `AccessibilityConsent` (+`consentCurrent`) | |
| `feature:trigger` (new) | `presentation/TriggerListViewModel.kt`, `presentation/TriggerEditorViewModel.kt`, `data/LauncherApps.kt`, `ui/TriggerListScreen.kt`, `ui/TriggerEditorScreen.kt`, `ui/TriggerCanvas.kt`, `ui/AccessChecklist.kt`, `ui/TriggerNavigation.kt`, `res/values/strings.xml` | List (arm, enable, duplicate, delete, import/export via SAF), editor (canvas + numeric fields + execution + points), access checklist, test mode. |
| Integration | `MacroListScreen` top-bar entry, `MacroApp.kt` nav graph, Settings toggle, disclosure text, `app/build.gradle.kts` dependency | |

---

## 4. Injection mechanism — exactly what "Sequential" and "Multi-touch" mean

`AccessibilityInputInjectionAdapter` reports `InjectionCapability(maxSimultaneousContacts = 10, supportsHold = true)`
only while a service is bound; otherwise `GESTURE_DISPATCH_UNAVAILABLE`.

* **Sequential** — one `dispatchGesture` per enabled target, in list order, with a real `delay(delayBeforeMs)`
  before each (default 60 ms between taps). `LONG_PRESS` uses a longer stroke duration (`holdMs`, default 600 ms).
* **Multi-touch** — one `GestureDescription` containing one `StrokeDescription` per enabled target, all starting at
  t = 0. Android delivers these as genuine simultaneous pointers (pointer ids 0…n-1) — this is the only mode we
  label "simultaneous"; capped at `GestureDescription.getMaxStrokeCount()` = 10. Per-target delays are ignored
  and the editor says so.
* **Reaction delay** (`reactionDelayMs`, 0–5000, presets 0/10/25/50/100/150/200/500, any value typeable) is a
  real `delay()` between the activating DOWN and the first contact; during it the trigger is `EXECUTING` and
  `disarm()` cancels it without injecting anything.
* **Hold** is genuine: each `StrokeDescription` has the point's `holdMs` as its duration (press → hold → release
  is one stroke; 10–5000 ms). Nothing is faked with repeated taps.
* **Repeat mode** is `ONCE` or `FIXED_COUNT` (`repeatCount` 1–20 with `repeatDelayMs` as the interval); it is
  derived from `repeatCount` so older documents stay valid. "Repeat while held" is **not offered** because
  `dispatchGesture` cancels the user's own touch (see next point), so a continuous hold cannot be observed.
* **Own-window collision**: while a plan executes, the gameplay overlay window is flagged `FLAG_NOT_TOUCHABLE`
  (`TriggerOverlayController.setTouchable`) so an injected contact whose target lies *inside* the trigger area
  reaches the game instead of our own window; the flag is cleared when the plan finishes. The edit-mode window
  does the same for its TEST button.
* Consequence of the platform: dispatching a gesture cancels the touch currently on the overlay
  (`ACTION_CANCEL`). The controller treats CANCEL like UP (release → idle); the gate makes sure one DOWN
  activates **at most once** regardless of subsequent MOVE/CANCEL events.
* Stuck contacts: every injected stroke has a finite duration; `cancelAll()` on disarm/teardown cancels the
  in-flight gesture (`GestureResultCallback.onCancelled`) and the runtime never leaves a pending stroke when the
  overlay is removed.

---

## 5. Coordinate system (spec §7) — documented spaces

| Space | Unit | Used for |
|---|---|---|
| **Stored** | fractions 0…1 of the *full physical display* (`width×height` incl. system bars), plus `authoredDisplay {widthPx, heightPx, orientation}` | `TriggerArea`, `TargetPoint` in `CoordinateSpace.DISPLAY`. Resolution-independent; survives density changes. |
| Stored, relative | fractions 0…1 of the *trigger area* | `TargetPoint` in `CoordinateSpace.TRIGGER_RELATIVE` ("moves with zone" chip). Off by default (spec: moving the area must not move points unless explicitly relative). |
| **Internal / runtime** | physical px of the *current* display (`DisplayGeometry`), origin top-left of the full screen | Overlay `LayoutParams` (with `FLAG_LAYOUT_IN_SCREEN|FLAG_LAYOUT_NO_LIMITS` so insets do not shift it) and `GestureDescription` paths — both APIs use the same full-screen px space, so **no inset offset is applied**; insets/cutouts are only *drawn* in the editor so the user sees where system UI sits. |
| Editor canvas | canvas px of a box with the display's aspect ratio | Converted by a plain division into stored fractions. Numeric fields show/accept px on the authored display. |

`CoordinateConverter.compatibility(authored, current)`: `ORIENTATION_MISMATCH` → the trigger is *hidden* with
`DISPLAY_ORIENTATION_MISMATCH` as the blocked reason (portrait triggers never appear in landscape and vice
versa); `ASPECT_DIFFERS` (>2 %) → shown, editor warns. Off-display points are rejected before injection
(`TRIGGER_COORDINATE_INVALID`).

---

## 6. Runtime behaviour & lifecycle (spec §5, §12, §13)

`TriggerRuntime` (lives inside the a11y service scope) collects
`configs × foregroundPackage × manuallyArmedId × access × interactive(screen on & unlocked) × displayChanges`
and asks the pure `TriggerRuntimePolicy` which configs must be visible:

* config bound to a package → visible while that package is in front;
* unbound config → visible only after the user **arms** it from the list (one at a time);
* never visible over MacroAndroid itself, on the keyguard, with the screen off, or when access is missing;
* enabling/disabling/deleting a config while visible removes its overlay on the next DB emission;
* consent withdrawn / service disabled / service unbound → `stop()` removes all overlays and cancels gestures;
* rotation / display change → overlays are re-laid-out from the stored fractions on the new geometry, or hidden
  on orientation mismatch;
* process recreation → the system rebinds the service, `onServiceConnected` restarts the runtime from Room.

Activation state machine (`TriggerActivationGate` + `TriggerController`, explicit `TriggerState`):
`IDLE` ─DOWN inside─▶ `EXECUTING` (reaction delay → targets → repeats) ─done─▶ `WAITING_FOR_RELEASE` (finger
still down) ─UP/CANCEL─▶ `COOLDOWN` ─`cooldownMs` elapsed─▶ `IDLE`. A DOWN in any state but `IDLE` is rejected
(`TRIGGER_BUSY` / `TRIGGER_COOLDOWN`), which is what guarantees one physical gesture never activates twice. MOVE
events never reach the controller (the overlay forwards DOWN/UP/CANCEL only). Every validation (enabled, targets,
coordinates, access, capability) runs again at activation time. Rejections carry a reason code and are logged
(debug builds) but never crash.

**On-screen edit mode** (spec §13 "Edit Mode"): *Adjust over game* in the editor (saved configurations only)
calls `TriggerRuntimeContract.startOverlayEdit(id)`, launches the bound game (existing `AppLauncher` port) and the
runtime shows one full-display `TYPE_ACCESSIBILITY_OVERLAY` window (`TriggerEditOverlayView`) instead of any
gameplay overlay: drag the area, drag its bottom-right corner to resize, drag numbered markers, tap empty space
or *Add* to add a point, *On/Off* / *Delete* for the selected point, *Test* (window made untouchable during
injection), *Cancel*, *Done* (writes area/points back through `TriggerRepository.save`; refused saves, e.g. no
enabled target, keep the editor open). All drag maths is the pure `TriggerEditSession` (engine, unit-tested);
the view only draws. The editor obeys the same policy as gameplay (never over our own app, needs access, screen
on, display measurable, matching orientation; bound configs only over their package) and pauses — keeping the
unsaved session — when those conditions lapse; deleting the configuration ends the session. Numeric X/Y editing
stays in the in-app editor.

Indicator: the zone outline is drawn when the global setting *Show trigger area outline in games* is on, unless
the configuration overrides it (Follow setting / Show / Hide). A hidden zone still receives taps.

---

## 7. Data model (spec §11)

```jsonc
{ "schemaVersion": 1, "triggers": [ {
  "id": "uuid", "name": "Combo", "enabled": true, "packageName": "com.game" | null,
  "triggerArea": { "x": 0.093, "y": 0.292, "width": 0.185, "height": 0.0625 },
  "authoredDisplay": { "widthPx": 1080, "heightPx": 2400, "orientation": "PORTRAIT" },
  "targetPoints": [ { "id": "uuid", "x": 0.37, "y": 0.125, "coordinateSpace": "DISPLAY",
                      "actionType": "TAP" | "LONG_PRESS", "delayBeforeMs": 60, "holdMs": null, "enabled": true } ],
  "executionMode": "SEQUENTIAL" | "MULTI_TOUCH", "reactionDelayMs": 0, "cooldownMs": 300,
  "repeatCount": 1, "repeatDelayMs": 100, "showIndicatorInGameplay": null
} ] }
```

Room: `MacroDatabase` **v2**, `Migration(1, 2)` creates `trigger_configs` (indexed `package_name`, `enabled`).
Import/export: the list screen offers *Export trigger areas* (SAF `CreateDocument`) and *Import* (SAF
`OpenDocument`); imported configs get new ids and arrive **disabled** for review. Limits: 10 targets, 100
triggers per document, 1 MiB.

---

## 8. UI (spec §4)

* **Macros → Trigger areas** (top-bar icon) → list: access checklist card (exact missing requirement, why, and a
  button that opens the disclosure or Android Accessibility settings; re-checked in `onResume`), runtime banner
  ("2 trigger areas on screen" / "Armed but hidden: <reason>"), indicator switch, one card per trigger with
  app binding, target count, mode, *Arm/Armed* chip (unbound only), duplicate, delete, enable switch. FAB *New*.
* **Editor**: canvas (full display aspect, shaded system bars/cutouts, translucent zone with corner resize
  handle, numbered target markers — tertiary = enabled, outline grey = disabled, red ring = selected; tap empty
  space to add, drag to move), toolbar (*Add point*, *Test here*, *Test in game*), validation card, General
  (name, game/app picker, enabled), Trigger zone X/Y/W/H px, Execution (Sequential/Multi-touch segmented control
  with an honest description, cooldown, repeat ×, repeat gap, indicator override), Target points (per point:
  order ▲▼, delete, enable, X/Y px, Tap/Long press, "Moves with zone", delay-before (sequential only), hold).
  *Save* in the top bar is enabled only when validation passes.
* **Test** (spec §17): *Test here* covers the editor with a scrim (nothing else is tappable), injects the
  configured taps through the exact same resolver/injection path, and draws a ring where each injected tap
  lands — an on-device proof of the coordinate pipeline. *Test in game* counts down 5 s so the user can switch
  to the game, then injects once. Both are clearly labelled and never leave a zone armed.
* Settings → Execution: *Show trigger area outline in games*.

---

## 9. Tests

JVM (`automation:engine`, no Android): `CoordinateConverterTest`, `TargetResolverTest`, `TriggerActivationGateTest`,
`TriggerControllerTest`, `TriggerRuntimePolicyTest`, `TriggerJsonTest`, `TriggerEditSessionTest` — 57 cases
covering: touch inside / outside, all enabled points executed, trigger location never alters targets, order,
sequential delays, reaction delay (nothing injected before it elapses; disarm during it injects nothing), genuine
multi-touch stroke count, cooldown, the explicit state machine (IDLE → EXECUTING → WAITING_FOR_RELEASE →
COOLDOWN → IDLE with rejections in each non-idle state), single-point test plans (only that point, once, no
reaction delay / pre-delay / repeats, allowed while the configuration has no enabled target), repeat mode
derivation, disabled area / disabled point / empty list, invalid & off-screen coordinates, scaling between
resolutions, orientation mismatch, relative vs display space, JSON round trip, schema-too-new, activation policy
(bound / armed / own app / access missing / screen off), edit-mode policy (replaces gameplay overlays, follows
package binding, same access/screen/display/orientation rules, deletion noticed), on-screen edit session (hit
priority, area move leaves display points and carries relative points, resize clamping, point drag clamping,
tap-vs-drag slop, add/toggle/delete with cap, cancel and geometry re-mapping), no stuck contacts (`cancelAll`
on disarm).

ViewModel tests (`feature:trigger`, Robolectric-free): `TriggerEditorViewModelTest` (defaults, load, not-found,
numeric vs drag edits, relative/display point behaviour on area move, add/move/reorder/disable/delete/cap,
clamping & validation, save success/failure, test refused without access then runs, in-game countdown cancel,
reaction-delay clamping, per-point test path even with no enabled targets, on-screen edit requiring a saved
configuration then starting the runtime editor and launching the bound game, rotation compatibility) and
`TriggerListViewModelTest` (state combination, disarm on disable/delete, arm error,
export→import round trip, corrupt import, indicator preference).

Persistence: `Migration(1, 2)` runs under Room's schema export; the repository test path is exercised through
the existing `TestDatabase` (in-memory) in the database module tests.

Not automatable here: overlay window behaviour, real `dispatchGesture` delivery, app switching — covered by the
manual acceptance flow in §11.

---

## 10. Device / Android limitations (honest list)

* Games that render with `FLAG_SECURE` still receive injected gestures; games that detect accessibility services
  or synthetic input (anti-cheat) may ignore or penalise them — outside our control and stated in the disclosure.
* `dispatchGesture` cancels the user's current touch: you cannot keep holding the zone while taps are injected;
  hence no hold-to-repeat.
* Multi-touch is limited to 10 simultaneous contacts; some games treat rapid multi-pointer DOWN events
  differently from human input.
* Overlays cannot appear over the lock screen, the system UI, or other accessibility overlays; Android may also
  refuse `TYPE_ACCESSIBILITY_OVERLAY` while a call screen is up (Android 16 restriction on enabling services during
  calls also applies).
* Side-loaded builds on Android 13+ must first allow "restricted settings" for the accessibility switch.
* Foldables / secondary displays: geometry is read for the default display; a trigger authored on the inner
  panel is hidden on the outer panel (`ASPECT_DIFFERS` warns, `ORIENTATION_MISMATCH` hides).
* Rooted or emulated input is neither required nor used. **Shizuku, ADB, USB/wireless debugging and shell
  `input` are not used and not required** — the only injection path is `AccessibilityService.dispatchGesture`.
* No `SYSTEM_ALERT_WINDOW`, no foreground service and no notification permission are needed for triggers: the
  overlay is `TYPE_ACCESSIBILITY_OVERLAY` owned by the bound service, whose lifecycle the system manages.
* An injected contact that lands inside the trigger area is delivered to the game only because the overlay is
  made untouchable during execution; if the system refuses the `updateViewLayout` (logged), such a target would
  be swallowed by the overlay — place targets outside the zone if you see that in logs.

---

## 11. Manual acceptance flow (spec §21)

1. Settings → Permission center → read the (updated) disclosure → agree → enable *MacroAndroid* in Android
   Accessibility settings → back in the app the checklist shows all four items satisfied.
2. Macros → *Trigger areas* → *New trigger area* → name it, pick the game → drag/resize the zone → tap the canvas
   three times to add targets → *Test here* shows three rings at the targets → *Save*.
3. Launch the game → the zone appears (outline visible if the setting is on) → tap inside → three taps hit the
   game → release → tap again after the cooldown → works again; tapping outside the zone does nothing.
   With a reaction delay of 200 ms the first tap lands visibly later; the per-point ▶ button in the editor taps
   just that point.
3b. Editor → *Adjust over game* → the game comes to the front with the full-screen editor → drag the zone and a
   marker → *Test* → *Done* → back in the app the editor shows the new positions.
4. Rotate the device → the zone disappears (orientation mismatch) and returns when rotated back.
5. Disable the trigger in the list → the zone disappears immediately; withdraw consent → same, and the checklist
   explains what is missing.

---

## 12. Build & verify

```
./gradlew :automation:engine:test --tests 'com.macroandroid.automation.trigger.*'
./gradlew :feature:trigger:testDebugUnitTest
./gradlew :core:database:testDebugUnitTest      # migration + repository
./gradlew detekt lintDebug assembleDebug checkDebugForbiddenPermissions
```

CI (`.github/workflows/ci.yml`) runs all of these; the final report cites the green run id.
