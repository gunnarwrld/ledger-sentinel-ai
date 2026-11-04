package io.paymentslab.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * An RFC 9457 problem details body plus a stable machine-readable {@code code}. {@code type} is
 * {@code about:blank}, so {@code title} is the HTTP status phrase and clients branch on {@code code}.
 * Absent members are left out of the JSON.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Problem(
        String type, String title, int status, String code, String detail, String correlationId,
        List<FieldError> errors) {

    /** One invalid request parameter. */
    public record FieldError(String field, String message) {}
}
