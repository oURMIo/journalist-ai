package com.dach.sagiri.telegram.command;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.dach.sagiri.config.MessageTemplates;
import com.dach.sagiri.schedule.NewsPollingJob;
import com.dach.sagiri.telegram.TelegramSender;
import com.dach.sagiri.topic.TopicEntity;
import com.dach.sagiri.topic.TopicRegistryService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** An on-demand poll obeys the same two flags the scheduler does. */
class NowCommandTest {

    private static final long CHAT_ID = -1001L;
    private static final long THREAD_ID = 42L;

    private final TelegramSender sender = mock(TelegramSender.class);
    private final TopicRegistryService registry = mock(TopicRegistryService.class);
    private final NewsPollingJob pollingJob = mock(NewsPollingJob.class);
    private final MessageTemplates messages = messages();
    private final NowCommand command = new NowCommand(sender, messages, registry, pollingJob);

    private final TopicEntity topic = topic();

    private static MessageTemplates messages() {
        MessageTemplates messages = new MessageTemplates();
        messages.setNotInTopic("not-in-topic");
        messages.setUnbindUnknown("unknown-topic");
        messages.setNowStarted("started {slug}");
        messages.setNowDone("done {slug} {sent}");
        return messages;
    }

    private static TopicEntity topic() {
        TopicEntity topic = new TopicEntity();
        topic.setId(7L);
        topic.setChatId(CHAT_ID);
        topic.setThreadId(THREAD_ID);
        topic.setSlug("java");
        topic.setActive(true);
        return topic;
    }

    private CommandContext inTopic() {
        when(registry.find(CHAT_ID, THREAD_ID)).thenReturn(Optional.of(topic));
        return new CommandContext(null, CHAT_ID, THREAD_ID, "/now", List.of(), true);
    }

    private List<String> replies() {
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(sender, atLeastOnce()).reply(eq(CHAT_ID), eq(THREAD_ID), text.capture());
        return text.getAllValues();
    }

    @Test
    void an_active_open_topic_is_polled_and_answered_in_its_own_thread() {
        when(pollingJob.pollTopic(topic)).thenReturn(3);

        command.execute(inTopic());

        verify(pollingJob).pollTopic(topic);
        assertThat(replies()).containsExactly("started java", "done java 3");
    }

    @Test
    void a_closed_topic_is_not_polled() {
        topic.setClosed(true);

        command.execute(inTopic());

        verify(pollingJob, never()).pollTopic(any());
        verify(sender).reply(CHAT_ID, THREAD_ID, "unknown-topic");
    }

    @Test
    void a_topic_that_is_no_longer_ours_is_not_polled() {
        topic.setActive(false);

        command.execute(inTopic());

        verify(pollingJob, never()).pollTopic(any());
        verify(sender).reply(CHAT_ID, THREAD_ID, "unknown-topic");
    }

    @Test
    void an_unregistered_topic_is_reported_instead_of_being_polled() {
        when(registry.find(CHAT_ID, THREAD_ID)).thenReturn(Optional.empty());

        command.execute(new CommandContext(null, CHAT_ID, THREAD_ID, "/now", List.of(), true));

        verify(pollingJob, never()).pollTopic(any());
        verify(sender).reply(CHAT_ID, THREAD_ID, "unknown-topic");
    }

    @Test
    void outside_a_topic_it_explains_where_to_send_the_command() {
        command.execute(new CommandContext(null, CHAT_ID, null, "/now", List.of(), true));

        verify(sender).reply(CHAT_ID, null, "not-in-topic");
        verifyNoInteractions(pollingJob, registry);
    }
}
