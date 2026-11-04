-- One row per transfer that passed validation, posted or not: a decline is a stored outcome (ADR 0009).
CREATE TABLE payments (
  id                uuid PRIMARY KEY,
  caller_id         uuid NOT NULL REFERENCES callers (id),
  -- Permanent backstop for I11: idempotency snapshots expire after 7 days, this uniqueness never does.
  idempotency_key   text NOT NULL,
  source_account_id uuid NOT NULL,
  dest_account_id   uuid NOT NULL,
  currency          char(3) NOT NULL CONSTRAINT payments_currency_supported CHECK (currency IN ('EUR')),
  amount_minor      bigint NOT NULL CONSTRAINT payments_amount_range CHECK (amount_minor BETWEEN 1 AND 100000000),
  -- Free text from the client. Synthetic only, never logged.
  reference         text CONSTRAINT payments_reference_length CHECK (char_length(reference) <= 140),
  status            text NOT NULL
                      CONSTRAINT payments_status_known CHECK (status IN ('POSTED', 'DECLINED', 'REVIEW_REQUIRED', 'REVERSED')),
  decline_reason    text CONSTRAINT payments_decline_reason_known
                      CHECK (decline_reason IN ('INSUFFICIENT_FUNDS', 'ACCOUNT_NOT_ACTIVE', 'RISK_DECLINED')),
  journal_entry_id  uuid UNIQUE REFERENCES journal_entries (id),
  reversal_entry_id uuid UNIQUE REFERENCES journal_entries (id),
  created_at        timestamptz NOT NULL DEFAULT now(),
  updated_at        timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT payments_one_per_key UNIQUE (caller_id, idempotency_key),
  -- I3: the payment's currency is the currency of both accounts.
  CONSTRAINT payments_source_currency_fk FOREIGN KEY (source_account_id, currency) REFERENCES accounts (id, currency),
  CONSTRAINT payments_dest_currency_fk FOREIGN KEY (dest_account_id, currency) REFERENCES accounts (id, currency),
  CONSTRAINT payments_distinct_accounts CHECK (source_account_id <> dest_account_id),
  -- I12: only POSTED and REVERSED payments moved money, so only they have a journal entry.
  CONSTRAINT payments_entry_iff_money_moved CHECK ((status IN ('POSTED', 'REVERSED')) = (journal_entry_id IS NOT NULL)),
  CONSTRAINT payments_reversal_iff_reversed CHECK ((status = 'REVERSED') = (reversal_entry_id IS NOT NULL)),
  CONSTRAINT payments_reason_iff_declined CHECK ((status = 'DECLINED') = (decline_reason IS NOT NULL))
);

CREATE INDEX payments_source_created_idx ON payments (source_account_id, created_at);

-- Transactional outbox (I13): each committed state change writes one event in the same transaction.
-- The relay that publishes them arrives in Phase 3; until then they stay PENDING.
CREATE TABLE outbox_events (
  id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  event_id       uuid NOT NULL UNIQUE,
  aggregate_type text NOT NULL,
  aggregate_id   uuid NOT NULL,
  event_type     text NOT NULL CONSTRAINT outbox_events_type_format CHECK (event_type ~ '^[a-z]+\.[a-z]+$'),
  payload        jsonb NOT NULL,
  created_at     timestamptz NOT NULL DEFAULT now(),
  available_at   timestamptz NOT NULL DEFAULT now(),
  attempts       int NOT NULL DEFAULT 0,
  last_error     text,
  status         text NOT NULL DEFAULT 'PENDING' CONSTRAINT outbox_events_status_known CHECK (status IN ('PENDING', 'PUBLISHED', 'DEAD')),
  published_at   timestamptz
);

CREATE INDEX outbox_pending_idx ON outbox_events (available_at, id) WHERE status = 'PENDING';
CREATE INDEX outbox_aggregate_idx ON outbox_events (aggregate_id);
