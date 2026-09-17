package com.dach.sagiri.topic;

import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.dach.sagiri.config.MessageTemplates;
import com.dach.sagiri.news.model.TopicQuery;
import com.dach.sagiri.telegram.HtmlText;
import com.dach.sagiri.telegram.TelegramSender;

/**
 * Keeps the subject of a topic — what it is about and what to search for — and
 * derives it from the slug the first time the topic is polled.
 * <p>
 * Deriving happens here rather than when the topic is registered: a model call
 * takes seconds to minutes, and an incoming Telegram update must not wait for it.
 * A topic that existed before this feature therefore also gets a subject, on its
 * next cycle, with nobody having to do anything.
 */
@Service
public class TopicProfileService {

    private static final Logger logger = LoggerFactory.getLogger(TopicProfileService.class);

    private final TopicRepository repository;
    private final TopicQueryResolver queryResolver;
    private final TopicProfiler profiler;
    private final TelegramSender sender;
    private final MessageTemplates messages;

    public TopicProfileService(TopicRepository repository, TopicQueryResolver queryResolver,
        TopicProfiler profiler, TelegramSender sender, MessageTemplates messages) {
        this.repository = repository;
        this.queryResolver = queryResolver;
        this.profiler = profiler;
        this.sender = sender;
        this.messages = messages;
    }

    /** The sources of a topic, with its subject derived first if that never happened. */
    public TopicQuery queryFor(TopicEntity topic) {
        derive(topic);
        return queryResolver.resolve(topic);
    }

    /**
     * A query written by hand: nothing derived later may overwrite it.
     * <p>
     * The derived subject goes with it. A reader types {@code /query} precisely
     * when the model read the topic wrong, and a wrong subject left in place would
     * go on blocking exactly the articles the new query was written to find —
     * permanently, since a model rejection is remembered. Without a subject the
     * topic is judged by its keyword rules alone, the way it was before any of
     * this; {@code /query auto} asks the model again.
     */
    public void setQuery(TopicEntity topic, String query) {
        repository.setProfile(topic.getId(), null, query, TopicProfileSource.USER, Instant.now());
        topic.setDomain(null);
        topic.setQuery(query);
        topic.setProfileSource(TopicProfileSource.USER);
        logger.info("Topic '{}' searches for '{}' by hand", topic.getSlug(), query);
    }

    /** Drops both halves of the profile, so the next cycle derives them again. */
    public void resetQuery(TopicEntity topic) {
        String fallback = queryResolver.resolve(topic.getSlug()).query();
        repository.setProfile(topic.getId(), null, fallback, TopicProfileSource.SLUG,
            Instant.now());
        topic.setDomain(null);
        topic.setQuery(fallback);
        topic.setProfileSource(TopicProfileSource.SLUG);
    }

    /**
     * Only a topic still running on its bare slug is read: once a subject is
     * stored, deriving it again would either change nothing or overrule a reader
     * who already overruled the model.
     */
    private void derive(TopicEntity topic) {
        if (topic.getId() == null || !profiler.available()
            || topic.getProfileSource() != TopicProfileSource.SLUG) {
            return;
        }
        profiler.derive(topic.getSlug()).ifPresent(profile -> store(topic, profile));
    }

    /**
     * The write is conditional, so the scheduler and a {@code /now} deriving the
     * same topic at once cannot store two profiles — or announce twice.
     */
    private void store(TopicEntity topic, TopicProfile profile) {
        if (repository.applyDerivedProfile(topic.getId(), profile.domain(), profile.query(),
            Instant.now()) != 1) {
            return;
        }
        topic.setDomain(profile.domain());
        topic.setQuery(profile.query());
        topic.setProfileSource(TopicProfileSource.MODEL);
        announce(topic, profile.domain());
    }

    /**
     * The reader gets to see what the bot decided their topic is about, in the
     * topic itself — a wrong guess is then one {@code /query} away from fixed
     * instead of silently shaping the feed forever.
     * <p>
     * The query announced is the one that will actually be searched, which for a
     * topic with a preset is the preset's and not the derived one.
     */
    private void announce(TopicEntity topic, String domain) {
        sender.reply(topic.getChatId(), topic.getThreadId(),
            MessageTemplates.render(messages.getTopicProfileDerived(), Map.of(
                "slug", HtmlText.escape(topic.getSlug()),
                "domain", HtmlText.escape(domain),
                "query", HtmlText.escape(queryResolver.resolve(topic).query()))));
    }
}
