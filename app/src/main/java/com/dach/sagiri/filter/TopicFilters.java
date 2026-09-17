package com.dach.sagiri.filter;

import java.util.List;

/**
 * The keyword rules of one topic, as the filter needs them.
 *
 * @param exclude terms that must not appear in an article
 * @param include terms of which at least one must appear; empty means "anything"
 */
public record TopicFilters(String slug, List<String> exclude, List<String> include) {

    public TopicFilters {
        exclude = exclude == null ? List.of() : List.copyOf(exclude);
        include = include == null ? List.of() : List.copyOf(include);
    }

    public static TopicFilters none(String slug) {
        return new TopicFilters(slug, List.of(), List.of());
    }

    public boolean isEmpty() {
        return exclude.isEmpty() && include.isEmpty();
    }
}
