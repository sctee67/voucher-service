# voucher-service

Internal campaign voucher redemption service.

Clients run promotional campaigns; each campaign has a fixed stock of vouchers.
End users redeem a voucher code via the storefront, which calls this service.

## Running

```
./gradlew bootRun
```

H2 in-memory DB, seeded from `src/main/resources/schema.sql` + `data.sql` on startup.

## Endpoints

| Method | Path | Notes |
|---|---|---|
| POST | `/vouchers/{code}/redeem?userId=...` | Redeem a voucher |
| POST | `/vouchers/{code}/void` | Mark a voucher void |
| GET | `/campaigns/{id}/stats` | Stock + redemption counts |
| GET | `/campaigns/by-client/{clientCode}/stats` | Same, per client |

## Per-user redemption limit

Each campaign has `max_redemptions_per_user` (default `2`). A user who already holds
that many `REDEEMED` vouchers in the campaign gets:

```json
{"result":"FAILED","message":"User has reached the redemption limit for this campaign (2)"}
```

Voided vouchers do not count toward the limit. A blank `userId` is rejected.

## Sample data

Campaign 1 (`ACME`, "Raya Flash Deal") — vouchers `RAYA-0001` … `RAYA-0006`
Campaign 2 (`NOVA`, "Merdeka Giveaway") — vouchers `MRDK-0001`, `MRDK-0002`
Campaign 3 (`ACME`) — inactive, no stock

`user-99` already has redemption history on campaign 1.

## Tests

```
./gradlew test
```
