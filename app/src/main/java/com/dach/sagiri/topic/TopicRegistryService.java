package com.dach.sagiri.topic;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The topic registry — the bot's own answer to the missing {@code getForumTopics}.
 * <p>
 * Writes are upserts keyed by {@code (chat_id, thread_id)}: Telegram delivers a
 * {@code forum_topic_created} update even for topics the bot created itself, and
 * without the upsert that would produce a duplicate row and a second welcome.
 */
@Service
public class TopicRegistryService {

    private static final Logger logger = LoggerFactory.getLogger(TopicRegistryService.class);

    private final TopicRepository repository;
    private final TopicSlugParser slugParser;
    private final TopicQueryResolver queryResolver;

    public TopicRegistryService(TopicRepository repository, TopicSlugParser slugParser,
        TopicQueryResolver queryResolver) {
        this.repository = repository;
        this.slugParser = slugParser;
        this.queryResolver = queryResolver;
    }

    /**
     * Registers or refreshes a topic from its name.
     *
     * @return the stored topic, or empty when the name does not carry the news
     *     prefix — such a topic is not ours and gets deactivated if it was
     */
    @Transactional
    public Optional<TopicEntity> register(long chatId, long threadId, String rawName,
        boolean createdByBot) {

        Optional<String> parsed = slugParser.parse(rawName);
        Optional<TopicEntity> existing = repository.findByChatIdAndThreadId(chatId, threadId);

        if (parsed.isEmpty()) {
            // Renamed away from "news-*", or never was one: stop polling it.
            existing.filter(TopicEntity::isActive).ifPresent(topic -> {
                logger.info("Topic {} no longer matches the news prefix, deactivating", threadId);
                topic.setActive(false);
                topic.setRawName(rawName);
                repository.save(topic);
            });
            return Optional.empty();
        }

        String slug = parsed.get();
        TopicEntity topic = existing.orElseGet(TopicEntity::new);
        boolean isNew = topic.getId() == null;
        boolean subjectChanged = !slug.equals(topic.getSlug());

        if (isNew) {
            topic.setChatId(chatId);
            topic.setThreadId(threadId);
            topic.setCreatedByBot(createdByBot);
        }
        topic.setRawName(rawName);
        topic.setSlug(slug);
        if (subjectChanged) {
            // A rename to another subject invalidates the derived one; a rename that
            // only changes the emoji must not throw away a query written by hand.
            topic.setQuery(queryResolver.resolve(slug).query());
            topic.setDomain(null);
            topic.setProfileSource(TopicProfileSource.SLUG);
        }

        if (conflictsWithOtherActiveTopic(chatId, slug, threadId)) {
            // The partial unique index allows only one active topic per subject.
            logger.warn("Chat {} already has an active topic for '{}', keeping thread {} inactive",
                chatId, slug, threadId);
            topic.setActive(false);
            return Optional.of(repository.save(topic));
        }
        topic.setActive(true);

        TopicEntity saved = repository.save(topic);
        logger.info("{} topic {} -> slug '{}' query '{}'", isNew ? "Registered" : "Updated",
            threadId, slug, saved.getQuery());
        return Optional.of(saved);
    }

    private boolean conflictsWithOtherActiveTopic(long chatId, String slug, long threadId) {
        return repository.findByChatIdAndSlugAndActiveTrue(chatId, slug)
            .filter(other -> other.getThreadId() != threadId)
            .isPresent();
    }

    /** Manual binding for topics that existed before the bot joined the group. */
    @Transactional
    public Optional<TopicEntity> bind(long chatId, long threadId, String rawName) {
        return register(chatId, threadId, rawName, false);
    }

    @Transactional
    public boolean setActive(long chatId, long threadId, boolean active) {
        return repository.findByChatIdAndThreadId(chatId, threadId)
            .map(topic -> {
                topic.setActive(active);
                repository.save(topic);
                return true;
            })
            .orElse(false);
    }

    @Transactional(readOnly = true)
    public List<TopicEntity> activeTopics() {
        return repository.findByActiveTrueOrderByChatIdAscThreadIdAsc();
    }

    @Transactional(readOnly = true)
    public List<TopicEntity> allTopics() {
        return repository.findAllByOrderByChatIdAscThreadIdAsc();
    }

    @Transactional(readOnly = true)
    public Optional<TopicEntity> find(long chatId, long threadId) {
        return repository.findByChatIdAndThreadId(chatId, threadId);
    }

    @Transactional(readOnly = true)
    public Optional<TopicEntity> findActiveBySlug(long chatId, String slug) {
        return repository.findByChatIdAndSlugAndActiveTrue(chatId, slug);
    }

    @Transactional
    public void markPolled(Long topicId, Instant polledAt) {
        repository.markPolled(topicId, polledAt);
    }

    /** Applies a rename that the bot itself performed through {@code editForumTopic}. */
    @Transactional
    public Optional<TopicEntity> rename(long chatId, long threadId, String newRawName) {
        return register(chatId, threadId, newRawName, false);
    }
}
