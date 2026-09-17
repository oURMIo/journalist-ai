package com.dach.sagiri.telegram.command;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.dach.sagiri.config.MessageTemplates;
import com.dach.sagiri.news.model.TopicQuery;
import com.dach.sagiri.telegram.TelegramSender;
import com.dach.sagiri.topic.TopicEntity;
import com.dach.sagiri.topic.TopicProfileService;
import com.dach.sagiri.topic.TopicProfileSource;
import com.dach.sagiri.topic.TopicQueryResolver;
import com.dach.sagiri.topic.TopicRegistryService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Seeing and overruling what the bot thinks a topic is about. */
class QueryCommandTest {

    private static final long CHAT_ID = -1001L;
    private static final long THREAD_ID = 42L;

    private final TelegramSender sender = mock(TelegramSender.class);
    private final TopicRegistryService registry = mock(TopicRegistryService.class);
    private final TopicQueryResolver queryResolver = mock(TopicQueryResolver.class);
    private final TopicProfileService profiles = mock(TopicProfileService.class);
    private final MessageTemplates messages = messages();
    private final QueryCommand command =
        new QueryCommand(sender, messages, registry, queryResolver, profiles);

    private final TopicEntity topic = topic();

    private static MessageTemplates messages() {
        MessageTemplates messages = new MessageTemplates();
        messages.setNotInTopic("not-in-topic");
        messages.setUnbindUnknown("unknown-topic");
        messages.setQueryHeader("{slug} | {domain} | {query} | {source}");
        messages.setQueryFeeds("feeds {count}");
        messages.setQueryDomainUnknown("not-known-yet");
        messages.setQueryDomainDropped("subject-dropped");
        messages.setQuerySourceSlug("from-slug");
        messages.setQuerySourcePreset("from-preset");
        messages.setQuerySourceModel("from-model");
        messages.setQuerySourceUser("by-hand");
        messages.setQueryUsage("usage");
        messages.setQuerySet("set {query}");
        messages.setQueryReset("reset");
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

    private CommandContext context(String... args) {
        when(registry.find(CHAT_ID, THREAD_ID)).thenReturn(Optional.of(topic));
        return new CommandContext(null, CHAT_ID, THREAD_ID, "/query", List.of(args), true);
    }

    private void resolvesTo(String query, List<String> feeds, boolean preset) {
        when(queryResolver.resolve(topic))
            .thenAnswer(call -> new TopicQuery("java", query, feeds, preset));
    }

    private String reply() {
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(sender).reply(eq(CHAT_ID), eq(THREAD_ID), text.capture());
        return text.getValue();
    }

    @Test
    void outside_a_topic_it_explains_where_to_send_the_command() {
        command.execute(new CommandContext(null, CHAT_ID, null, "/query", List.of(), true));

        verify(sender).reply(CHAT_ID, null, "not-in-topic");
        verifyNoInteractions(profiles);
    }

    @Test
    void an_unregistered_topic_is_reported_instead_of_being_searched_for() {
        when(registry.find(CHAT_ID, THREAD_ID)).thenReturn(Optional.empty());

        command.execute(new CommandContext(null, CHAT_ID, THREAD_ID, "/query", List.of(), true));

        assertThat(reply()).isEqualTo("unknown-topic");
    }

    @Test
    void it_shows_the_stored_subject_without_waiting_for_a_model() {
        topic.setDomain("the Java programming language");
        topic.setQuery("\"java programming\" OR jvm");
        topic.setProfileSource(TopicProfileSource.MODEL);
        resolvesTo("\"java programming\" OR jvm", List.of(), false);

        command.execute(context());

        assertThat(reply())
            .contains("java | the Java programming language "
                      + "| \"java programming\" OR jvm | from-model")
            .contains("usage");
        verifyNoInteractions(profiles);
    }

    @Test
    void a_topic_that_was_never_read_says_so_and_names_its_source() {
        resolvesTo("java", List.of(), false);

        command.execute(context());

        assertThat(reply()).contains("java | not-known-yet | java | from-slug");
    }

    @Test
    void a_hand_written_query_reports_that_the_subject_is_no_longer_used() {
        topic.setQuery("jvm OR jdk");
        topic.setProfileSource(TopicProfileSource.USER);
        resolvesTo("jvm OR jdk", List.of(), false);

        command.execute(context());

        assertThat(reply())
            .as("it will not be derived again, so it must not promise that")
            .contains("java | subject-dropped | jvm OR jdk | by-hand")
            .doesNotContain("not-known-yet");
    }

    @Test
    void a_preset_topic_reports_its_curated_feeds() {
        topic.setSlug("it");
        resolvesTo("technology OR software", List.of("https://example.com/feed"), true);

        command.execute(context());

        assertThat(reply()).contains("from-preset").contains("feeds 1");
    }

    @Test
    void a_query_typed_by_hand_is_stored_and_read_back() {
        resolvesTo("jvm OR jdk", List.of(), false);

        command.execute(context("jvm", "OR", "jdk"));

        verify(profiles).setQuery(topic, "jvm OR jdk");
        assertThat(reply()).contains("set jvm OR jdk");
    }

    @Test
    void auto_gives_the_topic_back_to_the_model() {
        resolvesTo("java", List.of(), false);

        command.execute(context("auto"));

        verify(profiles).resetQuery(topic);
        verify(profiles, never()).setQuery(any(), any());
        assertThat(reply()).contains("reset");
    }
}
