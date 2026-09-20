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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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

    @Test
    void renaming_a_closed_topic_does_not_reopen_it() {
        TopicEntity topic = stored();
        topic.setClosed(true);

        Optional<TopicEntity> saved = registry.register(CHAT_ID, THREAD_ID, "news-it", false);

        assertThat(saved).isPresent();
        assertThat(saved.get().isClosed())
            .as("a rename decides 'is this topic ours', never whether Telegram closed it")
            .isTrue();
        assertThat(saved.get().isActive()).isTrue();
    }

    @Test
    void closing_a_known_topic_sets_the_flag_and_leaves_active_alone() {
        TopicEntity topic = stored();

        assertThat(registry.setClosed(CHAT_ID, THREAD_ID, true)).isTrue();

        assertThat(topic.isClosed()).isTrue();
        assertThat(topic.isActive()).isTrue();
        verify(repository).save(topic);
    }

    @Test
    void reopening_a_closed_topic_clears_the_flag() {
        TopicEntity topic = stored();
        topic.setClosed(true);

        assertThat(registry.setClosed(CHAT_ID, THREAD_ID, false)).isTrue();

        assertThat(topic.isClosed()).isFalse();
        verify(repository).save(topic);
    }

    @Test
    void closing_an_unknown_topic_reports_false_and_saves_nothing() {
        when(repository.findByChatIdAndThreadId(CHAT_ID, THREAD_ID)).thenReturn(Optional.empty());

        assertThat(registry.setClosed(CHAT_ID, THREAD_ID, true)).isFalse();

        verify(repository, never()).save(any(TopicEntity.class));
    }

    @Test
    void pollable_topics_are_the_active_and_open_ones() {
        TopicEntity open = new TopicEntity();
        when(repository.findByActiveTrueAndClosedFalseOrderByChatIdAscThreadIdAsc())
            .thenReturn(List.of(open));

        assertThat(registry.pollableTopics()).containsExactly(open);
    }

    @Test
    void an_active_and_open_topic_is_reported_pollable() {
        when(repository.existsByIdAndActiveTrueAndClosedFalse(7L)).thenReturn(true);

        assertThat(registry.isPollable(7L)).isTrue();
    }

    @Test
    void a_closed_inactive_or_unknown_topic_is_not_pollable() {
        when(repository.existsByIdAndActiveTrueAndClosedFalse(7L)).thenReturn(false);

        assertThat(registry.isPollable(7L)).isFalse();
    }

    @Test
    void a_null_id_is_not_pollable_and_never_reaches_the_database() {
        assertThat(registry.isPollable(null)).isFalse();

        verifyNoInteractions(repository);
    }
}
