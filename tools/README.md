# Local Android device tooling

These source sets are opt-in development tooling, not provider integrations or proof of a completed comparison.

- `-PoctopusLiveProbe=true` adds the structure-only `LiveProbeTest` to the test APK. It uses the normal JUnit application lifecycle, retains no arbitrary screen text, and refuses capture unless the requested provider has an active window on an unlocked device.
- `-PoctopusLiveBridge=true` adds `CatalogTestReceiver` to the debug APK and marks its version `-device-tools`. Its exported receiver requires Android's `DUMP` permission. It invokes only the existing finite catalog commands; existing consent, supported-version, cart and financial guards remain in force. The UI continues to use the same runtime.

The bridge writes its result to the app's own `files/live-device-result.json`. Restaurant/menu names are public catalog data; credentials, addresses, arbitrary screen text and payment fields are not exported. `finalTotalsVerified` remains false: a successful command or menu read must never be presented as a verified two-provider final quote.

For product inspection, the development bridge leaves the provider's product page in front after the ordinary read-only product navigation. Normal user navigation still returns to Octopus. This does not permit adding a product, clearing a basket or proceeding to review/payment.

Do not publish an APK built with the live bridge. The release APK validator rejects `CatalogTestReceiver`. Ordinary builds omit both opt-in source directories. Never commit device pairing codes, signing keys, account tokens or private device captures.
