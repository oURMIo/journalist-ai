package com.dach.sagiri.telegram;

import java.util.List;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.dach.sagiri.config.BotProperties;
import com.dach.sagiri.topic.TopicEntity;
import com.dach.sagiri.topic.TopicRegistryService;
import com.dach.sagiri.topic.TopicWelcomeService;
import com.pengrad.telegrambot.UpdatesListener;
import com.pengrad.telegrambot.model.Message;
import com.pengrad.telegrambot.model.Update;

/**
 * Entry point for every Telegram update. Two jobs: keep the topic registry in
 * sync with forum service messages, and hand commands to {@link CommandRouter}.
 * <p>
 * Replaces the skeleton's echo handler; the update loop and its error handling
 * are carried over.
 */
@Component
public class UpdateHandler {

    private static final Logger logger = LoggerFactory.getLogger(UpdateHandler.class);

    private final BotProperties properties;
    private final CommandRouter commandRouter;
    private final TopicRegistryService registry;
    private final TopicWelcomeService welcomeService;

    public UpdateHandler(BotProperties properties, CommandRouter commandRouter,
        TopicRegistryService registry, TopicWelcomeService welcomeService) {
        this.properties = properties;
        this.commandRouter = commandRouter;
        this.registry = registry;
        this.welcomeService = welcomeService;
    }

    public int handle(List<Update> updates) {
        for (Update update : updates) {
            try {
                handleUpdate(update);
            } catch (RuntimeException e) {
                logger.error("Failed to process update {}", update.updateId(), e);
            }
        }
        return UpdatesListener.CONFIRMED_UPDATES_ALL;
    }

    private void handleUpdate(Update update) {
        Message message = update.message();
        if (message == null || message.chat() == null) {
            return;
        }
        long chatId = message.chat().id();
        if (!properties.isAllowedChat(chatId)) {
            // ALLOWED_CHAT_IDS is the guard against the bot being added to a foreign group.
            logger.debug("Ignoring update {} from chat {} which is not allowed",
                update.updateId(), chatId);
            return;
        }

        if (handleForumServiceMessage(chatId, message)) {
            return;
        }
        if (StringUtils.isNotBlank(message.text())) {
            commandRouter.dispatch(message);
        }
    }

    /**
     * Forum service messages are the only way the bot learns topic names: Bot API
     * has no method to list the topics of a chat.
     *
     * @return {@code true} when the message was a forum service message
     */
    private boolean handleForumServiceMessage(long chatId, Message message) {
        Long threadId = message.messageThreadId();

        if (message.forumTopicCreated() != null) {
            if (threadId == null) {
                return true;
            }
            String name = message.forumTopicCreated().name();
            logger.info("forum_topic_created chat={} thread={} name='{}'", chatId, threadId, name);
            // The bot gets this update for topics it created itself too, hence the
            // upsert + welcome-once guard downstream.
            Optional<TopicEntity> topic = registry.register(chatId, threadId, name, false);
            topic.ifPresent(welcomeService::ensureWelcome);
            return true;
        }

        if (message.forumTopicEdited() != null) {
            if (threadId == null) {
                return true;
            }
            String name = message.forumTopicEdited().name();
            if (StringUtils.isBlank(name)) {
                // Only the icon changed — the payload carries no name, do not wipe ours.
                logger.debug("forum_topic_edited without a name for thread {}, ignoring", threadId);
                return true;
            }
            logger.info("forum_topic_edited chat={} thread={} name='{}'", chatId, threadId, name);
            Optional<TopicEntity> topic = registry.register(chatId, threadId, name, false);
            // The subject may have changed, so the welcome text has to follow.
            topic.ifPresent(welcomeService::refreshWelcome);
            return true;
        }

        if (message.forumTopicClosed() != null) {
            if (threadId != null) {
                logger.info("forum_topic_closed chat={} thread={}", chatId, threadId);
                registry.setClosed(chatId, threadId, true);
            }
            return true;
        }

        if (message.forumTopicReopened() != null) {
            if (threadId != null) {
                logger.info("forum_topic_reopened chat={} thread={}", chatId, threadId);
                registry.setClosed(chatId, threadId, false);
            }
            return true;
        }

        return false;
    }
}
