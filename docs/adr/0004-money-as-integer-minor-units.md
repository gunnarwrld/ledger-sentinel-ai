# ADR 0004: Money as integer minor units with checked arithmetic

- **Status:** Proposed by Claude, implemented in Slice 1.1. The student proposes to accept it, pending final
  confirmation (2026-11-03); their reasoning is in `PROGRESS.md`, in their own words.
- **Date:** 2026-10-29

## Context

Every invariant in the ledger (balanced entries, conservation, no overdraft) depends on exact
arithmetic. Errors must fail loudly, never round or wrap.

## Decision

- `Money(long minor, Currency currency)`: an exact count of minor units (cents for EUR).
- `plus`, `minus` and `negate` use `Math.addExact`, `subtractExact` and `negateExact`. Overflow throws
  `ArithmeticException`. Mixing currencies throws `CurrencyMismatchException`.
- `Money` may be negative (SYSTEM accounts go negative). Rules about what is *allowed* live
  elsewhere: `SupportedCurrencies` (only EUR, exact case-sensitive code) and `TransferAmount`
  (1 to 100,000,000 minor units).
- The database stores `bigint` minor units and its CHECK constraints repeat the rules (DESIGN §5).
- JSON carries integers (`amountMinor`); the limit keeps values far below 2^53, so JavaScript clients don't lose precision.

## Alternatives considered

| Option | Why not |
|---|---|
| `double` | Binary floating point can't represent 0.10 exactly. Never for money. |
| `BigDecimal` | Exact, but scale makes `equals` subtle (`2.0` ≠ `2.00`), and it invites fractional cents. Unbounded size hides overflow-type bugs instead of failing. |
| JSR 354 (Moneta) / Joda-Money | Solid libraries, but a dependency to learn and defend for a single-currency system. |
| Amounts as JSON strings | Safer for any client, but more parsing for no gain within our limit. |

## Consequences

- Currencies without 2 decimal places (JPY 0, BHD 3) need care if multi-currency ever arrives.
  The minor-unit count stays exact either way.
- Verified by `MoneySpec`, `SupportedCurrenciesSpec`, `TransferAmountSpec` and the CHECK cases in `MigrationSpec`.

## Explain-back (student)

- Why does `Money.of(Long.MIN_VALUE, EUR).negate()` throw?
- Why is "is this amount allowed?" kept out of `Money` itself?
