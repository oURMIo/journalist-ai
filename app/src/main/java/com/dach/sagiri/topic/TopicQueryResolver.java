package com.dach.sagiri.topic;

import java.util.List;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import com.dach.sagiri.config.TopicPresetsConfig;
import com.dach.sagiri.config.TopicPresetsConfig.Preset;
import com.dach.sagiri.news.model.TopicQuery;

/**
 * Maps a subject to the sources to poll.
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

    /** What a subject resolves to before anything is known about the topic itself. */
    public TopicQuery resolve(String slug) {
        return presets.find(slug)
            .map(preset -> new TopicQuery(slug, preset.getQuery(), preset.getFeeds(), true))
            .orElseGet(() -> new TopicQuery(slug, fallbackQuery(slug), List.of(), false));
    }

    /**
     * What a registered topic resolves to now: a query written by hand wins over
     * the curated preset, the preset over one the model derived from the slug, and
     * the bare slug is the last resort. Feeds only ever come from a preset.
     */
    public TopicQuery resolve(TopicEntity topic) {
        String slug = topic.getSlug();
        Optional<Preset> preset = presets.find(slug);
        String stored = StringUtils.trimToEmpty(topic.getQuery());
        boolean written = topic.getProfileSource() != TopicProfileSource.SLUG
                          && !stored.isEmpty();

        String query;
        if (written && topic.getProfileSource() == TopicProfileSource.USER) {
            query = stored;
        } else if (preset.isPresent()) {
            query = preset.get().getQuery();
        } else {
            query = written ? stored : fallbackQuery(slug);
        }
        return new TopicQuery(slug, query, preset.map(Preset::getFeeds).orElseGet(List::of),
            preset.isPresent());
    }

    private static String fallbackQuery(String slug) {
        return slug.replace('-', ' ');
    }
}
