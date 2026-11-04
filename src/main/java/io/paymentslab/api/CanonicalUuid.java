package io.paymentslab.api;

import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * UUIDs as clients may send them: canonical 8-4-4-4-12 hex, either case. {@code UUID.fromString}
 * alone is lenient (it accepts "1-1-1-1-1"), so the shape is checked first.
 */
final class CanonicalUuid {

    private static final Pattern TEXT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private CanonicalUuid() {}

    static Optional<UUID> parse(String text) {
        return text != null && TEXT.matcher(text).matches() ? Optional.of(UUID.fromString(text)) : Optional.empty();
    }
}
