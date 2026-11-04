package io.paymentslab.api;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads the credentials from {@code Authorization: Bearer <token>} (RFC 6750 §2.1). */
final class BearerToken {

    // The scheme name is case-insensitive (RFC 9110 §11.1); the token is a token68.
    private static final Pattern HEADER = Pattern.compile("(?i:bearer) +([A-Za-z0-9._~+/-]+=*)");

    private BearerToken() {}

    static Optional<String> from(String authorizationHeader) {
        if (authorizationHeader == null) {
            return Optional.empty();
        }
        Matcher matcher = HEADER.matcher(authorizationHeader);
        return matcher.matches() ? Optional.of(matcher.group(1)) : Optional.empty();
    }
}
