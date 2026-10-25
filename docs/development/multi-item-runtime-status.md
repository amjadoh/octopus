# Multi-item runtime status — 2026-10-26

Implemented ledger foundation: `PreparedBasketRecord.create` validates every plain canonical line and quantity against a complete basket observation, rejects missing/extra/duplicate/unknown rows and modifiers, and combines repeated identical canonical lines. This does not prove address or branch equivalence.

Implemented read-only Android phase: `PlainMenuReadPlan` retains the full request fingerprint, aggregates repeated plain lines, and requires a fresh validated unit price for every line before exposing an item subtotal. Unknown or conflicting amounts, changed requests/versions and integer overflow produce no subtotal. Up to ten distinct plain lines are admitted. The existing Android readers now visit each product without Add, quantity changes, promotions or checkout. The normal full Compare button still admits one plain item only; this phase is not multi-item preparation.

Physical two-line reading passed on Kudo: medium fries x3 plus nachos cheese x1, no additions, produced item subtotals of 40 SAR in both Keeta 3.13.203 and HungerStation 8.0.303. Neither final review total was read for this request and neither provider basket was modified by this path. See `docs/verification/2026-10-25/multi-item-menu-reading.json`, including the preceding failed development attempts. The exact observed Keeta public product_name field and informational allergen heading are retained; unknown modifier text remains rejected. Menu scanning is finite (80 forward viewports, 60 rewind); a visible target without a supported opener stops.

Remaining actual integration:

1. Persist per-line configured-product evidence separately from menu item prices. Keep full-request identity throughout; do not swap the draft to a one-item cart.
2. Split current-line context from all permitted cart titles. Runtime capture currently omits other basket item labels; parser unit tests alone do not prove complete Android observations.
3. Implement ownership-bound preparation on both platforms: complete cart observation before each edit, one pending action, post-action reconciliation, and no repeated Add after a timeout. Unrelated baskets need explicit user approval.
4. Replace single-line assumptions in `prepareRidan`, `prepareComparedKeeta`, quantity commands and `ReviewNavigationGuard.target` only when full-cart executors exist. Do not relax `PreviewEvidence.supports` early.
5. Read final review totals only after every line/option/quantity is verified, binding readings to the full fingerprint. Menu subtotals cannot substitute for checkout totals.
6. Implement actual address-reference mapping and branch evidence. Public city/district, guessed branches or an address alias such as Home cannot establish equality.
7. Enable full multi-item UI comparison only after real mixed-line preparation, reconciliation/retry and handoff verification on both providers.

The generic `CartPreparationEngine`/`ComparisonCoordinator` ports are not implemented Android integrations. Current suite: 369 passing unit tests, zero lint errors, five warnings. Prior five normal-APK single-item runs validate repeated readings on one existing matching basket pair, not arbitrary restaurants or fresh mixed-cart builds. No publication.

## Keeta ownership integration checkpoint — 2026-10-26 02:24 Riyadh

`PlainBasketPreparationSession` now owns complete plain-row content transitions, checks full request/version, consumes one pending add or quantity change before dispatch, and checks all rows after it. It does not manufacture branch/address context. The Android Keeta driver is reachable only in device-tools through the DUMP-protected `prepare_multi_keeta` command; normal full Compare remains single-item. It admits exactly two distinct plain lines, needs an existing complete visible basket, and never clears or removes the last row.

The actual 3.13.203 drawer uses one section per row, with an optional empty separator. The reader now accounts for every section and child. Requested titles are restored only at the first text slot of a known drawer card; an extra name in a product or modifier slot cannot become a plain-item permit. A redacted actual two-row TSV captures this structure.

Actual Keeta cart: Kudo fries x3 + nachos cheese x1, no additions, item subtotal 40 SAR. The latest code retained this existing basket without another Add and changed nachos 1->2->1 while verifying fries remained x3 and every row matched. The first development Add reached the basket but the old reader timed out before same-run acknowledgement. Do not claim the fresh-add path is fully verified or that these are checkout totals. Report: `docs/verification/2026-10-25/keeta-owned-two-row-preparation.json`. Unit suite now 379 passing; lint zero errors/five warnings.

Current phone build is device-tools, not normal. Draft is the two-row request; Keeta has both rows, HungerStation only fries x3. Do not run the old single-item golden case on these carts or trust its previous totals for the new request. Prior normal-APK report remains valid only for its original request and build. Next: implement real HS complete basket observation and an ownership-bound missing-row add, followed by mixed-row quantity controls and complete review admission. Do not merely remove the one-item guard. Keep all changes local.
