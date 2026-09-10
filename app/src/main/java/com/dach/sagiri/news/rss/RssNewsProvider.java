package com.dach.sagiri.news.rss;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.dach.sagiri.config.BotProperties;
import com.dach.sagiri.news.HttpFetcher;
import com.dach.sagiri.news.NewsProvider;
import com.dach.sagiri.news.model.NewsItem;
import com.dach.sagiri.news.model.TopicQuery;

/**
 * Pulls the curated feeds of a preset topic. Feeds are fetched concurrently on
 * virtual threads, capped so the bot is not rude to the hosts it reads from.
 */
@Component
public class RssNewsProvider implements NewsProvider {

    private static final Logger logger = LoggerFactory.getLogger(RssNewsProvider.class);

    private final HttpFetcher fetcher;
    private final FeedParser parser;
    private final int maxParallel;

    public RssNewsProvider(HttpFetcher fetcher, FeedParser parser, BotProperties properties) {
        this.fetcher = fetcher;
        this.parser = parser;
        this.maxParallel = Math.max(1, properties.getHttp().getMaxParallelFeeds());
    }

    @Override
    public String name() {
        return "rss";
    }

    @Override
    public boolean supports(TopicQuery query) {
        return !query.feeds().isEmpty();
    }

    @Override
    public List<NewsItem> fetch(TopicQuery query, Instant since, int limit) {
        Semaphore permits = new Semaphore(maxParallel);
        List<NewsItem> collected = new ArrayList<>();

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<List<NewsItem>>> futures = query.feeds().stream()
                .map(feedUrl -> executor.<List<NewsItem>>submit(() -> {
                    permits.acquire();
                    try {
                        return fetchOne(feedUrl);
                    } finally {
                        permits.release();
                    }
                }))
                .toList();

            for (Future<List<NewsItem>> future : futures) {
                try {
                    collected.addAll(future.get());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    // One dead feed must not sink the whole run.
                    logger.warn("Feed task failed for topic '{}': {}", query.slug(), e.toString());
                }
            }
        }

        return collected.stream()
            .filter(item -> item.publishedAt().isAfter(since))
            .sorted(Comparator.comparing(NewsItem::publishedAt).reversed())
            .limit(limit)
            .toList();
    }

    private List<NewsItem> fetchOne(String feedUrl) {
        return fetcher.getBytes(feedUrl)
            .map(bytes -> parser.parse(bytes, feedUrl))
            .orElseGet(List::of);
    }
}
