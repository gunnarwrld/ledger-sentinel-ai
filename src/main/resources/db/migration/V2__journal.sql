-- Double-entry journal (DESIGN §9, ADR 0005). Append-only: mistakes are corrected with a new
-- REVERSAL entry, never by editing history.
CREATE TABLE journal_entries (
  id          uuid PRIMARY KEY,
  kind        text NOT NULL
                CONSTRAINT journal_entries_kind_known CHECK (kind IN ('FUNDING', 'TRANSFER', 'REVERSAL')),
  currency    char(3) NOT NULL CONSTRAINT journal_entries_currency_supported CHECK (currency IN ('EUR')),
  -- UNIQUE: an entry can be reversed at most once.
  reverses_id uuid UNIQUE REFERENCES journal_entries (id),
  created_at  timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT journal_entries_reversal_names_target CHECK ((kind = 'REVERSAL') = (reverses_id IS NOT NULL))
);

CREATE TABLE postings (
  id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  journal_entry_id uuid NOT NULL REFERENCES journal_entries (id),
  account_id       uuid NOT NULL,
  currency         char(3) NOT NULL,
  -- Signed (ADR 0005): positive is a credit, negative is a debit.
  amount_minor     bigint NOT NULL CONSTRAINT postings_amount_nonzero CHECK (amount_minor <> 0),
  -- I3: a posting is always in its account's currency.
  CONSTRAINT postings_account_currency_fk FOREIGN KEY (account_id, currency) REFERENCES accounts (id, currency)
);

CREATE INDEX postings_account_idx ON postings (account_id, id);
CREATE INDEX postings_entry_idx ON postings (journal_entry_id);

-- I5: every entry has at least two postings, all in the entry's currency, summing to zero.
-- Checked at COMMIT (deferred), so an entry and its postings can be inserted in any order.
CREATE FUNCTION ledger_assert_entry_balanced(entry_id uuid) RETURNS void
LANGUAGE plpgsql AS $$
DECLARE
  entry_currency char(3);
  posting_count  bigint;
  total          numeric;
  wrong_currency bigint;
BEGIN
  SELECT e.currency INTO entry_currency FROM journal_entries e WHERE e.id = entry_id;
  SELECT count(*), coalesce(sum(p.amount_minor), 0), count(*) FILTER (WHERE p.currency <> entry_currency)
    INTO posting_count, total, wrong_currency
    FROM postings p
   WHERE p.journal_entry_id = entry_id;
  IF posting_count < 2 OR total <> 0 OR wrong_currency > 0 THEN
    RAISE EXCEPTION 'journal entry % is not balanced (% postings, sum %, % in another currency)',
      entry_id, posting_count, total, wrong_currency
      USING ERRCODE = 'check_violation';
  END IF;
END
$$;

CREATE FUNCTION ledger_check_posting_entry() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  PERFORM ledger_assert_entry_balanced(NEW.journal_entry_id);
  RETURN NULL;
END
$$;

CREATE FUNCTION ledger_check_new_entry() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  PERFORM ledger_assert_entry_balanced(NEW.id);
  RETURN NULL;
END
$$;

CREATE CONSTRAINT TRIGGER postings_entry_balanced
  AFTER INSERT ON postings
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION ledger_check_posting_entry();

-- Also fires for entries that never get a posting.
CREATE CONSTRAINT TRIGGER journal_entries_balanced
  AFTER INSERT ON journal_entries
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION ledger_check_new_entry();

-- I9: append-only. Phase 5 also removes UPDATE and DELETE privileges from the application role.
CREATE FUNCTION ledger_reject_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION '% is append-only; correct mistakes with a REVERSAL entry', TG_TABLE_NAME
    USING ERRCODE = 'insufficient_privilege';
END
$$;

CREATE TRIGGER journal_entries_append_only
  BEFORE UPDATE OR DELETE ON journal_entries
  FOR EACH ROW EXECUTE FUNCTION ledger_reject_change();

CREATE TRIGGER journal_entries_no_truncate
  BEFORE TRUNCATE ON journal_entries
  FOR EACH STATEMENT EXECUTE FUNCTION ledger_reject_change();

CREATE TRIGGER postings_append_only
  BEFORE UPDATE OR DELETE ON postings
  FOR EACH ROW EXECUTE FUNCTION ledger_reject_change();

CREATE TRIGGER postings_no_truncate
  BEFORE TRUNCATE ON postings
  FOR EACH STATEMENT EXECUTE FUNCTION ledger_reject_change();
