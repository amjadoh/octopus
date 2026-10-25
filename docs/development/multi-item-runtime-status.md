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
