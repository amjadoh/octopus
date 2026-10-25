# Multi-item runtime status — 2026-10-26

Implemented foundation: `PreparedBasketRecord.create` validates every plain canonical line and its quantity against the observed basket, rejects extra/missing/duplicate/unknown rows and modifiers, and requires `BasketEvidence.complete` for a multi-row record. Repeated identical canonical lines are combined by quantity. It retains the current single-row visible-only completeness flag; it does not upgrade branch/address certainty.

This is not multi-item automation. `PreviewEvidence.supports` and the Android provider executors still admit one plain item only. Do not relax that gate merely because the ledger now accepts complete multi-row observations. The existing generic `CartPreparationEngine`/`ComparisonCoordinator` have no implemented Android `ComparisonProviderPort`; unit ports are not live integrations.

Next concrete integration steps:

1. Read each selected plain item's product and unit price into a plan keyed by canonical item identity, while retaining the full draft fingerprint throughout. Never replace the draft with a single-item cart to make an old executor pass its guard.
2. Split Keeta's current-line reader context from the set of all permitted basket titles. The basket parser's multi-row support currently cannot recover titles omitted by the runtime functional-label filter. Do not make unknown modifier text acceptable in a product modal.
3. Implement ownership-bound cart preparation for both providers: observe the entire cart before every edit, permit only one pending action, and verify the resulting complete contents before advancing to the next item. Existing unrelated items require explicit approval; a timeout cannot repeat Add.
4. Replace the single-line/count assumptions in `prepareRidan`, `prepareComparedKeeta`, their quantity commands, and `ReviewNavigationGuard.target` with complete-cart evidence. Existing quantity commands and Ridan admission cannot safely be reused unchanged for an occupied multi-item cart.
5. Record and read the final review only after every requested line/options/quantity match. Bind its reading to the full cart fingerprint. Keep the pricing engine independent of providers.
6. Implement real address reference mapping and branch evidence before promoting actual readings to an equivalent cheapest ranking. Never substitute the selected public catalog city/district, a guessed branch, or an address alias such as Home for proof.
7. Only then enable multi-item comparison in the UI and test actual mixed-line baskets and idempotent retry on both apps. The successful Kudo single-item repetitions do not validate this path.

Verification: four new unit tests cover complete two-item matching, missing/extra/unknown rows, modifiers and size differences, duplicate rows, combined repeated lines, changed-request invalidation, and negative read amounts. The current suite has 361 passing tests. Physical testing remains scoped to the current single plain item unless a later report says otherwise.
