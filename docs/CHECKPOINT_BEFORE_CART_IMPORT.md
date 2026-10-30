# Legacy flow checkpoint — 2026-10-30

Preserved on `fix/cart-total-reading`, tagged `checkpoint-before-cart-import-20261030`.
This is a development checkpoint, not an accepted release.

- Includes the complete existing source, unit/UI test sources, and six pending reading-fix changes.
- Previous recorded validation (2026-10-29): 557 unit tests and lint passed; new Android UI regression compiled but was not executed.
- Physical acceptance remained incomplete: Keeta full-cart identity rejected; HungerStation historical-cart guard stopped an unverified first add. These safeguards remain required.
- Local backup: sibling `checkpoint-before-cart-import-20261030/`, containing tracked source/test ZIP, binary patch, existing test results/reports, SHA-256 inventory, and the previous checkpoint including private evidence. Private evidence stays local.

To resume the legacy implementation, switch to `fix/cart-total-reading` or create a branch from the tag. Save any new-branch work before switching. Existing device data/native carts are independent of Git and are not rolled back by switching branches.

New work belongs to `feature/native-cart-compare`. Do not merge into `main` without the user's approval and relevant verification. No publication is authorized by this checkpoint.
