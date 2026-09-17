package com.dach.sagiri.topic;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import com.dach.sagiri.config.BotProperties;
import com.dach.sagiri.config.TopicPresetsConfig;
import com.dach.sagiri.config.TopicPresetsConfig.Preset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** What a re-registration is allowed to overwrite. */
class TopicRegistryServiceTest {

    private static final long CHAT_ID = -1001L;
    private static final long THREAD_ID = 42L;

    private final TopicRepository repository = mock(TopicRepository.class);
    private final TopicRegistryService registry = new TopicRegistryService(repository,
        new TopicSlugParser(properties()), new TopicQueryResolver(presets()));

    private static BotProperties properties() {
        BotProperties properties = new BotProperties();
        properties.setTopicPrefix("news-");
        return properties;
    }

    private static TopicPresetsConfig presets() {
        Preset preset = new Preset();
        preset.setQuery("technology OR software");
        preset.setFeeds(List.of("https://example.com/feed"));
        TopicPresetsConfig presets = new TopicPresetsConfig();
        presets.setPresets(Map.of("it", preset));
        return presets;
    }

    private TopicEntity stored() {
        TopicEntity topic = new TopicEntity();
        topic.setId(7L);
        topic.setChatId(CHAT_ID);
        topic.setThreadId(THREAD_ID);
        topic.setSlug("java");
        topic.setQuery("jvm OR jdk");
        topic.setDomain("the Java programming language");
        topic.setProfileSource(TopicProfileSource.USER);
        topic.setActive(true);

        when(repository.findByChatIdAndThreadId(CHAT_ID, THREAD_ID)).thenReturn(Optional.of(topic));
        when(repository.findByChatIdAndSlugAndActiveTrue(any(Long.class), any()))
            .thenReturn(Optional.empty());
        when(repository.save(any(TopicEntity.class))).thenAnswer(call -> call.getArgument(0));
        return topic;
    }

    @Test
    void renaming_the_emoji_of_a_topic_keeps_the_query_its_reader_wrote() {
        TopicEntity topic = stored();

        Optional<TopicEntity> saved = registry.register(CHAT_ID, THREAD_ID, "☕ news-java", false);

        assertThat(saved).isPresent();
        assertThat(topic.getQuery()).isEqualTo("jvm OR jdk");
        assertThat(topic.getDomain()).isEqualTo("the Java programming language");
        assertThat(topic.getProfileSource()).isEqualTo(TopicProfileSource.USER);
    }

    @Test
    void renaming_to_another_subject_throws_the_old_one_away() {
        TopicEntity topic = stored();

        registry.register(CHAT_ID, THREAD_ID, "news-it", false);

        assertThat(topic.getSlug()).isEqualTo("it");
        assertThat(topic.getQuery())
            .as("the new subject starts from its preset, not from the old query")
            .isEqualTo("technology OR software");
        assertThat(topic.getDomain()).isNull();
        assertThat(topic.getProfileSource()).isEqualTo(TopicProfileSource.SLUG);
    }

    @Test
    void a_brand_new_topic_starts_from_its_slug() {
        when(repository.findByChatIdAndThreadId(CHAT_ID, THREAD_ID)).thenReturn(Optional.empty());
        when(repository.findByChatIdAndSlugAndActiveTrue(any(Long.class), any()))
            .thenReturn(Optional.empty());
        when(repository.save(any(TopicEntity.class))).thenAnswer(call -> call.getArgument(0));

        Optional<TopicEntity> saved = registry.register(CHAT_ID, THREAD_ID, "news-ai-safety", true);

        assertThat(saved).isPresent();
        assertThat(saved.get().getQuery()).isEqualTo("ai safety");
        assertThat(saved.get().getProfileSource()).isEqualTo(TopicProfileSource.SLUG);
    }
}
