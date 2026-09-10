package com.dach.sagiri.news.rss;

import com.rometools.rome.feed.synd.SyndContent;
import com.rometools.rome.feed.synd.SyndEnclosure;
import com.rometools.rome.feed.synd.SyndEntry;
import com.rometools.rome.feed.synd.SyndFeed;
import com.rometools.rome.io.SyndFeedInput;
import com.rometools.rome.io.XmlReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.dach.sagiri.news.model.NewsItem;

/**
 * Rome-backed RSS/Atom parsing.
 * <p>
 * Feeds in the wild are sloppy: missing or unparseable dates, no guid, HTML in
 * the description. Every one of those is handled here so that the callers only
 * ever see well-formed {@link NewsItem}s.
 */
@Component
public class FeedParser {

    private static final Logger logger = LoggerFactory.getLogger(FeedParser.class);

    /**
     * @param sourceUrl only used to derive a source name when the feed omits its title
     * @return parsed items, empty when the payload is not a usable feed
     */
    public List<NewsItem> parse(byte[] payload, String sourceUrl) {
        try (InputStream stream = new ByteArrayInputStream(payload)) {
            SyndFeedInput input = new SyndFeedInput();
            // The bot fetches arbitrary third-party URLs: no external entities.
            input.setAllowDoctypes(false);
            SyndFeed feed = input.build(new XmlReader(stream));

            String feedTitle = StringUtils.defaultIfBlank(feed.getTitle(), hostOf(sourceUrl));
            List<NewsItem> items = new ArrayList<>(feed.getEntries().size());
            for (SyndEntry entry : feed.getEntries()) {
                toNewsItem(entry, feedTitle).ifPresent(items::add);
            }
            return items;
        } catch (Exception e) {
            // Broken XML is routine; it must not take the polling cycle down.
            logger.warn("Could not parse feed {}: {}", sourceUrl, e.toString());
            return List.of();
        }
    }

    private static Optional<NewsItem> toNewsItem(SyndEntry entry, String feedTitle) {
        String title = StringUtils.trimToEmpty(entry.getTitle());
        String link = StringUtils.trimToEmpty(entry.getLink());
        if (title.isEmpty() || link.isEmpty()) {
            return Optional.empty();
        }
        String externalId = StringUtils.defaultIfBlank(entry.getUri(), link);
        return Optional.of(new NewsItem(
            externalId,
            title,
            link,
            feedTitle,
            publishedAt(entry),
            description(entry),
            imageUrl(entry)));
    }

    /** A missing or unparseable date falls back to now instead of dropping the item. */
    private static Instant publishedAt(SyndEntry entry) {
        Date date = entry.getPublishedDate() != null
            ? entry.getPublishedDate()
            : entry.getUpdatedDate();
        return date == null ? Instant.now() : date.toInstant();
    }

    private static String description(SyndEntry entry) {
        SyndContent description = entry.getDescription();
        if (description != null && StringUtils.isNotBlank(description.getValue())) {
            return description.getValue();
        }
        return entry.getContents().stream()
            .map(SyndContent::getValue)
            .filter(StringUtils::isNotBlank)
            .findFirst()
            .orElse("");
    }

    private static String imageUrl(SyndEntry entry) {
        return entry.getEnclosures().stream()
            .filter(enclosure -> StringUtils.startsWith(enclosure.getType(), "image/"))
            .map(SyndEnclosure::getUrl)
            .filter(StringUtils::isNotBlank)
            .findFirst()
            .orElse(null);
    }

    static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? "unknown" : host.replaceFirst("^www\\.", "");
        } catch (IllegalArgumentException e) {
            return "unknown";
        }
    }
}
