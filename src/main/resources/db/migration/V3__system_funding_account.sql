-- The SYSTEM account that issues synthetic money (ADR 0005). Its balance goes negative by exactly
-- the amount issued, which keeps the sum of all balances at zero. The fixed id is
-- SystemAccounts.FUNDING in the application.
INSERT INTO accounts (id, owner_id, kind, currency, allow_negative)
VALUES ('00000000-0000-0000-0000-000000000001', NULL, 'SYSTEM', 'EUR', true);
