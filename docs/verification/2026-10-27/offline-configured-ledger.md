# Configured basket evidence — offline checkpoint

Phone access was stopped at the user's request. No device commands, installation, push, or publication in this task.

## Changes
- PreparedBasketRecord accepts fully observed configured variants only when BasketItemMatcher verifies restaurant, size, selected option values and multiplicity, row quantities, and no extra rows.
- Complete records still require every row amount and an exactly reconciled subtotal. Missing option quantities, notes, ambiguous duplicate variants, and incomplete observations fail closed.
- Plain historical single-row partial records retain their original partial status.
- PreparedReviewAdmission explicitly retains plain-only admission independently of the expanded evidence ledger.
- Added regression coverage for variant separation, changed choices, missing/extra choices, quantity mismatch, duplicate rows, notes, and review admission. Existing reduced HungerStation size/rice replay now exercises the ledger.

## Scope
This is evidence/accounting support, not selected-option automation. Main comparison admission and provider product executors have not been broadened. No claim of arbitrary restaurants, options, or both-empty configured cart preparation. Tests using constructed frames are not fresh phone evidence.

## Remaining
Implement provider-specific configured product selection and complete configured cart observation from sufficient recorded structures; do not infer Keeta option multiplicity from an unlabeled price or omit unknown modifiers. Then verify on a reconnected phone: both empty carts, full selected options, retry without duplication, fresh totals, and both handoffs. No order/payment action is authorized.

## Validation
Final offline build succeeded: 535 unit tests, zero failures/errors/skips. APK and instrumentation test APK assembled; instrumentation tests were not run. Lint completed. No phone used and no release published.
