package io.paymentslab.api;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Statement page cursor: base64url of {@code "v1:" + postingId}, the last posting on the previous
 * page. Opaque so clients don't build or parse it; versioned so the format can change. It needs no
 * signature: the route still checks that the caller owns the account, so a hand-made cursor can only
 * move around the caller's own statement.
 */
final class EntryCursor {

    /** The contract's maxLength for the cursor parameter. */
    private static final int MAX_LENGTH = 200;
    private static final Pattern DECODED = Pattern.compile("v1:([1-9][0-9]{0,18})");

    private EntryCursor() {}

    static String encode(long postingId) {
        if (postingId < 1) {
            throw new IllegalArgumentException("posting ids start at 1");
        }
        byte[] text = ("v1:" + postingId).getBytes(StandardCharsets.US_ASCII);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text);
    }

    /** @throws IllegalArgumentException for anything {@link #encode} could not have produced */
    static long decode(String cursor) {
        if (cursor == null || cursor.isEmpty() || cursor.length() > MAX_LENGTH) {
            throw invalid();
        }
        Matcher matcher;
        try {
            matcher = DECODED.matcher(new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII));
        } catch (IllegalArgumentException notBase64) {
            throw invalid();
        }
        if (!matcher.matches()) {
            throw invalid();
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException beyondLong) {
            throw invalid();
        }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("not a cursor returned by this API");
    }
}
