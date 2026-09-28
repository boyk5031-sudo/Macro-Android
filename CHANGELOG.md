# Changelog

## 1.0.0 (unreleased)

First release. Scope per `docs/phase-0-feasibility-and-constraints.md` (MVP column).

- Apps: installed-app list with search/sort/favourites, detail with launch and pinned shortcuts.
- APK files: SAF import (multi-select), metadata, permissions, SHA-256 of file and signer, duplicate detection,
  optional local copies, share/re-verify/delete. No installation.
- Macros: typed step editor (15 actions, conditions, variables, secure values), validation, sentence preview,
  step testing, JSON v1 import/export with migrations and redaction.
- Execution: engine with sequential/parallel steps, UI lock, timeouts, retries, pause/resume/cancel, persistence,
  process-death recovery, notifications, history with logs and text export.
- Scheduling: one-time / interval / daily(weekdays) via WorkManager, constraints, missed-run policies, boot
  reconciliation, next-run preview.
- Settings: theme, execution/retention/privacy options, permission center, accessibility disclosure and consent
  with versioning and withdrawal, audit log, help, diagnostics export, onboarding.
- Security: no INTERNET; Keystore AES-GCM for sensitive values; no backup; forbidden-permission build check.
- Trigger Areas (Phase 12): a screen zone that, when tapped in a game, injects taps to user-placed target points
  (sequential or genuine multi-touch) through the accessibility service — no root, no new permissions. Editor
  with canvas + numeric fields, per-app binding or manual arming, cooldown/repeat, in-editor test with landing
  proof, SAF import/export, Room v2 (`trigger_configs`), accessibility disclosure v2. See
  `docs/phase-12-trigger-areas.md`.
- Trigger Areas: configurable trigger reaction delay (0–5000 ms with presets), per-target test button, explicit
  trigger state machine, on-screen edit mode over the game (drag area/points, add/delete/toggle, test, done),
  and a fix so injected taps whose target lies inside the zone reach the game (overlay untouchable during
  execution).
- Trigger Areas input architecture: zone windows now set `FLAG_SPLIT_TOUCH` (a second finger is split to the
  zone while the game holds the first); touch handling split into view (translation only) → `PointerTracker`
  (pointer-id based DOWN/POINTER_DOWN/MOVE/POINTER_UP/UP/CANCEL) → controller; new untouchable debug read-out
  (Settings) showing pointer id/index/coordinates/action and trigger state; the platform limitation of
  `dispatchGesture` (cancels / is cancelled by concurrent real touches) is documented in the editor, the log and
  `docs/phase-12-trigger-areas.md` §10a. Editor: Save is no longer greyed out on a new trigger — it explains what
  is missing and flags the name field.
