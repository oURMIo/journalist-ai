package com.dach.sagiri.topic;

import java.time.Instant;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.dach.sagiri.config.BotProperties;
import com.dach.sagiri.news.model.TopicQuery;
import com.dach.sagiri.telegram.HtmlText;
import com.dach.sagiri.telegram.OutgoingMessage;
import com.dach.sagiri.telegram.TelegramApi;
import com.dach.sagiri.telegram.TelegramSender;
import com.pengrad.telegrambot.model.request.ParseMode;
import com.pengrad.telegrambot.request.EditMessageText;
import com.pengrad.telegrambot.request.PinChatMessage;
import com.pengrad.telegrambot.response.BaseResponse;
import com.pengrad.telegrambot.response.SendResponse;

/**
 * Sends and maintains the pinned welcome message of a news topic.
 * <p>
 * The welcome is sent at most once per topic, whichever way the topic came into
 * existence: {@code /newtopic}, {@code BOOTSTRAP_TOPICS} or a user creating it by
 * hand. Ownership is taken with a conditional UPDATE, so the duplicate
 * {@code forum_topic_created} update cannot produce a second greeting.
 */
@Service
public class TopicWelcomeService {

    private static final Logger logger = LoggerFactory.getLogger(TopicWelcomeService.class);

    private static final String MESSAGE_GONE = "message to edit not found";

    private final TopicRepository repository;
    private final TopicQueryResolver queryResolver;
    private final WelcomeMessageFormatter formatter;
    private final TelegramSender sender;
    private final TelegramApi api;
    private final BotProperties properties;

    public TopicWelcomeService(TopicRepository repository, TopicQueryResolver queryResolver,
        WelcomeMessageFormatter formatter, TelegramSender sender, TelegramApi api,
        BotProperties properties) {
        this.repository = repository;
        this.queryResolver = queryResolver;
        this.formatter = formatter;
        this.sender = sender;
        this.api = api;
        this.properties = properties;
    }

    /** Sends the welcome message if this topic has not been greeted yet. */
    public void ensureWelcome(TopicEntity topic) {
        if (!topic.isActive() || topic.getId() == null) {
            return;
        }
        if (repository.claimWelcome(topic.getId(), TopicEntity.WELCOME_IN_PROGRESS,
            Instant.now()) != 1) {
            logger.debug("Topic {} already has a welcome message, skipping", topic.getThreadId());
            return;
        }

        String html = formatter.format(queryResolver.resolve(topic.getSlug()));
        try {
            SendResponse response = sender.sendBlocking(
                new OutgoingMessage(topic.getChatId(), topic.getThreadId(), html, false, true));
            if (!response.isOk() || response.message() == null) {
                throw new IllegalStateException(
                    "Telegram rejected the welcome message: " + response.description());
            }
            long messageId = response.message().messageId();
            repository.setWelcomeMessageId(topic.getId(), messageId, Instant.now());
            topic.setWelcomeMessageId(messageId);
            logger.info("Welcome message {} sent to topic {}", messageId, topic.getThreadId());
            pin(topic.getChatId(), (int) messageId);
        } catch (RuntimeException e) {
            // Release the claim so a later update or /newtopic can retry.
            repository.setWelcomeMessageId(topic.getId(), null, Instant.now());
            logger.error("Failed to send the welcome message to topic {}", topic.getThreadId(), e);
        }
    }

    /**
     * Rewrites the welcome after a rename. If the message is gone, a fresh one is
     * sent and the stored id replaced.
     */
    public void refreshWelcome(TopicEntity topic) {
        TopicQuery query = queryResolver.resolve(topic.getSlug());
        String html = formatter.format(query);

        if (!topic.hasWelcome()) {
            ensureWelcome(topic);
            return;
        }

        int messageId = topic.getWelcomeMessageId().intValue();
        BaseResponse response = api.execute(topic.getChatId(),
            new EditMessageText(topic.getChatId(), messageId, HtmlText.fitMessage(html))
                .parseMode(ParseMode.HTML));

        if (response.isOk()) {
            return;
        }
        if (StringUtils.containsIgnoreCase(response.description(), MESSAGE_GONE)) {
            logger.info("Welcome message {} of topic {} is gone, sending a new one",
                messageId, topic.getThreadId());
            repository.setWelcomeMessageId(topic.getId(), null, Instant.now());
            topic.setWelcomeMessageId(null);
            ensureWelcome(topic);
            return;
        }
        logger.warn("Could not edit the welcome message of topic {}: {} {}",
            topic.getThreadId(), response.errorCode(), response.description());
    }

    /**
     * Pinning is a nice-to-have. Without {@code can_pin_messages} the topic is
     * still perfectly usable, so a failure here is a warning, not an error.
     */
    private void pin(long chatId, int messageId) {
        if (!properties.isWelcomePin()) {
            return;
        }
        try {
            BaseResponse response = api.execute(chatId,
                new PinChatMessage(chatId, messageId).disableNotification(true));
            if (!response.isOk()) {
                logger.warn("Could not pin the welcome message ({}): {}. "
                            + "Grant the bot can_pin_messages to enable pinning.",
                    response.errorCode(), response.description());
            }
        } catch (RuntimeException e) {
            logger.warn("Could not pin the welcome message: {}", e.toString());
        }
    }
}
