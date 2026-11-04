package io.paymentslab.api;

import java.util.List;

/**
 * An outcome the client is meant to see, rendered by {@link HttpApi} as problem+json. Details must
 * be safe to show: no SQL, stack traces, secrets or echoed input.
 */
final class ApiException extends RuntimeException {

    private final int status;
    private final String title;
    private final String code;
    private final String detail;
    private final List<Problem.FieldError> errors;
    private final Integer retryAfterSeconds;

    private ApiException(int status, String title, String code, String detail, List<Problem.FieldError> errors,
                         Integer retryAfterSeconds) {
        // No stack trace: these are expected outcomes, not bugs, and nobody reads one.
        super(code, null, false, false);
        this.status = status;
        this.title = title;
        this.code = code;
        this.detail = detail;
        this.errors = errors;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    private static ApiException of(int status, String title, String code, String detail) {
        return new ApiException(status, title, code, detail, null, null);
    }

    /** One body for every reason (no header, malformed, unknown key), so failures reveal nothing. */
    static ApiException unauthenticated() {
        return of(401, "Unauthorized", "UNAUTHENTICATED", "A valid API key is required.");
    }

    static ApiException forbidden() {
        return of(403, "Forbidden", "FORBIDDEN", "Your role can't use this operation.");
    }

    /** Also used for things that exist but aren't the caller's, so ids can't be probed. */
    static ApiException notFound() {
        return of(404, "Not Found", "NOT_FOUND", "Not found, or not visible to this caller.");
    }

    static ApiException validation(List<Problem.FieldError> errors) {
        return new ApiException(400, "Bad Request", "VALIDATION_FAILED", "Some parameters are invalid.",
                List.copyOf(errors), null);
    }

    static ApiException idempotencyKeyMissing() {
        return of(400, "Bad Request", "IDEMPOTENCY_KEY_MISSING", "This operation needs an Idempotency-Key header.");
    }

    static ApiException idempotencyKeyReused() {
        return of(422, "Unprocessable Content", "IDEMPOTENCY_KEY_REUSED",
                "This Idempotency-Key was already used for a different request.");
    }

    static ApiException requestInProgress() {
        return new ApiException(409, "Conflict", "IDEMPOTENCY_REQUEST_IN_PROGRESS",
                "A request with this Idempotency-Key is still in progress. Retry shortly.", null, 1);
    }

    static ApiException contention() {
        return new ApiException(503, "Service Unavailable", "CONTENTION_RETRY_EXHAUSTED",
                "The accounts involved are busy. Nothing was changed; retry shortly.", null, 1);
    }

    static ApiException invalidAccountCombination(String detail) {
        return of(422, "Unprocessable Content", "INVALID_ACCOUNT_COMBINATION", detail);
    }

    static ApiException invalidStateTransition() {
        return of(409, "Conflict", "INVALID_STATE_TRANSITION", "The transfer's status doesn't allow this operation.");
    }

    static ApiException insufficientFunds() {
        return of(422, "Unprocessable Content", "INSUFFICIENT_FUNDS", "The account doesn't hold enough to cover this.");
    }

    static ApiException accountNotActive() {
        return of(422, "Unprocessable Content", "ACCOUNT_NOT_ACTIVE", "The account isn't active.");
    }

    static ApiException internal() {
        return of(500, "Internal Server Error", "INTERNAL", "Unexpected error. Quote the correlationId if you report it.");
    }

    int status() {
        return status;
    }

    /** Seconds for a Retry-After header, or null for none. */
    Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }

    Problem problem(String correlationId) {
        return new Problem("about:blank", title, status, code, detail, correlationId, errors);
    }
}
