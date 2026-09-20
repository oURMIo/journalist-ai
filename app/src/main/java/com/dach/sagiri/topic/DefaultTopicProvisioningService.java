package com.dach.sagiri.topic;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.dach.sagiri.config.BotProperties;
import com.dach.sagiri.telegram.TelegramApi;
import com.pengrad.telegrambot.model.ForumTopic;
import com.pengrad.telegrambot.request.CloseForumTopic;
import com.pengrad.telegrambot.request.CreateForumTopic;
import com.pengrad.telegrambot.request.EditForumTopic;
import com.pengrad.telegrambot.response.BaseResponse;
import com.pengrad.telegrambot.response.CreateForumTopicResponse;

@Service
public class DefaultTopicProvisioningService implements TopicProvisioningService {

    private static final Logger logger =
        LoggerFactory.getLogger(DefaultTopicProvisioningService.class);

    /**
     * The only icon colours Bot API accepts. Anything else is rejected outright.
     */
    static final int[] PALETTE =
        {0x6FB9F0, 0xFFD67E, 0xCB86DB, 0x8EEE98, 0xFF93B2, 0xFB6F5F};

    /** Telegram throttles topic creation, so batches pace themselves. */
    private static final long BATCH_PAUSE_MILLIS = 1_100L;

    private final TelegramApi api;
    private final TopicRegistryService registry;
    private final TopicWelcomeService welcomeService;
    private final TopicSlugParser slugParser;
    private final BotProperties properties;

    public DefaultTopicProvisioningService(TelegramApi api, TopicRegistryService registry,
        TopicWelcomeService welcomeService, TopicSlugParser slugParser,
        BotProperties properties) {
        this.api = api;
        this.registry = registry;
        this.welcomeService = welcomeService;
        this.slugParser = slugParser;
        this.properties = properties;
    }

    @Override
    public ProvisionResult provision(long chatId, String rawSlug) {
        String slug = slugParser.normalizeRequestedSlug(rawSlug);
        if (!slugParser.isProvisionable(slug)) {
            return ProvisionResult.invalidSlug(rawSlug,
                "Invalid topic '" + rawSlug + "': letters, digits and hyphen are allowed, "
                + "length 2–100 characters.");
        }

        Optional<TopicEntity> existing = registry.findActiveBySlug(chatId, slug);
        if (existing.isPresent()) {
            // Telegram happily creates topics with duplicate names, so this check is ours.
            return ProvisionResult.alreadyExists(slug, existing.get().getThreadId(),
                "Topic '" + slug + "' already exists.");
        }

        String name = properties.topicName(slug);
        try {
            CreateForumTopicResponse response = api.execute(chatId,
                new CreateForumTopic(chatId, name).iconColor(iconColorFor(slug)));

            if (!response.isOk() || response.forumTopic() == null) {
                logger.warn("createForumTopic failed for '{}': {} {}", name,
                    response.errorCode(), response.description());
                return ProvisionResult.failed(slug,
                    "Could not create '" + name + "': " + response.description());
            }

            ForumTopic forumTopic = response.forumTopic();
            long threadId = forumTopic.messageThreadId();
            // The response already carries the thread id: register now, do not wait
            // for the forum_topic_created update.
            Optional<TopicEntity> stored = registry.register(chatId, threadId, name, true);
            if (stored.isEmpty()) {
                return ProvisionResult.failed(slug,
                    "Topic created, but it did not reach the registry: " + name);
            }
            welcomeService.ensureWelcome(stored.get());
            logger.info("Provisioned topic '{}' as thread {}", name, threadId);
            return ProvisionResult.created(slug, threadId, "Created topic '" + slug + "'.");
        } catch (RuntimeException e) {
            logger.error("Failed to provision topic '{}'", name, e);
            return ProvisionResult.failed(slug,
                "Could not create '" + name + "': " + e.getMessage());
        }
    }

    @Override
    public List<ProvisionResult> provisionAll(long chatId, List<String> slugs) {
        List<ProvisionResult> results = new ArrayList<>(slugs.size());
        for (int i = 0; i < slugs.size(); i++) {
            if (i > 0) {
                pause();
            }
            results.add(provision(chatId, slugs.get(i)));
        }
        return results;
    }

    @Override
    public ProvisionResult rename(long chatId, long threadId, String rawSlug) {
        String slug = slugParser.normalizeRequestedSlug(rawSlug);
        if (!slugParser.isProvisionable(slug)) {
            return ProvisionResult.invalidSlug(rawSlug,
                "Invalid topic '" + rawSlug + "'.");
        }
        Optional<TopicEntity> conflict = registry.findActiveBySlug(chatId, slug)
            .filter(topic -> topic.getThreadId() != threadId);
        if (conflict.isPresent()) {
            return ProvisionResult.alreadyExists(slug, conflict.get().getThreadId(),
                "Topic '" + slug + "' is already taken by another topic.");
        }

        String name = properties.topicName(slug);
        BaseResponse response = api.execute(chatId,
            new EditForumTopic(chatId, threadId).name(name));
        if (!response.isOk()) {
            return ProvisionResult.failed(slug,
                "Could not rename: " + response.description());
        }

        Optional<TopicEntity> stored = registry.rename(chatId, threadId, name);
        if (stored.isEmpty()) {
            return ProvisionResult.failed(slug, "Topic renamed, but it did not reach the registry.");
        }
        welcomeService.refreshWelcome(stored.get());
        return ProvisionResult.created(slug, threadId, "Topic changed to '" + slug + "'.");
    }

    @Override
    public void close(long chatId, long threadId) {
        BaseResponse response = api.execute(chatId, new CloseForumTopic(chatId, threadId));
        if (!response.isOk()) {
            logger.warn("closeForumTopic failed for thread {}: {} {}", threadId,
                response.errorCode(), response.description());
        }
        registry.setClosed(chatId, threadId, true);
    }

    /**
     * Deterministic colour per subject: the same slug always looks the same and
     * different subjects are easy to tell apart. {@code floorMod} keeps the index
     * in range even for {@code Integer.MIN_VALUE} hash codes.
     */
    static int iconColorFor(String slug) {
        return PALETTE[Math.floorMod(slug.hashCode(), PALETTE.length)];
    }

    private static void pause() {
        try {
            Thread.sleep(BATCH_PAUSE_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
