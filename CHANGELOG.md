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
