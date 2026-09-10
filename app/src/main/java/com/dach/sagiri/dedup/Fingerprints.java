package com.dach.sagiri.dedup;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.regex.Pattern;

/** Stable hashes used as de-duplication keys. */
public final class Fingerprints {

    private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^\\p{L}\\p{N}]+");

    private Fingerprints() {
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }

    /**
     * Hash of the normalised title. Aggregators republish the same story under
     * different URLs, so the title is the second de-duplication axis.
     */
    public static String titleHash(String title) {
        String normalized = NON_ALPHANUMERIC
            .matcher(title == null ? "" : title.toLowerCase(Locale.ROOT))
            .replaceAll(" ")
            .strip();
        return sha256(normalized);
    }
}
