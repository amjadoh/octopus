# Native cart comparison — development branch

Branch: `feature/native-cart-compare`. Legacy checkpoint: `checkpoint-before-cart-import-20261030` (`01b053b`). Do not merge to main or publish an app update without acceptance and user approval.

## Implemented path

1. Home → «ابدأ من هنقرستيشن». Existing accessibility setup, provider allowlist and catalog consent apply.
2. User chooses the delivery address, restaurant and meals in the native HungerStation app. Browsing remains passive.
3. From the cart, the floating «قارن» button imports the visible basket only after a recent same-session restaurant menu footer accounts for all row quantities and line amounts. If needed, return to the restaurant menu and reopen the cart; the footer proof expires after 30 seconds.
4. Preserve the previous active local order under `before-native-import-<fingerprint>` before saving the imported request. Existing history is not deleted.
5. Verify the source cart has not changed and read its actual order-review total without rebuilding its contents. Then run the existing guarded Keeta preparation/review adapter.
6. Return automatically to the comparison screen on normal completion. Source/target errors remain explicit. Cancellation, provider foreground changes, protected screens and cart-journal safeguards remain active.

## Current limits

- Development admission supports one or two plain items, supported installed provider versions only. Options, sizes, entered notes, omitted text, incomplete rows and ambiguous duplicates are rejected rather than dropped or guessed.
- A previous, different Keeta basket is not cleared or replaced. Changing matching pre-existing quantities still needs the existing explicit consent.
- Item/subtotal completeness is not proof of delivery address, branch identity or final price. The user must check the two addresses/branches; the existing UI warning remains.
- Legacy unit tests and UI regression sources remain. `NativeCartImportTest` adds completeness, freshness, version, notes/options, quantity and continuity checks. Android UI tests cover the new entry button and preservation/concurrent replacement of local orders.
- App version/channel unchanged. Native HungerStation→Keeta end-to-end acceptance on the phone is still required. A successful build or emulator test does not establish that acceptance.

## Local evidence

Validation completed 2026-10-30: 564 unit tests passed, lint passed, diagnostic app and Android-test APK builds passed. On temporary `emulator-5564`, all 25 tests in ManualBrowseUiTest, CartManagementUiTest and CatalogSourceRegressionUiTest passed, including the new prior-order preservation/concurrent-replacement test. No phone/native provider acceptance is claimed. The temporary emulator was started read-only, without saving snapshots; the physical phone was not targeted for installation or cart operations.

Legacy source/history/test reports are in sibling `checkpoint-before-cart-import-20261030/`. New build logs and temporary-emulator results are saved separately under sibling `native-cart-compare-*` files. Phone screenshots, native carts, credentials and signing materials are not part of source commits.

Remote upload explicitly approved on 2026-10-30: both source branches and `checkpoint-before-cart-import-20261030` were pushed and independently verified with `ls-remote`. Remote main remains `f500983`. Draft PR #3 targets the preserved legacy branch to isolate the new-flow diff; no merge or release is authorized.

## Subsequent phone trial and remote checks (2026-10-30)

The user authorized replacing native baskets with a test basket and made the phone available. A signed diagnostic build was installed over the existing app without clearing its data. Prior local order preservation was verified. This trial followed the emulator-only validation above.

- HungerStation 8.0.303 / Keeta 3.13.301: the native entry and floating compare control were exercised on the physical phone.
- A source basket containing one plain Big Baik (17.50 SAR, no modifications) was imported successfully. Diagnostic state confirms restaurant, quantity 1, no options and no size.
- The source order-review screen then displayed a provider error that the branch does not deliver to the selected address. Comparison stopped with no accepted final total and before Keeta preparation. The amount behind that error was not accepted as a valid quote.
- Target filling and successful automatic return remain **unverified**. `nativeCartImportVerifiedOnPhone` remains false; this is not release acceptance.
- Screenshots, diagnostic state and device APK backup remain in private sibling `native-cart-phone-20261030/`; no addresses or payment details are included here.
- GitHub Actions run 41024347766 for commit `1c6211d` completed successfully: diagnostic build/unit/lint/UI checks and ai-gateway checks. No main merge or release was performed.
