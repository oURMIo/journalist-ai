package com.dach.sagiri.dedup;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

/**
 * Reduces a URL to a stable identity so that the same article shared with
 * different tracking parameters is recognised as one story.
 */
@Component
public class UrlCanonicalizer {

    private static final Set<String> TRACKING_PARAMS = Set.of(
        "fbclid", "gclid", "dclid", "msclkid", "yclid", "igshid", "mc_cid", "mc_eid",
        "ref", "ref_src", "source", "cmpid", "smid", "spm", "_hsenc", "_hsmi");

    private static final Set<String> DEFAULT_PORTS_STRIPPED = Set.of("http", "https");

    /**
     * @return a canonical form of the URL, or the trimmed input when it cannot be
     *     parsed — an unparseable URL is still a usable identity
     */
    public String canonicalize(String rawUrl) {
        if (StringUtils.isBlank(rawUrl)) {
            return "";
        }
        String trimmed = rawUrl.strip();
        try {
            URI uri = new URI(trimmed);
            if (uri.getHost() == null) {
                return trimmed.toLowerCase(Locale.ROOT);
            }
            String scheme = StringUtils.lowerCase(uri.getScheme(), Locale.ROOT);
            String host = uri.getHost().toLowerCase(Locale.ROOT).replaceFirst("^www\\.", "");
            String path = normalizePath(uri.getPath());
            String query = normalizeQuery(uri.getRawQuery());

            StringBuilder canonical = new StringBuilder();
            canonical.append(scheme == null ? "http" : scheme).append("://").append(host);
            if (uri.getPort() > 0 && !DEFAULT_PORTS_STRIPPED.contains(scheme)) {
                canonical.append(':').append(uri.getPort());
            }
            canonical.append(path);
            if (!query.isEmpty()) {
                canonical.append('?').append(query);
            }
            return canonical.toString();
        } catch (URISyntaxException e) {
            return trimmed.toLowerCase(Locale.ROOT);
        }
    }

    private static String normalizePath(String path) {
        if (StringUtils.isBlank(path) || "/".equals(path)) {
            return "";
        }
        return StringUtils.removeEnd(path, "/");
    }

    /** Drops tracking parameters and sorts the rest so ordering cannot fool us. */
    private static String normalizeQuery(String rawQuery) {
        if (StringUtils.isBlank(rawQuery)) {
            return "";
        }
        return Arrays.stream(rawQuery.split("&"))
            .filter(StringUtils::isNotBlank)
            .filter(param -> !isTracking(param))
            .sorted(Comparator.naturalOrder())
            .collect(Collectors.joining("&"));
    }

    private static boolean isTracking(String param) {
        String key = StringUtils.substringBefore(param, "=").toLowerCase(Locale.ROOT);
        return key.startsWith("utm_") || TRACKING_PARAMS.contains(key);
    }
}
