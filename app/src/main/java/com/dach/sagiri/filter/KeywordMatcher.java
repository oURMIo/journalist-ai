package com.dach.sagiri.filter;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import com.dach.sagiri.news.model.NewsItem;
import com.dach.sagiri.summary.ExtractiveSummarizer;

/**
 * Literal keyword matching over a headline and a feed description.
 * <p>
 * Both sides are reduced to lowercase words separated by single spaces and then
 * compared as whole word sequences: the term {@code go} must hit "Go 1.24" but
 * not "Google" or "going", which a plain {@code contains} would get wrong.
 */
public final class KeywordMatcher {

    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

    private KeywordMatcher() {
    }

    /** Headline plus de-HTML-ed description — everything the bot honestly has. */
    public static String haystack(NewsItem item) {
        return normalize(StringUtils.trimToEmpty(item.title()) + " "
                         + ExtractiveSummarizer.plainText(item.rawDescription()));
    }

    /** @return the first term of {@code terms} present in the text */
    public static Optional<String> firstMatch(String haystack, List<String> terms) {
        return terms.stream().filter(term -> matches(haystack, term)).findFirst();
    }

    public static boolean matches(String haystack, String term) {
        String needle = normalize(term);
        return !needle.isEmpty() && padded(haystack).contains(padded(needle));
    }

    /** Lowercase words separated by single spaces; punctuation and markup gone. */
    public static String normalize(String text) {
        if (StringUtils.isBlank(text)) {
            return "";
        }
        return NON_WORD.matcher(text.toLowerCase(Locale.ROOT)).replaceAll(" ").strip();
    }

    /** Surrounding spaces turn {@code contains} into whole-word matching. */
    private static String padded(String normalized) {
        return " " + normalized + " ";
    }
}
