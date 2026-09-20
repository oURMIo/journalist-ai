package com.dach.sagiri.telegram;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import com.dach.sagiri.config.BotProperties;
import com.dach.sagiri.news.model.NewsItem;

/**
 * Renders a news item into the HTML message described in spec section 7.
 * MarkdownV2 is deliberately not used — its escaping rules are a minefield.
 */
@Component
public class MessageFormatter {

    static final int MAX_TITLE = 200;
    static final int MAX_SUMMARY = 500;

    /** Below this a recap that restates the headline has added nothing. */
    static final int MIN_ADDED_CHARS = 20;

    private final DateTimeFormatter timestampFormat;
    private final ZoneId zone;

    public MessageFormatter(BotProperties properties) {
        this.zone = properties.zoneId();
        this.timestampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")
            .withZone(this.zone);
    }

    public String format(NewsItem item, String summary) {
        String title = HtmlText.escape(HtmlText.truncate(item.title(), MAX_TITLE));
        String body = HtmlText.escape(
            HtmlText.truncate(recap(item.title(), summary), MAX_SUMMARY));
        String source = HtmlText.escape(StringUtils.defaultIfBlank(item.sourceName(), "unknown"));
        String published = timestampFormat.format(item.publishedAt());

        StringBuilder html = new StringBuilder()
            .append("🗞 <b>").append(title).append("</b>\n\n");
        if (StringUtils.isNotBlank(body)) {
            html.append(body).append("\n\n");
        }
        html.append("🕒 ").append(published).append("  •  📰 ").append(source).append('\n')
            .append("🔗 <a href=\"").append(escapeUrl(item.url()))
            .append("\">Read full article</a>");

        return HtmlText.fitMessage(html.toString());
    }

    /**
     * A feed that offers no description leaves the summarisers with the headline
     * alone, and the headline again under the headline is noise. A recap that
     * opens with the headline and then goes on saying something is kept: that is
     * what a fetched article context usually looks like.
     */
    static String recap(String title, String summary) {
        String normalized = normalize(summary);
        String headline = normalize(title);
        if (normalized.isEmpty()) {
            return "";
        }
        if (headline.isEmpty()) {
            return summary;
        }
        boolean repeats = headline.startsWith(normalized)
                          || (normalized.startsWith(headline)
                              && normalized.length() - headline.length() < MIN_ADDED_CHARS);
        return repeats ? "" : summary;
    }

    /** Letters and digits of any script: a Cyrillic headline must normalise too. */
    private static String normalize(String text) {
        return StringUtils.trimToEmpty(text).toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{L}\\p{N}]+", " ").strip();
    }

    ZoneId zone() {
        return zone;
    }

    /** A URL sits inside an attribute, so quotes have to go as well. */
    private static String escapeUrl(String url) {
        return HtmlText.escape(url).replace("\"", "&quot;");
    }
}
