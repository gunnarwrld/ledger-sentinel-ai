-- Callers are authenticated API clients (synthetic customers or operators).
CREATE TABLE callers (
  id            uuid PRIMARY KEY,
  name          text NOT NULL UNIQUE,
  role          text NOT NULL CONSTRAINT callers_role_known CHECK (role IN ('CUSTOMER', 'OPERATOR')),
  api_key_hash  bytea NOT NULL UNIQUE,
  created_at    timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE accounts (
  id             uuid PRIMARY KEY,
  owner_id       uuid REFERENCES callers (id),
  kind           text NOT NULL CONSTRAINT accounts_kind_known CHECK (kind IN ('CUSTOMER', 'SYSTEM')),
  -- Must match SupportedCurrencies in the application.
  currency       char(3) NOT NULL CONSTRAINT accounts_currency_supported CHECK (currency IN ('EUR')),
  status         text NOT NULL DEFAULT 'ACTIVE'
                   CONSTRAINT accounts_status_known CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
  balance_minor  bigint NOT NULL DEFAULT 0,
  allow_negative boolean NOT NULL DEFAULT false,
  created_at     timestamptz NOT NULL DEFAULT now(),
  updated_at     timestamptz NOT NULL DEFAULT now(),
  -- Target for composite foreign keys (account_id, currency) from postings (Phase 2).
  UNIQUE (id, currency),
  -- Last line of defence for the no-overdraft rule; the application checks first, under a row lock.
  CONSTRAINT accounts_no_overdraft CHECK (allow_negative OR balance_minor >= 0),
  CONSTRAINT accounts_only_system_may_go_negative CHECK (NOT allow_negative OR kind = 'SYSTEM'),
  CONSTRAINT accounts_owner_matches_kind CHECK ((kind = 'SYSTEM') = (owner_id IS NULL))
);

CREATE INDEX accounts_owner_idx ON accounts (owner_id);
