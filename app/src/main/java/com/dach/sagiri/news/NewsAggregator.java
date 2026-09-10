package com.dach.sagiri.news;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.dach.sagiri.dedup.Fingerprints;
import com.dach.sagiri.dedup.UrlCanonicalizer;
import com.dach.sagiri.news.model.NewsItem;
import com.dach.sagiri.news.model.TopicQuery;

/**
 * Runs every provider that supports a topic and merges their output into one
 * freshest-first list with in-batch duplicates removed.
 */
@Component
public class NewsAggregator {

    private static final Logger logger = LoggerFactory.getLogger(NewsAggregator.class);

    private final List<NewsProvider> providers;
    private final UrlCanonicalizer canonicalizer;

    public NewsAggregator(List<NewsProvider> providers, UrlCanonicalizer canonicalizer) {
        this.providers = providers;
        this.canonicalizer = canonicalizer;
    }

    public List<NewsItem> collect(TopicQuery query, Instant since, int limit) {
        List<NewsItem> collected = new ArrayList<>();
        for (NewsProvider provider : providers) {
            if (!provider.supports(query)) {
                continue;
            }
            try {
                List<NewsItem> items = provider.fetch(query, since, limit);
                logger.debug("Provider {} returned {} item(s) for '{}'", provider.name(),
                    items.size(), query.slug());
                collected.addAll(items);
            } catch (RuntimeException e) {
                // A provider blowing up degrades quality, it does not stop the cycle.
                logger.warn("Provider {} failed for '{}': {}", provider.name(), query.slug(),
                    e.toString());
            }
        }
        return dedupe(collected).stream()
            .sorted(Comparator.comparing(NewsItem::publishedAt).reversed())
            .limit(limit)
            .toList();
    }

    /** Same story from two providers: keep whichever we saw first. */
    private List<NewsItem> dedupe(List<NewsItem> items) {
        Set<String> seenUrls = new HashSet<>();
        Set<String> seenTitles = new HashSet<>();
        List<NewsItem> unique = new ArrayList<>(items.size());
        for (NewsItem item : items) {
            // Both sets have to learn the item even when only one of them rejects it.
            boolean freshUrl = seenUrls.add(canonicalizer.canonicalize(item.url()));
            boolean freshTitle = seenTitles.add(Fingerprints.titleHash(item.title()));
            if (freshUrl && freshTitle) {
                unique.add(item);
            }
        }
        return unique;
    }
}
