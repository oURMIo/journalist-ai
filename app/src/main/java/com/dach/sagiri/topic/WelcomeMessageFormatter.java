package com.dach.sagiri.topic;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import com.dach.sagiri.config.BotProperties;
import com.dach.sagiri.config.MessageTemplates;
import com.dach.sagiri.news.model.TopicQuery;
import com.dach.sagiri.telegram.HtmlText;

/**
 * Builds the pinned message that explains what a news topic is for. The wording
 * lives in {@code messages.yml}; only the substitutions are computed here.
 */
@Component
public class WelcomeMessageFormatter {

    private static final int MAX_LISTED_SOURCES = 3;

    private final MessageTemplates messages;
    private final BotProperties properties;

    public WelcomeMessageFormatter(MessageTemplates messages, BotProperties properties) {
        this.messages = messages;
        this.properties = properties;
    }

    public String format(TopicQuery query) {
        return MessageTemplates.render(messages.getWelcome(), Map.of(
            "slug", HtmlText.escape(TopicSlugParser.displayName(query.slug())),
            "sources", describeSources(query),
            "interval", properties.getPollIntervalMinutes(),
            "maxItems", properties.getMaxItemsPerTopic(),
            "prefix", HtmlText.escape(properties.getTopicPrefix())));
    }

    /** Preset topics list their feed domains; fallback topics say so plainly. */
    String describeSources(TopicQuery query) {
        if (!query.preset() || query.feeds().isEmpty()) {
            return messages.getSourcesFallback();
        }
        Set<String> domains = query.feeds().stream()
            .map(WelcomeMessageFormatter::domainOf)
            .filter(domain -> !domain.isBlank())
            .collect(Collectors.toCollection(LinkedHashSet::new));

        String listed = domains.stream()
            .limit(MAX_LISTED_SOURCES)
            .map(HtmlText::escape)
            .collect(Collectors.joining(", "));

        int rest = domains.size() - MAX_LISTED_SOURCES;
        if (rest <= 0) {
            return listed;
        }
        return listed + " " + MessageTemplates.render(messages.getSourcesMore(),
            Map.of("count", rest));
    }

    private static String domainOf(String feedUrl) {
        try {
            String host = URI.create(feedUrl).getHost();
            return host == null ? "" : host.replaceFirst("^www\\.", "");
        } catch (IllegalArgumentException e) {
            return "";
        }
    }
}
