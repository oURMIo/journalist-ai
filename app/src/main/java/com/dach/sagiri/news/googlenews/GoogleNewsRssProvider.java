package com.dach.sagiri.news.googlenews;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.dach.sagiri.config.BotProperties;
import com.dach.sagiri.news.HttpFetcher;
import com.dach.sagiri.news.NewsProvider;
import com.dach.sagiri.news.model.NewsItem;
import com.dach.sagiri.news.model.TopicQuery;
import com.dach.sagiri.news.rss.FeedParser;

/**
 * Google News search RSS — the fallback that makes any subject work out of the
 * box, with no API key and no preset.
 * <p>
 * Two Google-specific quirks are handled: item links are redirect stubs on
 * {@code news.google.com}, and item titles carry a trailing {@code " - Source"}.
 */
@Component
public class GoogleNewsRssProvider implements NewsProvider {

    private static final Logger logger = LoggerFactory.getLogger(GoogleNewsRssProvider.class);

    /** English-language, US edition: the spec asks for English news. */
    private static final String LOCALE_SUFFIX = "&hl=en-US&gl=US&ceid=US:en";

    private static final Pattern TITLE_SOURCE_TAIL = Pattern.compile("\\s+-\\s+([^-]{2,60})$");
    private static final String GOOGLE_HOST = "news.google.com";

    private final HttpFetcher fetcher;
    private final FeedParser parser;
    private final GoogleNewsLinkDecoder decoder;
    private final BotProperties properties;

    /** Resolving a redirect costs a request; the same stub often repeats across runs. */
    private final Map<String, String> resolvedLinks = new ConcurrentHashMap<>();

    /** The bot runs for weeks, so the cache is capped rather than left to grow. */
    private static final int MAX_CACHED_LINKS = 2_000;

    public GoogleNewsRssProvider(HttpFetcher fetcher, FeedParser parser,
        GoogleNewsLinkDecoder decoder, BotProperties properties) {
        this.fetcher = fetcher;
        this.parser = parser;
        this.decoder = decoder;
        this.properties = properties;
    }

    @Override
    public String name() {
        return "google-news";
    }

    @Override
    public boolean supports(TopicQuery query) {
        return StringUtils.isNotBlank(query.query());
    }

    @Override
    public List<NewsItem> fetch(TopicQuery query, Instant since, int limit) {
        String url = searchUrl(query.query());
        return fetcher.getBytes(url)
            .map(bytes -> parser.parse(bytes, url))
            .orElseGet(List::of)
            .stream()
            .filter(item -> item.publishedAt().isAfter(since))
            .sorted(Comparator.comparing(NewsItem::publishedAt).reversed())
            .limit(limit)
            .map(this::normalize)
            .toList();
    }

    public String searchUrl(String query) {
        return properties.getGoogleNewsBaseUrl()
               + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
               + LOCALE_SUFFIX;
    }

    /** Splits the source out of the title and resolves the redirect link. */
    private NewsItem normalize(NewsItem item) {
        String title = item.title();
        String source = item.sourceName();

        Matcher matcher = TITLE_SOURCE_TAIL.matcher(title);
        if (matcher.find()) {
            title = title.substring(0, matcher.start()).strip();
            source = matcher.group(1).strip();
        }

        String url = resolveLink(item.url());
        // The description of a Google News item is the headline and the source
        // over again, wrapped in a link: text, but nothing to summarise.
        return new NewsItem(item.externalId(), title, url, source, item.publishedAt(), "",
            item.imageUrl());
    }

    private String resolveLink(String url) {
        if (!StringUtils.contains(url, GOOGLE_HOST)) {
            return url;
        }
        String cached = resolvedLinks.get(url);
        if (cached != null) {
            return cached;
        }

        // The decoder handles today's /rss/articles/ stubs and declines the rest,
        // which still resolve by following the redirect.
        String resolved = properties.isGoogleNewsDecodeLinks()
            ? decoder.decode(url).orElseGet(() -> fetcher.resolveFinalUrl(url))
            : fetcher.resolveFinalUrl(url);

        if (resolved.contains(GOOGLE_HOST)) {
            // Not cached: Google answering 429 once must not pin every link of the
            // day to a stub. The item is still published, with the link it had.
            logger.debug("Could not resolve Google News link {}", url);
            return resolved;
        }
        if (resolvedLinks.size() >= MAX_CACHED_LINKS) {
            resolvedLinks.clear();
        }
        resolvedLinks.put(url, resolved);
        return resolved;
    }
}
