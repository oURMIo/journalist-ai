package com.dach.sagiri.topic;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.dach.sagiri.config.MessageTemplates;
import com.dach.sagiri.news.model.TopicQuery;
import com.dach.sagiri.telegram.TelegramSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** When a topic learns what it is about, and who is told. */
class TopicProfileServiceTest {

    private static final long CHAT_ID = -1001L;
    private static final long THREAD_ID = 42L;

    private static final TopicProfile JAVA = new TopicProfile(
        "the Java programming language", "\"java programming\" OR jvm -volcano");

    private final TopicRepository repository = mock(TopicRepository.class);
    private final TopicQueryResolver queryResolver = mock(TopicQueryResolver.class);
    private final TopicProfiler profiler = mock(TopicProfiler.class);
    private final TelegramSender sender = mock(TelegramSender.class);
    private final MessageTemplates messages = messages();

    private final TopicProfileService service =
        new TopicProfileService(repository, queryResolver, profiler, sender, messages);

    private final TopicEntity topic = topic();

    private static MessageTemplates messages() {
        MessageTemplates messages = new MessageTemplates();
        messages.setTopicProfileDerived("{slug} is about {domain}, searching {query}");
        return messages;
    }

    private static TopicEntity topic() {
        TopicEntity topic = new TopicEntity();
        topic.setId(7L);
        topic.setChatId(CHAT_ID);
        topic.setThreadId(THREAD_ID);
        topic.setSlug("java");
        topic.setQuery("java");
        return topic;
    }

    private void derives() {
        when(profiler.available()).thenReturn(true);
        when(profiler.derive("java")).thenReturn(Optional.of(JAVA));
        when(queryResolver.resolve(topic))
            .thenAnswer(call -> new TopicQuery("java", topic.getQuery(), List.of(), false));
    }

    @Test
    void the_first_cycle_derives_the_subject_stores_it_and_says_so_in_the_topic() {
        derives();
        when(repository.applyDerivedProfile(eq(7L), eq(JAVA.domain()), eq(JAVA.query()), any()))
            .thenReturn(1);

        TopicQuery query = service.queryFor(topic);

        assertThat(query.query()).isEqualTo(JAVA.query());
        assertThat(topic.getDomain()).isEqualTo(JAVA.domain());
        assertThat(topic.getProfileSource()).isEqualTo(TopicProfileSource.MODEL);

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(sender).reply(eq(CHAT_ID), eq(THREAD_ID), text.capture());
        assertThat(text.getValue())
            .isEqualTo("java is about the Java programming language, "
                       + "searching \"java programming\" OR jvm -volcano");
    }

    @Test
    void a_topic_that_already_knows_its_subject_costs_no_model_call() {
        topic.setDomain(JAVA.domain());
        topic.setQuery(JAVA.query());
        topic.setProfileSource(TopicProfileSource.MODEL);
        when(profiler.available()).thenReturn(true);
        when(queryResolver.resolve(topic))
            .thenReturn(new TopicQuery("java", JAVA.query(), List.of(), false));

        assertThat(service.queryFor(topic).query()).isEqualTo(JAVA.query());

        verify(profiler, never()).derive(any());
        verifyNoInteractions(sender);
    }

    @Test
    void the_cycle_that_loses_the_race_announces_nothing() {
        derives();
        when(repository.applyDerivedProfile(any(), any(), any(), any())).thenReturn(0);

        service.queryFor(topic);

        assertThat(topic.getProfileSource())
            .as("the winner's profile is what the next read will bring")
            .isEqualTo(TopicProfileSource.SLUG);
        verifyNoInteractions(sender);
    }

    @Test
    void a_topic_whose_reader_wrote_the_query_is_never_read_by_the_model_again() {
        topic.setQuery("jvm OR jdk");
        topic.setProfileSource(TopicProfileSource.USER);
        when(profiler.available()).thenReturn(true);
        when(queryResolver.resolve(topic))
            .thenReturn(new TopicQuery("java", "jvm OR jdk", List.of(), false));

        TopicQuery query = service.queryFor(topic);

        assertThat(query.query()).isEqualTo("jvm OR jdk");
        assertThat(topic.getDomain()).as("no subject, so no subject to block by").isNull();
        verify(profiler, never()).derive(any());
        verify(repository, never()).applyDerivedProfile(any(), any(), any(), any());
        verifyNoInteractions(sender);
    }

    @Test
    void a_preset_topic_is_told_the_query_it_will_really_be_polled_with() {
        derives();
        when(repository.applyDerivedProfile(any(), any(), any(), any())).thenReturn(1);
        when(queryResolver.resolve(topic))
            .thenReturn(new TopicQuery("java", "technology OR software",
                List.of("https://example.com/feed"), true));

        service.queryFor(topic);

        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(sender).reply(eq(CHAT_ID), eq(THREAD_ID), text.capture());
        assertThat(text.getValue())
            .as("a preset outranks the derived query, so that is what to announce")
            .contains("searching technology OR software")
            .doesNotContain(JAVA.query());
    }

    @Test
    void without_a_model_the_topic_polls_exactly_as_it_did_before() {
        when(profiler.available()).thenReturn(false);
        when(queryResolver.resolve(topic)).thenReturn(new TopicQuery("java", "java", List.of(), false));

        assertThat(service.queryFor(topic).query()).isEqualTo("java");

        verify(profiler, never()).derive(any());
        verifyNoInteractions(repository);
        verifyNoInteractions(sender);
    }

    @Test
    void a_model_that_answers_nothing_leaves_the_topic_on_its_slug() {
        when(profiler.available()).thenReturn(true);
        when(profiler.derive("java")).thenReturn(Optional.empty());
        when(queryResolver.resolve(topic)).thenReturn(new TopicQuery("java", "java", List.of(), false));

        assertThat(service.queryFor(topic).query()).isEqualTo("java");

        assertThat(topic.getProfileSource()).isEqualTo(TopicProfileSource.SLUG);
        verifyNoInteractions(repository);
    }

    @Test
    void a_query_set_by_hand_takes_the_misread_subject_down_with_it() {
        topic.setDomain("the Indonesian island of Java and its volcanoes");
        topic.setProfileSource(TopicProfileSource.MODEL);

        service.setQuery(topic, "jvm OR jdk");

        verify(repository).setProfile(eq(7L), eq(null), eq("jvm OR jdk"),
            eq(TopicProfileSource.USER), any(Instant.class));
        assertThat(topic.getDomain())
            .as("a wrong subject would block exactly what the new query finds")
            .isNull();
        assertThat(topic.getQuery()).isEqualTo("jvm OR jdk");
        assertThat(topic.getProfileSource()).isEqualTo(TopicProfileSource.USER);
    }

    @Test
    void a_reset_drops_both_halves_so_the_next_cycle_derives_them_again() {
        topic.setDomain(JAVA.domain());
        topic.setQuery(JAVA.query());
        topic.setProfileSource(TopicProfileSource.USER);
        when(queryResolver.resolve("java")).thenReturn(new TopicQuery("java", "java", List.of(), false));

        service.resetQuery(topic);

        verify(repository).setProfile(eq(7L), eq(null), eq("java"),
            eq(TopicProfileSource.SLUG), any(Instant.class));
        assertThat(topic.getDomain()).isNull();
        assertThat(topic.getQuery()).isEqualTo("java");
        assertThat(topic.getProfileSource()).isEqualTo(TopicProfileSource.SLUG);
    }
}
