-- Idempotency keys (ADR 0007, DESIGN §6). The row is inserted as the first statement of the business
-- transaction and gets its response in that same transaction. A concurrent duplicate's INSERT waits
-- on the primary key until the first transaction ends: that wait is the "in progress" state.
CREATE TABLE idempotency_keys (
  caller_id       uuid NOT NULL REFERENCES callers (id),
  operation       text NOT NULL CONSTRAINT idempotency_keys_operation_format CHECK (operation ~ '^[a-z]+\.[a-z]+$'),
  idempotency_key text NOT NULL CONSTRAINT idempotency_keys_key_format CHECK (idempotency_key ~ '^[!-~]{8,255}$'),
  -- SHA-256 of the canonical request, to tell a retry from a different request under the same key.
  request_hash    bytea NOT NULL CONSTRAINT idempotency_keys_hash_is_sha256 CHECK (length(request_hash) = 32),
  response_status int CONSTRAINT idempotency_keys_status_range CHECK (response_status BETWEEN 200 AND 599),
  -- text, not jsonb: jsonb reorders keys and drops whitespace, and replays must be byte-for-byte identical.
  response_body   text,
  resource_id     uuid,
  created_at      timestamptz NOT NULL DEFAULT now(),
  -- Snapshots are purged after this (job planned for Phase 5).
  expires_at      timestamptz NOT NULL DEFAULT now() + interval '7 days',
  PRIMARY KEY (caller_id, operation, idempotency_key),
  CONSTRAINT idempotency_keys_response_complete CHECK ((response_status IS NULL) = (response_body IS NULL))
);

-- At COMMIT, every key row inserted by the transaction must carry its response. Other sessions can
-- therefore never see a key without one: no half-finished state exists outside the transaction.
CREATE FUNCTION idempotency_require_response() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF EXISTS (SELECT 1 FROM idempotency_keys
             WHERE caller_id = NEW.caller_id
               AND operation = NEW.operation
               AND idempotency_key = NEW.idempotency_key
               AND response_status IS NULL) THEN
    RAISE EXCEPTION 'an idempotency key row is being committed without a response'
      USING ERRCODE = 'check_violation';
  END IF;
  RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER idempotency_keys_have_response
  AFTER INSERT ON idempotency_keys DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION idempotency_require_response();
