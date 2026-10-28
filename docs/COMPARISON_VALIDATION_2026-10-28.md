# Comparison validation — not a release approval

Device: Honor MBH-N49, Android 16. Providers: HungerStation 8.0.303 and Keeta 3.13.301.
Request: ALBAIK Big Baik ×2 and spicy chicken fillet burger ×2, without selected extras.

| Finding from inspection/report | Implementation/evidence | Remaining acceptance |
|---|---|---|
| Two-item comparison disabled on current Keeta | Version-specific admission and cart reader; repeated real two-row cart verified | Other versions stay unsupported until evidenced |
| HungerStation advert obscures search | Native explicit Braze close guard, plus optional Terra advice | Live advert dismissal through integrated client, cancellation and network loss |
| Big Baik search card is an ImageView | Card parser handles recorded structure; regression fixture | Full repeated comparison |
| Free options cannot be inferred from price | Structural unchecked-option proof; paid/free selection regression fixtures | Configured extras and all supported layouts |
| Burger free pickle option omitted | Recorded burger fixture and parser fix; incomplete groups fail closed instead of throwing; both items have now been added during isolated HS testing | Full cart acknowledgement and requested quantities |
| Existing-variant sheet appears after Add/back | Exact recorded sheet reader and scoped dismiss target reused in multi-item return and resume paths | Live recovery and final cart acknowledgement |
| Cart quantity Plus opens a variant selector instead of changing quantity | Owned command validates the same plain SKU, unit price and pre-edit quantity, issues one variant quantity command, then requires full cart re-observation | Live 1→2 burger quantity and subsequent repeat without another increment |
| List price is not checkout total | Keeta list subtotal 49.00 SAR; real verified review total 50.47 SAR in run 6 | HungerStation review total, same address/branch, freshness of both readings |
| AI account/setup absent | Real gpt-6.1-terra connectivity and synthetic recovery passed; Android USB gateway probe returned approved=true, executedClick=false | Production HTTPS hosting, opt-in/disconnect UI, live recovery acceptance |

551 Android unit tests passed, including the variant-quantity regression; diagnostic APK assembly and lint passed. Five gateway tests passed.
Run 7 read both provider menus at 49.00 SAR, but stopped before final review because the provider window check failed. That run is not a successful comparison.

The integrated AI client currently consults Terra only for an already native-validated explicit HungerStation advert close target. It sends public text from the advert dialog, not screenshots or account background. Android rechecks the complete frame before clicking. Missing/unavailable advice falls back only to the existing native close guard. This is not a general autonomous screen-navigation implementation.

Publication remains blocked on a complete two-provider run, repeat/no-duplicate tests, empty-cart recovery, configured extras, fresh equivalent final totals and handoff, plus production AI deployment and privacy controls. No order or payment has been submitted.
