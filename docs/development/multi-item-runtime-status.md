# Multi-item runtime status — latest checkpoint 2026-10-26 03:04 Riyadh

Both real provider baskets now contain Kudo medium fries x3 and nachos cheese x1, no additions, item subtotal 40 SAR each. The protected device command sequence ran the actual app navigators and displayed two fresh review totals inside Octopus: Keeta **41.20 SAR**, HungerStation **41.80 SAR**. No order/payment/COD/membership/points action occurred. This was not triggered by the main UI Compare button and is not a finished arbitrary-restaurant journey. See `docs/verification/2026-10-25/two-row-final-readings.json` and its actual timestamps/hash.

## Implemented and checked

- Full-request `PlainMenuReadPlan`: up to ten distinct plain lines, repeated canonical lines combined, every fresh unit price required; unknown/conflicting/stale/overflow values never produce a partial subtotal. Actual two-line menu subtotal 40 SAR read on both apps.
- `PlainBasketPreparationSession`: full request/version binding, complete plain known rows, one pending edit consumed before dispatch, and exact all-row quantities after it. No invented branch/address context. Unit ports alone are not Android integrations.
- Keeta 3.13.203: actual separately wrapped drawer rows and empty separators fully accounted for; requested labels restored only in the known row title slot, never a product modifier. Existing two-row cart and quantity 1->2->1 physically verified. The first development missing-row Add reached the cart before the older reader timed out; same-run successful fresh-Add acknowledgement remains a separate gap. No empty/scrolling cart support.
- HS 8.0.303: `HungerStationCompleteCartProof` binds one consumed MENU->CART command to the actual menu quantity/item amount and requires every positive-quantity parsed row, line sums and cart subtotal to account for them. A fresh source frame/version/30-second bound and one-use receipt prevent upgrading an arbitrary visible frame to complete. One Android Back is admitted only from the exact completely observed CART. The actual missing nachos row was added exactly once, all contents rechecked/acknowledged in that same run, and existing matching two-row carts read again without adding.
- Existing HS option/price/quantity Add checks remain intact. `RidanCartProgram.createForOwnedAddition` additionally requires the session-owned missing line and a menu footer still matching the whole observed basket; the old empty-cart factory remains strict.
- `PreparedReviewAdmission`: one navigation within 15 seconds, full request/version/complete record and actual source frame. HS must still be that exact cart frame; Keeta must retain the known menu/footer total quantity after closing its verified drawer. Existing review target structures are reused; confirmation/payment labels remain rejected. Stable explicit totals are read by the existing extractor.
- Normal one-item guard remains unchanged by default. Two-row paths are device-tools verification entries only: `prepare_multi_hs`, `prepare_multi_keeta`, `prepare_multi_hs_review`, `prepare_multi_keeta_review`. Current phone is this protected device-tools APK, not normal.
- Current suite: 387 unit tests pass, lint zero errors/five warnings. Local source and reports only; no push/publication.

## Actual display and limits

Both fresh totals were visible together in Octopus; screenshots were taken only inside Octopus, never on review/payment screens. A later reading naturally expires after the existing 120-second freshness period. Details are explicitly partial: HS extracted subtotal 40, a displayed delivery field 9 and service 1.80, but explicit total is 41.80. Do not invent a missing discount or treat those displayed components as a complete arithmetic breakdown. Keeta details are total-only. Address/branch equality and confirmed cheapest ranking remain unverified.

The original normal-APK five-run single-item report (fries x3 only, item subtotal 27, final 40.81/37.50) is historical and must not be used for this new request.

## Next implementation before enabling/releasing the full journey

1. Extract private reusable per-provider steps from the device verification wrappers into the actual in-app coordinator. Main UI Compare and the existing handoff handlers still admit one plain item. Do not merely change `PreviewEvidence.supports` or enable a button without replacing those downstream assumptions.
2. Implement empty first-line preparation while retaining the full request fingerprint; never swap the draft to a single-item cart. Use the existing strictly verified empty-menu/PDP/Add shapes, then complete contents before the next mutation. HS below-minimum states must not cause unauthorized quantity increases or repeated Add. Cart replacement needs an actual approval-bound known prompt; no silent clearing.
3. Add HS multi-row quantity commands bound to the complete owned basket and the selected row; its current driver only handles missing-row Add. Keeta fresh Add+complete acknowledgement still needs its own verified run.
4. Generalize both handoffs with fresh complete basket verification and re-read totals as needed. They currently reject two-row requests even though developer verification can prepare/read them.
5. Wire and test the real normal-APK Compare button on mixed rows, safe retry, new restaurant/empty cart and both handoffs, before publication. Full modifiers/sizes/notes and real address/branch reference mapping remain unsupported; do not fabricate contexts or broaden an unrelated parser permit.
