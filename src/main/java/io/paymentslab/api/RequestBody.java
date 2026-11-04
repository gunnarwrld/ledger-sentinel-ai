package io.paymentslab.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.paymentslab.money.SupportedCurrencies;
import io.paymentslab.money.TransferAmount;
import io.paymentslab.money.UnsupportedCurrencyException;
import java.util.Currency;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A JSON request body, read field by field. Every problem goes into the caller's error list, so the
 * client learns about all of them at once. Nothing is guessed, converted or ignored: unknown fields
 * are rejected (mass assignment, threat T6) and amounts must be JSON integers.
 */
final class RequestBody {

    private static final ObjectMapper STRICT = JsonMapper.builder()
            // {"a":1,"a":2} is an error, not "the last one wins".
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            // One object and nothing after it.
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    /** Null when the body isn't one JSON object; that is reported once and the fields add nothing. */
    private final ObjectNode root;
    private final List<Problem.FieldError> errors;

    private RequestBody(ObjectNode root, List<Problem.FieldError> errors) {
        this.root = root;
        this.errors = errors;
    }

    static RequestBody parse(String raw, Set<String> fields, List<Problem.FieldError> errors) {
        JsonNode node;
        try {
            node = raw == null ? null : STRICT.readTree(raw);
        } catch (JsonProcessingException notJson) {
            node = null;
        }
        if (!(node instanceof ObjectNode object)) {
            errors.add(new Problem.FieldError("body", "must be one JSON object"));
            return new RequestBody(null, errors);
        }
        object.fieldNames().forEachRemaining(name -> {
            if (!fields.contains(name)) {
                errors.add(new Problem.FieldError(name, "is not allowed"));
            }
        });
        return new RequestBody(object, errors);
    }

    UUID uuid(String field) {
        JsonNode value = required(field);
        if (value == null) {
            return null;
        }
        UUID id = value.isTextual() ? CanonicalUuid.parse(value.textValue()).orElse(null) : null;
        if (id == null) {
            errors.add(new Problem.FieldError(field, "must be a UUID"));
        }
        return id;
    }

    /** A JSON integer within the transfer amount range; 1.0, 1e3 and "10" are rejected, not converted. */
    Long amountMinor(String field) {
        JsonNode value = required(field);
        if (value == null) {
            return null;
        }
        if (value.isIntegralNumber() && value.canConvertToLong()) {
            long amount = value.longValue();
            if (amount >= 1 && amount <= TransferAmount.MAX_MINOR) {
                return amount;
            }
        }
        errors.add(new Problem.FieldError(field, "must be an integer from 1 to " + TransferAmount.MAX_MINOR));
        return null;
    }

    Currency currency(String field) {
        JsonNode value = required(field);
        if (value == null) {
            return null;
        }
        if (value.isTextual()) {
            try {
                return SupportedCurrencies.parse(value.textValue());
            } catch (UnsupportedCurrencyException unsupported) {
                // reported below
            }
        }
        errors.add(new Problem.FieldError(field, "must be a supported currency code"));
        return null;
    }

    /** Absent is fine (null); present means a string of at most {@code maxLength} characters (code points, as PostgreSQL counts). */
    String optionalText(String field, int maxLength) {
        if (root == null || !root.has(field)) {
            return null;
        }
        JsonNode value = root.get(field);
        if (value.isTextual() && value.textValue().codePointCount(0, value.textValue().length()) <= maxLength) {
            return value.textValue();
        }
        errors.add(new Problem.FieldError(field, "must be a string of at most " + maxLength + " characters"));
        return null;
    }

    /** The field's value, or null after recording why there is none. */
    private JsonNode required(String field) {
        if (root == null) {
            return null;
        }
        JsonNode value = root.get(field);
        if (value == null) {
            errors.add(new Problem.FieldError(field, "is required"));
        }
        return value;
    }
}
