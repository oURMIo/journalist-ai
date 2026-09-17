package com.dach.sagiri.topic;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import com.dach.sagiri.config.TopicPresetsConfig;
import com.dach.sagiri.config.TopicPresetsConfig.Preset;
import com.dach.sagiri.news.model.TopicQuery;

import static org.assertj.core.api.Assertions.assertThat;

/** Which of the four possible queries a topic actually searches with. */
class TopicQueryResolverTest {

    private final TopicQueryResolver resolver = resolver();

    private static TopicQueryResolver resolver() {
        Preset preset = new Preset();
        preset.setQuery("technology OR software");
        preset.setFeeds(List.of("https://example.com/feed"));
        TopicPresetsConfig presets = new TopicPresetsConfig();
        presets.setPresets(Map.of("it", preset));
        return new TopicQueryResolver(presets);
    }

    private static TopicEntity topic(String slug, String query, TopicProfileSource source) {
        TopicEntity topic = new TopicEntity();
        topic.setSlug(slug);
        topic.setQuery(query);
        topic.setProfileSource(source);
        return topic;
    }

    @Test
    void a_topic_nobody_touched_searches_for_its_own_name() {
        TopicQuery query = resolver.resolve(topic("ai-safety", "ai safety", TopicProfileSource.SLUG));

        assertThat(query.query()).isEqualTo("ai safety");
        assertThat(query.feeds()).isEmpty();
        assertThat(query.preset()).isFalse();
    }

    @Test
    void a_derived_query_replaces_the_bare_slug() {
        TopicQuery query = resolver.resolve(
            topic("java", "\"java programming\" OR jvm", TopicProfileSource.MODEL));

        assertThat(query.query()).isEqualTo("\"java programming\" OR jvm");
    }

    @Test
    void a_preset_outranks_what_the_model_derived_and_brings_the_feeds() {
        TopicQuery query = resolver.resolve(topic("it", "derived it query", TopicProfileSource.MODEL));

        assertThat(query.query()).isEqualTo("technology OR software");
        assertThat(query.feeds()).containsExactly("https://example.com/feed");
        assertThat(query.preset()).isTrue();
    }

    @Test
    void a_query_written_by_hand_outranks_everything_but_keeps_the_curated_feeds() {
        TopicQuery query = resolver.resolve(topic("it", "kubernetes OR devops", TopicProfileSource.USER));

        assertThat(query.query()).isEqualTo("kubernetes OR devops");
        assertThat(query.feeds()).containsExactly("https://example.com/feed");
    }

    @Test
    void an_empty_stored_query_falls_back_to_the_slug() {
        TopicQuery query = resolver.resolve(topic("ai-safety", "  ", TopicProfileSource.USER));

        assertThat(query.query()).isEqualTo("ai safety");
    }
}
