package com.dach.sagiri.news.model;

import java.util.List;

/**
 * What to search for on behalf of one topic.
 *
 * @param slug   topic subject, e.g. {@code ai-safety}
 * @param query  search expression handed to search-based providers
 * @param feeds  curated feed URLs; empty when the slug has no preset
 * @param preset whether the values came from {@code topic-presets.yml}
 */
public record TopicQuery(String slug, String query, List<String> feeds, boolean preset) {

    public TopicQuery {
        feeds = feeds == null ? List.of() : List.copyOf(feeds);
    }
}
