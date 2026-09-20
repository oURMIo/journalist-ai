package com.dach.sagiri.news;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.dach.sagiri.config.BotProperties;
import com.dach.sagiri.news.model.NewsItem;

/**
 * Reads enough of an article page to summarise it when the feed itself carried
 * almost nothing — Google News descriptions are link stubs, and a model given a
 * bare headline answers that it has no information.
 * <p>
 * Only what a page offers every link preview (its {@code og:description}) plus
 * the leading paragraphs are taken, capped at {@code maxChars}; the article is
 * not mirrored. Any failure returns an empty string: the summary is worth one
 * request, never a broken polling cycle.
 */
@Component
public class ArticleContextFetcher {

    private static final Logger logger = LoggerFactory.getLogger(ArticleContextFetcher.class);

    /** Shorter lines in an article body are captions, bylines and cookie notices. */
    private static final int MIN_PARAGRAPH_CHARS = 80;

    /**
     * A page served without a Content-Length is not capped by {@link HttpFetcher},
     * so what Jsoup is asked to parse is bounded here as well.
     */
    private static final int MAX_PARSED_BYTES = 2 * 1024 * 1024;

    /**
     * A Google News link the provider could not resolve still points at an
     * interstitial: its description belongs to Google, not to the article.
     */
    private static final String GOOGLE_NEWS_HOST = "news.google.com";

    private final HttpFetcher fetcher;
    private final BotProperties.ArticleContext settings;

    public ArticleContextFetcher(HttpFetcher fetcher, BotProperties properties) {
        this.fetcher = fetcher;
        this.settings = properties.getArticleContext();
    }

    /**
     * @param feedText the feed description, already stripped of HTML
     * @return extra text for the summariser, or an empty string when the feed
     *     said enough, the feature is off, or the page could not be read
     */
    public String contextFor(NewsItem item, String feedText) {
        if (!settings.isEnabled()
            || StringUtils.length(feedText) >= settings.getMinDescriptionChars()
            || StringUtils.isBlank(item.url())
            || StringUtils.contains(item.url(), GOOGLE_NEWS_HOST)) {
            return "";
        }

        String context = fetcher.getPage(item.url())
            .map(page -> extract(page, item.url()))
            .orElse("");
        if (context.isEmpty()) {
            logger.debug("No extra context for '{}' from {}", item.title(), item.url());
        }
        return context;
    }

    private String extract(byte[] page, String url) {
        try (ByteArrayInputStream stream = new ByteArrayInputStream(page, 0,
            Math.min(page.length, MAX_PARSED_BYTES))) {

            Document document = Jsoup.parse(stream, null, url);
            StringBuilder text = new StringBuilder();
            append(text, document.select("meta[property=og:description]").attr("content"));
            append(text, document.select("meta[name=description]").attr("content"));
            for (Element paragraph : body(document).select("p")) {
                if (text.length() >= settings.getMaxChars()) {
                    break;
                }
                String line = paragraph.text().strip();
                if (line.length() >= MIN_PARAGRAPH_CHARS) {
                    append(text, line);
                }
            }
            // abbreviate() throws below 4: a tiny maxChars must not disable the feature.
            return StringUtils.abbreviate(text.toString().strip(), "…",
                Math.max(2, settings.getMaxChars()));
        } catch (IOException | RuntimeException e) {
            logger.debug("Could not read the article at {}: {}", url, e.toString());
            return "";
        }
    }

    /** Prefer the article element: a whole page is mostly navigation and teasers. */
    private static Element body(Document document) {
        for (String selector : List.of("article", "main", "[itemprop=articleBody]")) {
            Element element = document.selectFirst(selector);
            if (element != null) {
                return element;
            }
        }
        return document.body() == null ? document : document.body();
    }

    /** The preview text usually repeats the opening paragraph; keep it once. */
    private static void append(StringBuilder text, String line) {
        String candidate = StringUtils.normalizeSpace(line);
        if (candidate.isEmpty() || text.indexOf(candidate) >= 0) {
            return;
        }
        if (text.length() > 0) {
            text.append(' ');
        }
        text.append(candidate);
    }
}
