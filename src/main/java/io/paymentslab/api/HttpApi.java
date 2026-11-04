package io.paymentslab.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.paymentslab.ledger.AccountNotActiveException;
import io.paymentslab.ledger.AccountNotFoundException;
import io.paymentslab.ledger.AccountOpening;
import io.paymentslab.ledger.AccountQueries;
import io.paymentslab.ledger.AccountQueries.AccountView;
import io.paymentslab.ledger.AccountQueries.StatementLine;
import io.paymentslab.ledger.FundingService;
import io.paymentslab.ledger.InsufficientFundsException;
import io.paymentslab.ledger.LedgerService;
import io.paymentslab.ledger.PostedEntry;
import io.paymentslab.ledger.SystemAccounts;
import io.paymentslab.money.TransferAmount;
import io.paymentslab.outbox.OutboxWriter;
import io.paymentslab.payments.AccountNotVisibleException;
import io.paymentslab.payments.Idempotency;
import io.paymentslab.payments.IdempotencyKeyReusedException;
import io.paymentslab.payments.InvalidStateTransitionException;
import io.paymentslab.payments.RequestFingerprint;
import io.paymentslab.payments.RequestInProgressException;
import io.paymentslab.payments.Transfer;
import io.paymentslab.payments.TransferCommand;
import io.paymentslab.payments.TransferNotFoundException;
import io.paymentslab.payments.TransferService;
import io.paymentslab.platform.ContentionException;
import io.paymentslab.platform.FaultInjector;
import io.paymentslab.platform.HealthService;
import io.paymentslab.platform.TxRunner;
import java.io.UncheckedIOException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Currency;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;
import spark.Service;
import spark.route.HttpMethod;

/** The only class that knows about SparkJava (docs/adr/0001). */
public final class HttpApi {

    private static final Logger log = LoggerFactory.getLogger(HttpApi.class);

    private static final String JSON = "application/json";
    private static final String PROBLEM_JSON = "application/problem+json";
    private static final String CALLER = "caller";
    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;
    /** Spark keeps filters in its route table; they aren't operations. */
    private static final Set<HttpMethod> FILTERS = EnumSet.of(HttpMethod.before, HttpMethod.after, HttpMethod.afterafter);

    // Idempotency scopes (caller, operation, key); DESIGN §6.
    private static final String OPEN_ACCOUNT = "account.create";
    private static final String FUND_ACCOUNT = "funding.create";
    private static final String CREATE_TRANSFER = "transfer.create";
    private static final String REVERSE_TRANSFER = "transfer.reverse";
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[!-~]{8,255}");
    private static final Set<String> OPEN_ACCOUNT_FIELDS = Set.of("currency");
    private static final Set<String> FUNDING_FIELDS = Set.of("accountId", "amountMinor", "currency");
    private static final Set<String> TRANSFER_FIELDS =
            Set.of("sourceAccountId", "destinationAccountId", "amountMinor", "currency", "reference");
    private static final int MAX_REFERENCE = 140;

    private final Service http;
    private final String bindAddress;
    private final int requestedPort;
    private final HealthService health;
    private final DSLContext db;
    private final Callers callers = new Callers();
    private final AccountQueries accounts = new AccountQueries();
    private final AccountOpening accountOpening = new AccountOpening();
    private final FundingService funding = new FundingService(new LedgerService());
    private final Idempotency idempotency;
    private final TransferService transfers;
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicReference<Exception> startupFailure = new AtomicReference<>();

    public HttpApi(String bindAddress, int port, HealthService health, DSLContext db) {
        this(bindAddress, port, health, db, FaultInjector.NONE);
    }

    public HttpApi(String bindAddress, int port, HealthService health, DSLContext db, FaultInjector faults) {
        this.bindAddress = bindAddress;
        this.requestedPort = port;
        this.health = health;
        this.db = db;
        this.idempotency = new Idempotency(new TxRunner(db));
        this.transfers = new TransferService(new LedgerService(), accounts, new OutboxWriter(), faults);
        this.http = Service.ignite().ipAddress(bindAddress).port(port);
        // Spark's default handler calls System.exit(100) from a background thread when the
        // server cannot start (for example, the port is taken). Record the failure instead and
        // rethrow it from start(). Must be set before the first route, which starts the server.
        http.initExceptionHandler(startupFailure::set);
    }

    /** Registers routes and blocks until the server is listening or has failed to start. */
    public void start() {
        http.get("/health/live", (request, response) -> {
            response.type(JSON);
            return json.writeValueAsString(Map.of("status", "UP"));
        });
        http.get("/health/ready", (request, response) -> {
            HealthService.Readiness readiness = health.readiness();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", readiness.ready() ? "UP" : "DOWN");
            body.put("checks", readiness.checks());
            response.status(readiness.ready() ? 200 : 503);
            response.type(JSON);
            return json.writeValueAsString(body);
        });

        // Runs before Spark looks for a matching route, so it covers every /v1 route, including
        // ones added later, and unauthenticated callers can't map the API by probing paths.
        http.before("/v1/*", (request, response) -> request.attribute(CALLER, authenticate(request)));
        http.post("/v1/accounts", this::openAccount);
        http.get("/v1/accounts/:accountId", this::getAccount);
        http.get("/v1/accounts/:accountId/entries", this::listEntries);
        http.post("/v1/admin/fundings", this::fundAccount);
        http.post("/v1/transfers", this::createTransfer);
        http.get("/v1/transfers/:transferId", this::getTransfer);
        http.post("/v1/transfers/:transferId/reversal", this::reverseTransfer);

        http.notFound((request, response) -> {
            response.type(PROBLEM_JSON);
            return write(ApiException.notFound().problem(null));
        });
        http.exception(ApiException.class, (e, request, response) -> problem(response, e, null));
        http.exception(IdempotencyKeyReusedException.class,
                (e, request, response) -> problem(response, ApiException.idempotencyKeyReused(), null));
        http.exception(RequestInProgressException.class,
                (e, request, response) -> problem(response, ApiException.requestInProgress(), null));
        http.exception(AccountNotVisibleException.class,
                (e, request, response) -> problem(response, ApiException.notFound(), null));
        http.exception(TransferNotFoundException.class,
                (e, request, response) -> problem(response, ApiException.notFound(), null));
        http.exception(InvalidStateTransitionException.class,
                (e, request, response) -> problem(response, ApiException.invalidStateTransition(), null));
        http.exception(ContentionException.class, (e, request, response) -> {
            log.warn("transaction retries exhausted", e);
            problem(response, ApiException.contention(), null);
        });
        http.exception(Exception.class, (e, request, response) -> {
            // The client gets an id, never the exception: its message can hold SQL or data.
            String correlationId = UUID.randomUUID().toString();
            log.error("unhandled error, correlationId={}", correlationId, e);
            problem(response, ApiException.internal(), correlationId);
        });

        http.init();
        http.awaitInitialization();

        Exception failure = startupFailure.get();
        if (failure != null) {
            stop();
            throw new IllegalStateException(
                    "HTTP server failed to start on " + bindAddress + ":" + requestedPort, failure);
        }
    }

    public int port() {
        return http.port();
    }

    /** Registered routes as "GET /v1/transfers/{id}", in OpenAPI path syntax (for the contract spec). */
    public List<String> routes() {
        return http.routes().stream()
                .filter(route -> !FILTERS.contains(route.getHttpMethod()))
                .map(route -> route.getHttpMethod().name().toUpperCase(Locale.ROOT) + " "
                        + route.getMatchUri().replaceAll(":(\\w+)", "{$1}"))
                .toList();
    }

    public void stop() {
        http.stop();
        http.awaitStop();
    }

    private Caller authenticate(Request request) {
        Optional<String> key = BearerToken.from(request.headers("Authorization"));
        Optional<Caller> caller = key.flatMap(presented -> callers.findByApiKey(db, presented));
        if (caller.isEmpty()) {
            // Never log the header or the key, not even part of it. The raw URI keeps any
            // encoded line breaks encoded.
            log.debug("authentication failed ({}) for {} {}",
                    key.isEmpty() ? "no bearer token" : "malformed or unknown key", request.requestMethod(), request.uri());
            throw ApiException.unauthenticated();
        }
        return caller.get();
    }

    private Object openAccount(Request request, Response response) {
        Caller caller = requireRole(request, CallerRole.CUSTOMER);
        List<Problem.FieldError> errors = new ArrayList<>();
        String key = idempotencyKey(request, errors);
        RequestBody body = RequestBody.parse(request.body(), OPEN_ACCOUNT_FIELDS, errors);
        Currency currency = body.currency("currency");
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        byte[] fingerprint = RequestFingerprint.of(OPEN_ACCOUNT).add(currency.getCurrencyCode()).hash();
        Idempotency.Result result = idempotency.execute(
                new Idempotency.Request(caller.id(), OPEN_ACCOUNT, key, fingerprint),
                tx -> {
                    AccountView account = accountOpening.open(tx, caller.id(), currency);
                    return new Idempotency.Completed(201, write(accountBody(account)), account.id());
                });
        return respond(response, result);
    }

    private Object fundAccount(Request request, Response response) {
        Caller caller = requireRole(request, CallerRole.OPERATOR);
        List<Problem.FieldError> errors = new ArrayList<>();
        String key = idempotencyKey(request, errors);
        RequestBody body = RequestBody.parse(request.body(), FUNDING_FIELDS, errors);
        UUID accountId = body.uuid("accountId");
        Long amount = body.amountMinor("amountMinor");
        Currency currency = body.currency("currency");
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        if (accountId.equals(SystemAccounts.FUNDING)) {
            throw ApiException.invalidAccountCombination("The SYSTEM funding account can't fund itself.");
        }
        byte[] fingerprint = RequestFingerprint.of(FUND_ACCOUNT)
                .add(accountId).add(amount).add(currency.getCurrencyCode()).hash();
        Idempotency.Result result = idempotency.execute(
                new Idempotency.Request(caller.id(), FUND_ACCOUNT, key, fingerprint),
                tx -> {
                    PostedEntry posted;
                    try {
                        posted = funding.fund(tx, accountId, TransferAmount.of(amount, currency));
                    } catch (AccountNotFoundException missing) {
                        throw ApiException.notFound();
                    } catch (AccountNotActiveException inactive) {
                        throw ApiException.accountNotActive();
                    }
                    Map<String, Object> funded = new LinkedHashMap<>();
                    funded.put("journalEntryId", posted.entryId().toString());
                    funded.put("accountId", accountId.toString());
                    funded.put("amountMinor", amount);
                    funded.put("currency", currency.getCurrencyCode());
                    funded.put("createdAt", timestamp(posted.createdAt()));
                    return new Idempotency.Completed(201, write(funded), posted.entryId());
                });
        return respond(response, result);
    }

    private Object createTransfer(Request request, Response response) {
        Caller caller = requireRole(request, CallerRole.CUSTOMER);
        List<Problem.FieldError> errors = new ArrayList<>();
        String key = idempotencyKey(request, errors);
        RequestBody body = RequestBody.parse(request.body(), TRANSFER_FIELDS, errors);
        UUID source = body.uuid("sourceAccountId");
        UUID destination = body.uuid("destinationAccountId");
        Long amount = body.amountMinor("amountMinor");
        Currency currency = body.currency("currency");
        String reference = body.optionalText("reference", MAX_REFERENCE);
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        if (source.equals(destination)) {
            throw ApiException.invalidAccountCombination("Source and destination must be different accounts.");
        }
        byte[] fingerprint = RequestFingerprint.of(CREATE_TRANSFER)
                .add(source).add(destination).add(amount).add(currency.getCurrencyCode()).add(reference).hash();
        TransferCommand command = new TransferCommand(
                caller.id(), key, source, destination, TransferAmount.of(amount, currency), reference);
        Idempotency.Result result = idempotency.execute(
                new Idempotency.Request(caller.id(), CREATE_TRANSFER, key, fingerprint),
                tx -> {
                    Transfer transfer = transfers.create(tx, command);
                    return new Idempotency.Completed(201, write(transferBody(transfer)), transfer.id());
                });
        return respond(response, result);
    }

    /** Operators only (ADR 0010). A refusal writes nothing, so the key stays free for a later retry. */
    private Object reverseTransfer(Request request, Response response) {
        Caller caller = requireRole(request, CallerRole.OPERATOR);
        UUID transferId = CanonicalUuid.parse(request.params("transferId")).orElseThrow(ApiException::notFound);
        List<Problem.FieldError> errors = new ArrayList<>();
        String key = idempotencyKey(request, errors);
        // No parameters: an empty body, or {} at most. Anything else, such as an amount, is refused
        // rather than ignored, because there are no partial reversals.
        String raw = request.body();
        if (raw != null && !raw.isBlank()) {
            RequestBody.parse(raw, Set.of(), errors);
        }
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        byte[] fingerprint = RequestFingerprint.of(REVERSE_TRANSFER).add(transferId).hash();
        Idempotency.Result result = idempotency.execute(
                new Idempotency.Request(caller.id(), REVERSE_TRANSFER, key, fingerprint),
                tx -> {
                    Transfer reversed;
                    try {
                        reversed = transfers.reverse(tx, transferId, caller.id());
                    } catch (InsufficientFundsException notCovered) {
                        throw ApiException.insufficientFunds();
                    } catch (AccountNotActiveException closed) {
                        throw ApiException.accountNotActive();
                    }
                    return new Idempotency.Completed(201, write(transferBody(reversed)), reversed.id());
                });
        return respond(response, result);
    }

    /** The creator can read a transfer, and so can operators; anyone else gets the same 404 as an unknown id. */
    private Object getTransfer(Request request, Response response) {
        Caller caller = request.attribute(CALLER);
        UUID transferId = CanonicalUuid.parse(request.params("transferId")).orElseThrow(ApiException::notFound);
        Optional<Transfer> transfer = caller.role() == CallerRole.OPERATOR
                ? transfers.findAny(db, transferId)
                : transfers.findForCaller(db, transferId, caller.id());
        return ok(response, transferBody(transfer.orElseThrow(ApiException::notFound)));
    }

    private Object getAccount(Request request, Response response) {
        Caller caller = request.attribute(CALLER);
        AccountView account = accounts.findOwned(db, caller.id(), accountId(request))
                .orElseThrow(ApiException::notFound);
        return ok(response, accountBody(account));
    }

    private Object listEntries(Request request, Response response) {
        Caller caller = request.attribute(CALLER);
        UUID accountId = accountId(request);
        List<Problem.FieldError> errors = new ArrayList<>();
        int limit = limit(request.queryParams("limit"), errors);
        Long before = cursor(request.queryParams("cursor"), errors);
        // Parameters are checked first: the answer to a bad request mustn't depend on ownership.
        if (!errors.isEmpty()) {
            throw ApiException.validation(errors);
        }
        if (accounts.findOwned(db, caller.id(), accountId).isEmpty()) {
            throw ApiException.notFound();
        }
        // One row more than the page tells us whether another page exists.
        List<StatementLine> rows = accounts.statement(db, accountId, before, limit + 1);
        List<StatementLine> page = rows.subList(0, Math.min(limit, rows.size()));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", page.stream().map(HttpApi::entryBody).toList());
        body.put("nextCursor", rows.size() > limit ? EntryCursor.encode(page.get(page.size() - 1).postingId()) : null);
        return ok(response, body);
    }

    private static Caller requireRole(Request request, CallerRole role) {
        Caller caller = request.attribute(CALLER);
        if (caller.role() != role) {
            throw ApiException.forbidden();
        }
        return caller;
    }

    /** A missing key has its own code; a malformed one is reported with the body's field errors. */
    private static String idempotencyKey(Request request, List<Problem.FieldError> errors) {
        String key = request.headers("Idempotency-Key");
        if (key == null) {
            throw ApiException.idempotencyKeyMissing();
        }
        if (!IDEMPOTENCY_KEY.matcher(key).matches()) {
            errors.add(new Problem.FieldError("Idempotency-Key", "must be 8 to 255 printable ASCII characters without spaces"));
        }
        return key;
    }

    /** Something that isn't a canonical UUID can't name an account: the same 404 as an unknown id. */
    private static UUID accountId(Request request) {
        return CanonicalUuid.parse(request.params("accountId")).orElseThrow(ApiException::notFound);
    }

    private static int limit(String value, List<Problem.FieldError> errors) {
        if (value == null) {
            return DEFAULT_LIMIT;
        }
        try {
            int limit = Integer.parseInt(value);
            if (limit >= 1 && limit <= MAX_LIMIT) {
                return limit;
            }
        } catch (NumberFormatException notAnInt) {
            // reported below
        }
        errors.add(new Problem.FieldError("limit", "must be an integer from 1 to " + MAX_LIMIT));
        return DEFAULT_LIMIT;
    }

    private static Long cursor(String value, List<Problem.FieldError> errors) {
        if (value == null) {
            return null;
        }
        try {
            return EntryCursor.decode(value);
        } catch (IllegalArgumentException e) {
            errors.add(new Problem.FieldError("cursor", e.getMessage()));
            return null;
        }
    }

    // Response bodies are mapped field by field: only what the contract declares leaves the server.
    private static Map<String, Object> accountBody(AccountView account) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", account.id().toString());
        body.put("currency", account.currency());
        body.put("status", account.status());
        body.put("balanceMinor", account.balanceMinor());
        body.put("createdAt", timestamp(account.createdAt()));
        return body;
    }

    private static Map<String, Object> entryBody(StatementLine line) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("journalEntryId", line.journalEntryId().toString());
        body.put("kind", line.kind().name());
        body.put("amountMinor", line.amountMinor());
        body.put("currency", line.currency());
        body.put("createdAt", timestamp(line.createdAt()));
        return body;
    }

    private static Map<String, Object> transferBody(Transfer transfer) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", transfer.id().toString());
        body.put("status", transfer.status().name());
        body.put("declineReason", transfer.declineReason() == null ? null : transfer.declineReason().name());
        body.put("sourceAccountId", transfer.source().toString());
        body.put("destinationAccountId", transfer.destination().toString());
        body.put("amountMinor", transfer.amountMinor());
        body.put("currency", transfer.currency());
        body.put("reference", transfer.reference());
        body.put("createdAt", timestamp(transfer.createdAt()));
        return body;
    }

    /** RFC 3339 in UTC, for example 2026-10-30T09:15:30.123456Z. */
    private static String timestamp(OffsetDateTime time) {
        return DateTimeFormatter.ISO_INSTANT.format(time.toInstant());
    }

    private String ok(Response response, Object body) {
        response.type(JSON);
        return write(body);
    }

    /** The stored status and body, exactly as first sent; a replay says so in a header. */
    private static Object respond(Response response, Idempotency.Result result) {
        response.status(result.status());
        response.type(JSON);
        if (result.replayed()) {
            response.header("Idempotent-Replayed", "true");
        }
        return result.body();
    }

    private void problem(Response response, ApiException e, String correlationId) {
        response.status(e.status());
        response.type(PROBLEM_JSON);
        if (e.status() == 401) {
            response.header("WWW-Authenticate", "Bearer realm=\"payments-lab\"");
        }
        if (e.retryAfterSeconds() != null) {
            response.header("Retry-After", e.retryAfterSeconds().toString());
        }
        response.body(write(e.problem(correlationId)));
    }

    private String write(Object body) {
        try {
            return json.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
