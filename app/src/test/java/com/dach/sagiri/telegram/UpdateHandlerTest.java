package com.dach.sagiri.telegram;

import com.google.gson.Gson;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import com.dach.sagiri.config.BotProperties;
import com.dach.sagiri.topic.TopicEntity;
import com.dach.sagiri.topic.TopicRegistryService;
import com.dach.sagiri.topic.TopicWelcomeService;
import com.pengrad.telegrambot.UpdatesListener;
import com.pengrad.telegrambot.model.Update;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Telegram's open/closed state arrives only as a forum service message, and it
 * has to land on its own column: {@code active} answers "is this topic ours",
 * which a rename decides, and a rename must not resume news into a closed topic.
 */
class UpdateHandlerTest {

    private static final long CHAT_ID = -1001234567890L;
    private static final long THREAD_ID = 42L;
    private static final Gson GSON = new Gson();

    private final CommandRouter commandRouter = mock(CommandRouter.class);
    private final TopicRegistryService registry = mock(TopicRegistryService.class);
    private final TopicWelcomeService welcomeService = mock(TopicWelcomeService.class);
    private final UpdateHandler handler =
        new UpdateHandler(properties(), commandRouter, registry, welcomeService);

    private static BotProperties properties() {
        BotProperties properties = new BotProperties();
        properties.setAllowedChatIds(List.of(CHAT_ID));
        properties.setTopicPrefix("news-");
        return properties;
    }

    private static Update serviceMessage(long chatId, String threadIdField, String service) {
        return GSON.fromJson("""
            {"update_id":1,"message":{"message_id":9,"date":0,%s
             "chat":{"id":%d,"type":"supergroup","is_forum":true},%s}}"""
            .formatted(threadIdField, chatId, service), Update.class);
    }

    private static Update inThread(long chatId, String service) {
        return serviceMessage(chatId, "\"message_thread_id\":" + THREAD_ID + ",", service);
    }

    @Test
    void forum_topic_closed_marks_the_topic_closed_and_leaves_active_alone() {
        handler.handle(List.of(inThread(CHAT_ID, "\"forum_topic_closed\":{}")));

        verify(registry).setClosed(CHAT_ID, THREAD_ID, true);
        verify(registry, never()).setActive(anyLong(), anyLong(), anyBoolean());
        verify(registry, never()).register(anyLong(), anyLong(), anyString(), anyBoolean());
        verifyNoInteractions(commandRouter, welcomeService);
    }

    @Test
    void forum_topic_reopened_clears_the_closed_flag_and_leaves_active_alone() {
        handler.handle(List.of(inThread(CHAT_ID, "\"forum_topic_reopened\":{}")));

        verify(registry).setClosed(CHAT_ID, THREAD_ID, false);
        verify(registry, never()).setActive(anyLong(), anyLong(), anyBoolean());
        verify(registry, never()).register(anyLong(), anyLong(), anyString(), anyBoolean());
        verifyNoInteractions(commandRouter, welcomeService);
    }

    @Test
    void a_rename_goes_through_register_and_never_touches_the_closed_flag() {
        TopicEntity topic = new TopicEntity();
        when(registry.register(CHAT_ID, THREAD_ID, "news-genomics", false))
            .thenReturn(Optional.of(topic));

        handler.handle(List.of(inThread(CHAT_ID,
            "\"forum_topic_edited\":{\"name\":\"news-genomics\"}")));

        verify(registry).register(CHAT_ID, THREAD_ID, "news-genomics", false);
        verify(registry, never()).setClosed(anyLong(), anyLong(), anyBoolean());
        verify(welcomeService).refreshWelcome(topic);
    }

    @Test
    void a_closed_update_from_a_foreign_chat_is_ignored() {
        handler.handle(List.of(inThread(-999L, "\"forum_topic_closed\":{}")));

        verifyNoInteractions(registry, commandRouter, welcomeService);
    }

    @Test
    void a_closed_update_without_a_thread_id_changes_nothing() {
        handler.handle(List.of(serviceMessage(CHAT_ID, "", "\"forum_topic_closed\":{}")));

        verify(registry, never()).setClosed(anyLong(), anyLong(), anyBoolean());
    }

    @Test
    void a_forum_service_message_never_reaches_the_command_router() {
        int result = handler.handle(List.of(
            inThread(CHAT_ID, "\"forum_topic_closed\":{}"),
            inThread(CHAT_ID, "\"forum_topic_reopened\":{}")));

        assertThat(result).isEqualTo(UpdatesListener.CONFIRMED_UPDATES_ALL);
        verify(commandRouter, never()).dispatch(any());
    }
}
