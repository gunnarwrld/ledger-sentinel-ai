-- Who reversed a payment (threat T7). The operator belongs to the payment's own record, not only to the
-- idempotency snapshot, which is purged after 7 days. Existing rows aren't REVERSED, so the CHECK holds.
ALTER TABLE payments ADD COLUMN reversed_by uuid REFERENCES callers (id);
ALTER TABLE payments ADD CONSTRAINT payments_reverser_iff_reversed CHECK ((status = 'REVERSED') = (reversed_by IS NOT NULL));
