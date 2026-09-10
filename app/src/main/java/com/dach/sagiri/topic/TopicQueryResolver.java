package com.dach.sagiri.topic;

import java.util.List;
import org.springframework.stereotype.Component;
import com.dach.sagiri.config.TopicPresetsConfig;
import com.dach.sagiri.news.model.TopicQuery;

/**
 * Maps a subject slug to the sources to poll.
 * <p>
 * A preset gives curated feeds; anything else falls back to a plain search
 * query, which is what makes an arbitrary new topic work with no code change.
 */
@Component
public class TopicQueryResolver {

    private final TopicPresetsConfig presets;

    public TopicQueryResolver(TopicPresetsConfig presets) {
        this.presets = presets;
    }

    public TopicQuery resolve(String slug) {
        return presets.find(slug)
            .map(preset -> new TopicQuery(slug, preset.getQuery(), preset.getFeeds(), true))
            .orElseGet(() -> new TopicQuery(slug, fallbackQuery(slug), List.of(), false));
    }

    private static String fallbackQuery(String slug) {
        return slug.replace('-', ' ');
    }
}
