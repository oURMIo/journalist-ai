package com.dach.sagiri.news.model;

import java.time.Instant;

/**
 * One article as it came out of a feed, before summarising and formatting.
 *
 * @param externalId     RSS guid, or the canonical URL when the feed has no guid
 * @param rawDescription feed HTML, not yet cleaned
 * @param imageUrl       nullable
 */
public record NewsItem(String externalId, String title, String url, String sourceName,
                       Instant publishedAt, String rawDescription, String imageUrl) {
}
