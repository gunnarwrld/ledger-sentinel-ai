# ADR 0005: Signed postings (credits positive, debits negative)

- **Status:** Accepted. **Decided by the student (D8), 2026-10-29**, choosing Claude's proposal.
  The student's rationale (2026-11-03) is in `PROGRESS.md`, in their own words.
- **Date:** 2026-10-29

## Context

A double-entry journal needs a representation for debits and credits that keeps "every entry
balances" easy to enforce and easy to explain.

## Proposal

Each posting has one signed `amount_minor`: **credit = positive, debit = negative**, the same rule for
every account. "Total debits equal total credits" then becomes "an entry's postings sum to zero",
which a deferred constraint trigger can check at commit (DESIGN §9).

| Event | Postings |
|---|---|
| Fund a customer with 100.00 | SYSTEM_FUNDING −10000, customer +10000 |
| Customer A pays B 25.00 | A −2500, B +2500 |
| Reverse that payment | B −2500, A +2500 (new entry, references the original) |

Mapping to accounting terms, from the bank's side: customer accounts are liabilities (the bank
owes the customer), so their normal balance is a credit and they show as positive. The SYSTEM
funding account plays the asset side that backs those deposits; its normal balance is a debit, so
under this convention it shows as negative. Its negative balance equals the synthetic money issued,
which makes conservation, Σ balances = 0, easy to check.

## Alternatives

| Option | Trade-off |
|---|---|
| `direction` (DEBIT/CREDIT) + positive amount | Closer to accounting vocabulary; balancing needs a CASE in every sum. |
| Separate `debits_posted` / `credits_posted` totals per account | Rich audit view (used by some ledger databases); more columns to keep consistent. |

## Explain-back (student)

- Can you explain a transfer in debit/credit terms using the table above?
  **Student's answer (2026-11-03):** "For a EUR 25 transfer from A to B, post -2500 cents to A and +2500 cents to B. From the platform's perspective, customer accounts are liabilities: debit A reduces what we owe A, and credit B increases what we owe B."
- Is "asset accounts show negative balances" acceptable to your target interviewers, or do you prefer the direction column?
