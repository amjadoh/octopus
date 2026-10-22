# Offline continuation — 2026-10-22

## Distribution

The user requires private code and APKs. No public update repository or public download URL was created.
Settings now offers private GitHub release navigation plus importing a downloaded APK and opening Android's installer.
The APK must match the installed package and current signer set, and have a strictly higher versionCode.
APK import is limited to 100 MiB and uses app-private cache; only the verified-update cache directory is shared through FileProvider.
Android installation permission and confirmation remain user-controlled.

This is not the direct-download Alatheel update flow. Alatheel's checked-in updater uses a Firebase Hosting URL; no equivalent authenticated Octopus update endpoint is configured.
The private GitHub download is performed through the user's logged-in browser; the application contains no GitHub credential or repository token.

## Comparison evidence and limits

The stored Honor captures establish the Madwar feta-pie cart shape on HungerStation 8.0.303 and Keeta 3.13.203.
Three plain feta pies were observed at an item subtotal of 2,100 halala on each provider. These are historical observations, not current quotes.
HungerStation's stored review pricing contains delivery 2,000 halala and service 190 halala. No confirmed final order total was saved.
There is no saved Keeta final-review total. Branch/address equivalence is not established by these redacted captures.
Do not derive a final total from these partial fields, inject them into the live comparison UI, or rank a cheapest provider.

The UI separates item-price reading from sequential guarded cart preparation. The latter records per-provider failure or review-unavailable status and never manufactures a successful quote.
Grouped review field parsing now supports the observed merged fee-label/currency layout in SnapshotReader.
The Android adapter path for recognizing final reviews and validating both final totals is still incomplete; CartComparisonFlow explicitly reports REVIEW_UNAVAILABLE after successful preparation.
Current Keeta empty-cart product-option addition is still version-gated pending an evidenced complete path. Existing matching current-version drawers can be read, and the recorded quantity-control shape is supported.

## Release decision

Local validation completed: diagnostic APK assembly succeeded; 249 JVM unit tests passed with zero failures/errors, including signer/version policy, sequential preparation, cancellation, request changes and partial-fee parsing. The new installer UI and installation handoff were not tested on a device.

No real phone was operated during this offline continuation. Unit tests and APK assembly cannot establish the missing live final-review path.
Do not publish this work as a functioning end-to-end price-comparison release. No new release was created for this continuation.
