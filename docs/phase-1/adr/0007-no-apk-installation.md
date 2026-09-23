# ADR-0007 — APK installation and uninstallation are excluded from the product

- Status: Accepted (owner decision, 2026-09-23)

## Context
`PackageInstaller` with user confirmation is technically possible, but it requires `REQUEST_INSTALL_PACKAGES`, a special-access toggle, a Play "core functionality" justification that an automation tool is unlikely to satisfy, and it creates the most dangerous misuse path in the product (a macro that installs something). The owner prefers not to support it.

## Decision
- No `REQUEST_INSTALL_PACKAGES`/`REQUEST_DELETE_PACKAGES` permission, no `PackageInstaller` code, no action type, no UI affordance. The APK feature is strictly inventory + verification (metadata, checksums, duplicate/installed-state detection).
- The APK detail screen links to a Help section explaining the decision and pointing users to their file manager/system installer.
- A CI manifest check fails the build if either permission appears.
- Re-introduction requires a new ADR superseding this one plus a security and policy review.

## Consequences
- Simpler permission surface, cleaner Play review, one less abuse vector.
- Users who expected an installer are told plainly that this is not one.
