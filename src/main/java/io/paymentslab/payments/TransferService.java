package io.paymentslab.payments;

import static io.paymentslab.db.Tables.PAYMENTS;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.paymentslab.db.tables.records.PaymentsRecord;
import io.paymentslab.ledger.AccountNotActiveException;
import io.paymentslab.ledger.AccountQueries;
import io.paymentslab.ledger.EntryKind;
import io.paymentslab.ledger.InsufficientFundsException;
import io.paymentslab.ledger.JournalEntryRequest;
import io.paymentslab.ledger.LedgerService;
import io.paymentslab.ledger.Posting;
import io.paymentslab.money.Money;
import io.paymentslab.outbox.OutboxWriter;
import io.paymentslab.platform.FaultInjector;
import java.io.UncheckedIOException;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

/**
 * Creates transfers (DESIGN §3.2) inside the caller's transaction, which also holds the idempotency
 * key. Locks are taken in a fixed order: the key first, then both accounts in id order (in
 * {@link LedgerService}), so neither duplicates nor opposite transfers can deadlock.
 */
public final class TransferService {

    /** Fault point after every write of a transfer, just before commit (crash point C1). */
    public static final String BEFORE_COMMIT = "transfer.before-commit";
    /** Fault point after every write of a reversal, just before commit. */
    public static final String REVERSAL_BEFORE_COMMIT = "reversal.before-commit";

    private final LedgerService ledger;
    private final AccountQueries accounts;
    private final OutboxWriter outbox;
    private final FaultInjector faults;
    private final ObjectMapper json = new ObjectMapper();

    public TransferService(LedgerService ledger, AccountQueries accounts, OutboxWriter outbox, FaultInjector faults) {
        this.ledger = ledger;
        this.accounts = accounts;
        this.outbox = outbox;
        this.faults = faults;
    }

    /**
     * Posts the transfer, or records a decline when the ledger refuses it (ADR 0009). Either way one
     * payment row and one outbox event are written. The ledger refuses before writing anything, so
     * this transaction can still record the decline.
     *
     * @throws AccountNotVisibleException if the caller doesn't own the source or the destination doesn't exist
     */
    public Transfer create(DSLContext tx, TransferCommand command) {
        // Plain reads, no locks: ownership never changes and accounts are never deleted.
        if (accounts.findOwned(tx, command.callerId(), command.source()).isEmpty()
                || !accounts.exists(tx, command.destination())) {
            throw new AccountNotVisibleException();
        }
        // Phase 4: fraud screening goes here, after the idempotency key and before any account lock.

        PaymentStatus status = PaymentStatus.POSTED;
        DeclineReason declineReason = null;
        UUID entryId = null;
        try {
            entryId = ledger.post(tx, entry(command)).entryId();
        } catch (InsufficientFundsException refused) {
            status = PaymentStatus.DECLINED;
            declineReason = DeclineReason.INSUFFICIENT_FUNDS;
        } catch (AccountNotActiveException refused) {
            status = PaymentStatus.DECLINED;
            declineReason = DeclineReason.ACCOUNT_NOT_ACTIVE;
        }

        PaymentsRecord payment = tx.insertInto(PAYMENTS)
                .set(PAYMENTS.ID, UUID.randomUUID())
                .set(PAYMENTS.CALLER_ID, command.callerId())
                .set(PAYMENTS.IDEMPOTENCY_KEY, command.idempotencyKey())
                .set(PAYMENTS.SOURCE_ACCOUNT_ID, command.source())
                .set(PAYMENTS.DEST_ACCOUNT_ID, command.destination())
                .set(PAYMENTS.CURRENCY, command.amount().money().currency().getCurrencyCode())
                .set(PAYMENTS.AMOUNT_MINOR, command.amount().money().minor())
                .set(PAYMENTS.REFERENCE, command.reference())
                .set(PAYMENTS.STATUS, status.name())
                .set(PAYMENTS.DECLINE_REASON, declineReason == null ? null : declineReason.name())
                .set(PAYMENTS.JOURNAL_ENTRY_ID, entryId)
                .returning()
                .fetchSingle();
        Transfer transfer = toTransfer(payment);
        outbox.append(tx, "transfer", transfer.id(),
                status == PaymentStatus.POSTED ? "transfer.posted" : "transfer.declined", eventPayload(transfer));
        faults.at(BEFORE_COMMIT);
        return transfer;
    }

    /**
     * Reverses a POSTED transfer with an entry that mirrors it (ADR 0010). Lock order: the idempotency
     * key (taken by the caller), then this payment's row, then both accounts in id order. The row lock
     * makes concurrent reversals of one transfer take turns: the second one sees REVERSED.
     *
     * <p>The status can't be set first and the entry added later: the CHECK constraints require a
     * REVERSED payment to name its reversal entry and its operator in the same statement.
     *
     * @throws TransferNotFoundException if there is no such transfer
     * @throws InvalidStateTransitionException unless the transfer is POSTED
     * @throws InsufficientFundsException if the destination can't cover it; nothing is written
     * @throws AccountNotActiveException if either account is CLOSED; nothing is written
     */
    public Transfer reverse(DSLContext tx, UUID transferId, UUID operatorId) {
        PaymentsRecord payment = tx.selectFrom(PAYMENTS).where(PAYMENTS.ID.eq(transferId)).forNoKeyUpdate().fetchOne();
        if (payment == null) {
            throw new TransferNotFoundException();
        }
        PaymentStatus current = PaymentStatus.valueOf(payment.getStatus());
        if (!current.canBecome(PaymentStatus.REVERSED)) {
            throw new InvalidStateTransitionException(current, PaymentStatus.REVERSED);
        }
        UUID reversalEntryId = ledger.reverse(tx, payment.getJournalEntryId()).entryId();
        // Conditional on the expected status, as every transition is (DESIGN §4.3); under the row lock it always matches.
        PaymentsRecord reversed = tx.update(PAYMENTS)
                .set(PAYMENTS.STATUS, PaymentStatus.REVERSED.name())
                .set(PAYMENTS.REVERSAL_ENTRY_ID, reversalEntryId)
                .set(PAYMENTS.REVERSED_BY, operatorId)
                .set(PAYMENTS.UPDATED_AT, DSL.currentOffsetDateTime())
                .where(PAYMENTS.ID.eq(transferId))
                .and(PAYMENTS.STATUS.eq(current.name()))
                .returning()
                .fetchOne();
        if (reversed == null) {
            throw new IllegalStateException("payment " + transferId + " changed while its row was locked");
        }
        Transfer transfer = toTransfer(reversed);
        outbox.append(tx, "transfer", transfer.id(), "transfer.reversed", eventPayload(transfer));
        faults.at(REVERSAL_BEFORE_COMMIT);
        return transfer;
    }

    /** A transfer the caller created. */
    public Optional<Transfer> findForCaller(DSLContext db, UUID transferId, UUID callerId) {
        return db.selectFrom(PAYMENTS)
                .where(PAYMENTS.ID.eq(transferId))
                .and(PAYMENTS.CALLER_ID.eq(callerId))
                .fetchOptional(TransferService::toTransfer);
    }

    /** Any transfer; for operators. */
    public Optional<Transfer> findAny(DSLContext db, UUID transferId) {
        return db.selectFrom(PAYMENTS).where(PAYMENTS.ID.eq(transferId)).fetchOptional(TransferService::toTransfer);
    }

    private static JournalEntryRequest entry(TransferCommand command) {
        Money money = command.amount().money();
        return new JournalEntryRequest(EntryKind.TRANSFER, money.currency(), null, List.of(
                new Posting(command.source(), money.negate()),
                new Posting(command.destination(), money)));
    }

    private static Transfer toTransfer(PaymentsRecord row) {
        return new Transfer(row.getId(), row.getCallerId(), PaymentStatus.valueOf(row.getStatus()),
                row.getDeclineReason() == null ? null : DeclineReason.valueOf(row.getDeclineReason()),
                row.getSourceAccountId(), row.getDestAccountId(), row.getAmountMinor(), row.getCurrency(),
                row.getReference(), row.getCreatedAt());
    }

    /** What consumers need, and no free text: the reference stays out of the event stream. */
    private String eventPayload(Transfer transfer) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("transferId", transfer.id().toString());
        payload.put("status", transfer.status().name());
        payload.put("declineReason", transfer.declineReason() == null ? null : transfer.declineReason().name());
        payload.put("sourceAccountId", transfer.source().toString());
        payload.put("destinationAccountId", transfer.destination().toString());
        payload.put("amountMinor", transfer.amountMinor());
        payload.put("currency", transfer.currency());
        payload.put("createdAt", DateTimeFormatter.ISO_INSTANT.format(transfer.createdAt().toInstant()));
        try {
            return json.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
