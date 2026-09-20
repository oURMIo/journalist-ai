package com.dach.sagiri.telegram.command;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.dach.sagiri.config.MessageTemplates;
import com.dach.sagiri.telegram.TelegramSender;
import com.dach.sagiri.topic.TopicEntity;
import com.dach.sagiri.topic.TopicRegistryService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The registry dump is the only place the three topic states are visible. */
class TopicsCommandTest {

    private static final long CHAT_ID = -1001L;
    private static final long THREAD_ID = 42L;

    private final TelegramSender sender = mock(TelegramSender.class);
    private final TopicRegistryService registry = mock(TopicRegistryService.class);
    private final MessageTemplates messages = messages();
    private final TopicsCommand command = new TopicsCommand(sender, messages, registry);

    private static MessageTemplates messages() {
        MessageTemplates messages = new MessageTemplates();
        messages.setTopicsEmpty("no-topics-yet");
        return messages;
    }

    private static TopicEntity topic(String slug, long threadId, boolean active, boolean closed) {
        TopicEntity topic = new TopicEntity();
        topic.setChatId(CHAT_ID);
        topic.setThreadId(threadId);
        topic.setSlug(slug);
        topic.setActive(active);
        topic.setClosed(closed);
        return topic;
    }

    private CommandContext context() {
        return new CommandContext(null, CHAT_ID, THREAD_ID, "/topics", List.of(), true);
    }

    private String listing() {
        ArgumentCaptor<String> html = ArgumentCaptor.forClass(String.class);
        verify(sender).reply(eq(CHAT_ID), eq(THREAD_ID), html.capture());
        return html.getValue();
    }

    @Test
    void a_closed_topic_is_marked_closed_even_while_it_stays_ours() {
        when(registry.allTopics()).thenReturn(List.of(topic("java", 42L, true, true)));

        command.execute(context());

        assertThat(listing()).contains("🔒 <code>java</code>").doesNotContain("🟢");
    }

    @Test
    void an_active_and_open_topic_is_marked_polled() {
        when(registry.allTopics()).thenReturn(List.of(topic("java", 42L, true, false)));

        command.execute(context());

        assertThat(listing()).contains("🟢 <code>java</code>");
    }

    @Test
    void a_topic_that_is_no_longer_ours_is_marked_neither() {
        when(registry.allTopics()).thenReturn(List.of(topic("java", 42L, false, false)));

        command.execute(context());

        assertThat(listing()).contains("⚪ <code>java</code>");
    }

    @Test
    void every_state_gets_its_own_line_in_one_listing() {
        when(registry.allTopics()).thenReturn(List.of(
            topic("java", 42L, true, true),
            topic("it", 43L, true, false),
            topic("chat", 44L, false, false)));

        command.execute(context());

        assertThat(listing().lines())
            .anyMatch(line -> line.startsWith("🔒 <code>java</code>"))
            .anyMatch(line -> line.startsWith("🟢 <code>it</code>"))
            .anyMatch(line -> line.startsWith("⚪ <code>chat</code>"));
    }

    @Test
    void the_listing_is_answered_in_the_topic_the_command_came_from() {
        when(registry.allTopics()).thenReturn(List.of(topic("java", 42L, true, false)));

        command.execute(new CommandContext(null, CHAT_ID, 99L, "/topics", List.of(), true));

        verify(sender).reply(eq(CHAT_ID), eq(99L), anyString());
    }

    @Test
    void an_empty_registry_answers_with_the_template() {
        when(registry.allTopics()).thenReturn(List.of());

        command.execute(context());

        verify(sender).reply(CHAT_ID, THREAD_ID, "no-topics-yet");
    }
}
