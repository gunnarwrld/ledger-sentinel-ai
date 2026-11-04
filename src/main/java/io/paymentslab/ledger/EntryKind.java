package io.paymentslab.ledger;

/** Why a journal entry exists. Must match the journal_entries.kind CHECK constraint. */
public enum EntryKind {
    FUNDING,
    TRANSFER,
    REVERSAL;

    /**
     * Whether an account with this status may take a posting of this kind. A correction may reach a
     * FROZEN account, because freezing the receiver is usually the first step of a fraud response
     * (ADR 0010). A CLOSED account never changes.
     */
    public boolean allowsPostingTo(String status) {
        return "ACTIVE".equals(status) || (this == REVERSAL && "FROZEN".equals(status));
    }
}
