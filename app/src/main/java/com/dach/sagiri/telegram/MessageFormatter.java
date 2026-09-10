package com.dach.sagiri.telegram;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
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

    private final DateTimeFormatter timestampFormat;
    private final ZoneId zone;

    public MessageFormatter(BotProperties properties) {
        this.zone = properties.zoneId();
        this.timestampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")
            .withZone(this.zone);
    }

    public String format(NewsItem item, String summary) {
        String title = HtmlText.escape(HtmlText.truncate(item.title(), MAX_TITLE));
        String body = HtmlText.escape(HtmlText.truncate(summary, MAX_SUMMARY));
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

    ZoneId zone() {
        return zone;
    }

    /** A URL sits inside an attribute, so quotes have to go as well. */
    private static String escapeUrl(String url) {
        return HtmlText.escape(url).replace("\"", "&quot;");
    }
}
