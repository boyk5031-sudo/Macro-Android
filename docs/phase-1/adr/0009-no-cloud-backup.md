# ADR-0009 — `allowBackup=false`; explicit export/import is the migration path

- Status: Accepted

## Context
Auto Backup / device-to-device transfer would copy the Room database but not the Keystore keys, leaving `secure_values` undecryptable; persisted SAF URI grants are also device-specific; WorkManager's DB must not be restored into a fresh install.

## Decision
`android:allowBackup="false"` on `<application>`. The Settings screen offers "Export all macros and schedules" and Help explains that sensitive values must be re-entered or explicitly included in the export.

## Consequences
- No silent, partially broken restores. Users have a clear, user-controlled migration path.
