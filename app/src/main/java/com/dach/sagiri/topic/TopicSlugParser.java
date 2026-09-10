package com.dach.sagiri.topic;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import com.dach.sagiri.config.BotProperties;

/**
 * Turns a forum topic name into a subject slug: {@code "News-AI Safety 🤖"} to
 * {@code "ai-safety"}. A name that does not carry the configured prefix belongs
 * to somebody else and must be left alone.
 */
@Component
public class TopicSlugParser {

    /** Everything that is neither a letter, a digit nor a separator — emoji included. */
    private static final Pattern DECORATION = Pattern.compile("[^\\p{L}\\p{N}\\s_-]");
    private static final Pattern SEPARATORS = Pattern.compile("[\\s_]+");
    private static final Pattern REPEATED_DASHES = Pattern.compile("-{2,}");
    private static final Pattern EDGE_DASHES = Pattern.compile("^-+|-+$");
    private static final Pattern PROVISIONABLE = Pattern.compile("[a-z0-9-]{2,100}");

    /** Telegram rejects a forum topic name longer than this. */
    static final int MAX_TOPIC_NAME_LENGTH = 128;

    private final String prefix;
    private final String prefixBase;
    private final Pattern prefixPattern;

    public TopicSlugParser(BotProperties properties) {
        this.prefix = properties.getTopicPrefix();
        // "news-" must also match "news_it" and "News IT": the separator is free.
        this.prefixBase = normalizeSlug(prefix);
        this.prefixPattern = Pattern.compile("^" + Pattern.quote(prefixBase) + "[-_\\s]+");
    }

    /**
     * @return the subject slug, or empty when the topic is not one of ours
     *     (wrong prefix, or nothing left after the prefix)
     */
    public Optional<String> parse(String rawName) {
        if (StringUtils.isBlank(rawName)) {
            return Optional.empty();
        }
        String normalized = normalizeName(rawName);
        if (!prefixPattern.matcher(normalized).find()) {
            return Optional.empty();
        }
        String slug = normalizeSlug(prefixPattern.matcher(normalized).replaceFirst(""));
        return slug.isEmpty() ? Optional.empty() : Optional.of(slug);
    }

    /** Lower-cases, drops emoji and decoration, collapses whitespace. */
    public static String normalizeName(String rawName) {
        String cleaned = DECORATION.matcher(rawName).replaceAll(" ");
        return cleaned.strip().toLowerCase(Locale.ROOT).replaceAll("\\s{2,}", " ");
    }

    /**
     * Normalises user input into a slug: lower case, separators to dashes,
     * repeated dashes collapsed, edge dashes trimmed.
     */
    public static String normalizeSlug(String raw) {
        if (StringUtils.isBlank(raw)) {
            return "";
        }
        String slug = normalizeName(raw);
        slug = SEPARATORS.matcher(slug).replaceAll("-");
        slug = REPEATED_DASHES.matcher(slug).replaceAll("-");
        return EDGE_DASHES.matcher(slug).replaceAll("");
    }

    /**
     * Normalises a slug the user passed to {@code /newtopic}. A leading prefix is
     * stripped so {@code /newtopic news-it} yields {@code news-it}, not
     * {@code news-news-it}.
     */
    public String normalizeRequestedSlug(String raw) {
        String slug = normalizeSlug(prefixPattern.matcher(normalizeSlug(raw)).replaceFirst(""));
        // A bare "news" carries no subject at all.
        return slug.equals(prefixBase) ? "" : slug;
    }

    /**
     * Whether the bot may create a topic for this slug: ASCII letters, digits and
     * dashes only, and the resulting topic name must fit into Telegram's limit.
     */
    public boolean isProvisionable(String slug) {
        return StringUtils.isNotBlank(slug)
               && PROVISIONABLE.matcher(slug).matches()
               && (prefix.length() + slug.length()) <= MAX_TOPIC_NAME_LENGTH;
    }

    /** {@code "ai-safety"} to {@code "Ai Safety"}, used in the welcome message. */
    public static String displayName(String slug) {
        String spaced = slug.replace('-', ' ');
        StringBuilder out = new StringBuilder(spaced.length());
        boolean capitalize = true;
        for (char c : spaced.toCharArray()) {
            out.append(capitalize ? Character.toUpperCase(c) : c);
            capitalize = c == ' ';
        }
        return out.toString();
    }

    public String prefix() {
        return prefix;
    }
}
