# NOTES

## What I changed and why

**Model.** I added `campaign.max_redemptions_per_user INT NOT NULL DEFAULT 2 CHECK (>= 1)`. The column default means existing campaigns get `2` without a data backfill. The limit is a per-campaign column because the brief says *each campaign* defines it.

**Enforcement** is in `VoucherService.redeem()`. The service counts the user's vouchers in the campaign with status `REDEEMED`. If that count is already at the limit, it returns `FAILED` with the message `User has reached the redemption limit for this campaign (N)`. A rejected attempt consumes neither the voucher nor stock. I kept the existing response shape (HTTP 200 with `result: FAILED`) so the storefront contract doesn't change.

**Voided redemptions do not count.** This is the main judgement call. Seed user `user-99` has two redemptions on campaign 1, and one of them (`RAYA-0005`) is `VOID`. With a limit of 2, counting voided ones would mean user-99 is already blocked. I treated void as "this redemption was cancelled", so user-99 can redeem one more. **I'd confirm this with the product owner.** If void is used for fraud, the opposite rule may be wanted. That would be a one-line change: count `redemption` rows instead of `REDEEMED` vouchers.

**Concurrency (most of the actual work).** `redeem()` had no transaction and no locking. My first version just added the limit check, and a concurrent test showed 4 parallel requests from one user all succeeding against a limit of 2. Now:
- `redeem()` is `@Transactional`. It takes `SELECT ... FOR UPDATE` locks on the voucher row, then the campaign row, always in that order to avoid deadlocks.
- The campaign row lock serializes redemptions within a campaign. That makes the per-user count, the stock decrement, and the "already redeemed" check correct. The lock is in the DB, so it also holds across app instances that share a database.
- This also fixes two existing races: lost updates on `remaining_stock`, and the same voucher being redeemed twice concurrently. Both now have tests.
- The audit HTTP call moved to `afterCommit`. That way no audit event is sent for a rolled-back redemption, and no row lock is held across an HTTP call that has no timeout.

**Smaller things:**
- A blank `userId` is rejected. Otherwise every anonymous call would share one limit bucket.
- Tests now reset the DB before each test (`@Sql`). The existing tests mutated shared H2 state and depended on run order.
- I added `.gitattributes` so `gradlew` keeps LF endings.

**Production migration.** `schema.sql` drops and recreates tables, so it isn't a migration. Against a real DB this would be:
```sql
ALTER TABLE campaign ADD COLUMN max_redemptions_per_user INT NOT NULL DEFAULT 2;
ALTER TABLE campaign ADD CONSTRAINT chk_max_redemptions_per_user CHECK (max_redemptions_per_user >= 1);
CREATE INDEX idx_voucher_campaign_redeemed_by ON voucher (campaign_id, redeemed_by);
```

## Noticed but deliberately did not change

- **`schema.sql` starts with `DROP TABLE` and `spring.sql.init.mode: always`.** Pointed at a real database, every restart wipes all data. This is the most dangerous thing in the repo, but fixing it properly means introducing Flyway/Liquibase, which is out of scope here.
- **Hard-coded audit API key** in `AuditClient`, sent in the request body. It should come from config/secrets and be rotated, since it's now in git history. I left it because it's unrelated to the feature, but it should be raised.
- **`AuditClient` uses a `RestTemplate` with no timeouts**, and failures are logged without the exception. It's fire-and-forget, so audit events are silently lost. An outbox table would make this reliable.
- **`voidVoucher`** doesn't check the current status, doesn't restore stock, isn't transactional, and has no authorization. I didn't change the void semantics because the business rule is unclear, and my limit rule depends on that answer.
- **Stats**: `redeemedCount` counts any voucher with a redemption row, so it includes voided ones. That's inconsistent with my limit rule; the smoke test showed 5 where 4 were effectively redeemed. It also does an N+1 query per voucher, and returns a 500 (`.get()` on an empty Optional) for an unknown campaign id.
- **Seed data inconsistency**: campaign 1 has `remaining_stock = 100` despite two prior redemptions, and `total_stock = 100` with only 6 vouchers.
- **All failures return HTTP 200** with a free-text message. Clients presumably match on strings. A machine-readable `reason` code (and 4xx/409 statuses) would be better, but that's an API contract change for the storefront.
- **No unique constraint on `voucher.code`** (`findByCode` would throw on a duplicate). `userId` isn't normalised (case/whitespace) or length-checked: the column is `VARCHAR(80)`, so a longer id causes a 500.
- Mixed field and constructor injection, string status constants, `java.util.Date`. Style only, so I left them.

## What I'd do next with another day

1. Confirm the void rule, then make void consistent: restore stock or not, and make stats agree with the limit.
2. Replace `schema.sql` with Flyway migrations, including the `ALTER` above.
3. Add a machine-readable `reason` field (e.g. `USER_LIMIT_REACHED`) to `RedeemResponse`.
4. **Hot-row contention**: every redemption in a campaign serializes on one campaign row. That's fine at this scale, but a flash sale would queue behind it. Options are a per-(campaign, user) counter row with a conditional update, or an atomic `UPDATE campaign SET remaining_stock = remaining_stock - 1 WHERE id = ? AND remaining_stock > 0`.
5. Idempotency key on redeem, so a storefront/LB retry after a timeout doesn't produce "already redeemed" for a user whose redemption actually succeeded.
6. Audit via an outbox; timeouts and secrets out of code.
7. Run the test suite against Postgres via Testcontainers, since H2 lock semantics aren't identical.

## Reflection

> _TODO (candidate): write these in your own words. The bullets are facts from the session you can draw on, not answers._

**What did you get wrong first, and how did you notice?**
_TODO_. Facts: the first version was a plain check-then-act limit check. The concurrency test (`concurrentRedemptionsBySameUserCannotExceedLimit`) failed with `expected: <2> but was: <4>`, which is visible in commit `Add per-user redemption limit ... (check only, not yet race-safe)`.

**Which AI suggestion did you reject, and why?**
_TODO_.

**What took you longest?**
_TODO_.
