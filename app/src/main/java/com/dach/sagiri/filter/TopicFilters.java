package com.dach.sagiri.filter;

import java.util.List;
import org.apache.commons.lang3.StringUtils;

/**
 * The rules of one topic, as the filter needs them.
 *
 * @param domain  what the topic is about in words; empty until the slug has been
 *     read by the model, and the reason an article can be rejected for being
 *     about the wrong subject rather than the wrong word
 * @param exclude terms that must not appear in an article
 * @param include terms of which at least one must appear; empty means "anything"
 */
public record TopicFilters(String slug, String domain, List<String> exclude,
                           List<String> include) {

    public TopicFilters {
        domain = StringUtils.trimToEmpty(domain);
        exclude = exclude == null ? List.of() : List.copyOf(exclude);
        include = include == null ? List.of() : List.copyOf(include);
    }

    public static TopicFilters none(String slug, String domain) {
        return new TopicFilters(slug, domain, List.of(), List.of());
    }

    public boolean hasDomain() {
        return !domain.isEmpty();
    }

    /** Whether the reader typed any keyword rule; the domain is not one of those. */
    public boolean isEmpty() {
        return exclude.isEmpty() && include.isEmpty();
    }
}
