package com.dach.sagiri.schedule;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.dach.sagiri.config.BotProperties;
import com.dach.sagiri.dedup.SeenArticleService;
import com.dach.sagiri.filter.ArticleFilter;
import com.dach.sagiri.filter.FilterDecision;
import com.dach.sagiri.filter.TopicFilters;
import com.dach.sagiri.news.NewsAggregator;
import com.dach.sagiri.news.model.NewsItem;
import com.dach.sagiri.news.model.TopicQuery;
import com.dach.sagiri.summary.Summarizer;
import com.dach.sagiri.telegram.MessageFormatter;
import com.dach.sagiri.telegram.OutgoingMessage;
import com.dach.sagiri.telegram.TelegramSender;
import com.dach.sagiri.topic.TopicEntity;
import com.dach.sagiri.topic.TopicProfileService;
import com.dach.sagiri.topic.TopicRegistryService;
import com.pengrad.telegrambot.response.SendResponse;

/**
 * Walks every active topic on a fixed schedule and publishes what is new.
 * <p>
 * A topic created between two runs is picked up by the next one, with no
 * restart and no code change — that is the whole point of the registry.
 */
@Component
public class NewsPollingJob {

    private static final Logger logger = LoggerFactory.getLogger(NewsPollingJob.class);

    /** Fetch more than needed so de-duplication and the filters still leave a full batch. */
    private static final int OVERFETCH_FACTOR = 4;

    /** Telegram is down or the topic is gone: stop the cycle instead of retrying 20 times. */
    private static final int MAX_SEND_FAILURES = 3;

    private final TopicRegistryService registry;
    private final TopicProfileService profiles;
    private final NewsAggregator aggregator;
    private final SeenArticleService seenArticles;
    private final ArticleFilter articleFilter;
    private final Summarizer summarizer;
    private final MessageFormatter formatter;
    private final TelegramSender sender;
    private final PollingState state;
    private final BotProperties properties;

    public NewsPollingJob(TopicRegistryService registry, TopicProfileService profiles,
        NewsAggregator aggregator, SeenArticleService seenArticles, ArticleFilter articleFilter,
        Summarizer summarizer, MessageFormatter formatter, TelegramSender sender,
        PollingState state, BotProperties properties) {
        this.registry = registry;
        this.profiles = profiles;
        this.aggregator = aggregator;
        this.seenArticles = seenArticles;
        this.articleFilter = articleFilter;
        this.summarizer = summarizer;
        this.formatter = formatter;
        this.sender = sender;
        this.state = state;
        this.properties = properties;
    }

    @Scheduled(initialDelayString = "${bot.poll-initial-delay-minutes:1}",
        fixedDelayString = "${bot.poll-interval-minutes:30}", timeUnit = TimeUnit.MINUTES)
    public void pollAll() {
        List<TopicEntity> topics = registry.activeTopics();
        if (topics.isEmpty()) {
            logger.debug("No active topics to poll");
            state.recordSuccess(0);
            return;
        }
        logger.info("Polling {} active topic(s)", topics.size());
        int sent = 0;
        for (TopicEntity topic : topics) {
            try {
                sent += pollTopic(topic);
            } catch (RuntimeException e) {
                // One bad topic must not stop the others.
                logger.warn("Polling topic {} ('{}') failed", topic.getThreadId(),
                    topic.getSlug(), e);
            }
        }
        state.recordSuccess(sent);
        logger.info("Polling cycle finished, {} message(s) sent", sent);
    }

    /**
     * Polls one topic and publishes what it has not seen yet.
     *
     * @return how many news messages were actually delivered
     */
    public int pollTopic(TopicEntity topic) {
        TopicQuery query = profiles.queryFor(topic);
        Instant since = lookbackStart(topic);
        int maxItems = properties.getMaxItemsPerTopic();

        List<NewsItem> candidates =
            aggregator.collect(query, since, maxItems * OVERFETCH_FACTOR);
        List<NewsItem> unseen = seenArticles.filterUnseen(topic.getId(), candidates);
        TopicFilters rules = articleFilter.rulesOf(topic);

        logger.debug("Topic '{}': {} candidate(s), {} new", topic.getSlug(), candidates.size(),
            unseen.size());

        int sent = 0;
        int filtered = 0;
        int failures = 0;
        Instant unfinished = null;
        for (NewsItem item : unseen) {
            if (sent >= maxItems || failures >= MAX_SEND_FAILURES) {
                break;
            }
            FilterDecision decision = articleFilter.decide(rules, item);
            if (!decision.allowed()) {
                if (decision.remember()) {
                    seenArticles.markFiltered(topic.getId(), item, decision.reason());
                } else if (decision.postponed()) {
                    unfinished = earliest(unfinished, publishedAt(item, since));
                }
                filtered++;
                continue;
            }
            if (publish(topic, item, sent == 0)) {
                // Recorded only after Telegram accepted the message, so a failed
                // send is retried on the next cycle instead of being lost.
                seenArticles.markSent(topic.getId(), item);
                sent++;
            } else {
                failures++;
                unfinished = earliest(unfinished, publishedAt(item, since));
            }
        }
        if (filtered > 0) {
            logger.info("Topic '{}': {} article(s) dropped by the topic filters", topic.getSlug(),
                filtered);
        }
        registry.markPolled(topic.getId(), watermark(unfinished));
        return sent;
    }

    /**
     * The next cycle only sees articles newer than {@code last_polled_at}, so a
     * cycle that left work behind - a send that failed, a model that could not
     * answer - must not move the mark past it. Otherwise "retried on the next
     * cycle" means "never fetched again".
     */
    private static Instant watermark(Instant unfinished) {
        return unfinished == null ? Instant.now() : unfinished.minusMillis(1);
    }

    private static Instant publishedAt(NewsItem item, Instant fallback) {
        return item.publishedAt() == null ? fallback : item.publishedAt();
    }

    private static Instant earliest(Instant left, Instant right) {
        return left == null || right.isBefore(left) ? right : left;
    }

    private boolean publish(TopicEntity topic, NewsItem item, boolean first) {
        try {
            String html = formatter.format(item, summarizer.summarize(item));
            SendResponse response = sender.sendBlocking(new OutgoingMessage(
                topic.getChatId(), topic.getThreadId(), html, showPreview(first), false));
            return response.isOk();
        } catch (RuntimeException e) {
            logger.warn("Could not publish '{}' to topic {}", item.title(), topic.getThreadId(), e);
            return false;
        }
    }

    /** A preview under every item turns the topic into a wall of cards. */
    private boolean showPreview(boolean first) {
        return switch (properties.getLinkPreview()) {
            case ALWAYS -> true;
            case NEVER -> false;
            case FIRST_ONLY -> first;
        };
    }

    /**
     * Never look further back than the configured window, so a brand new topic
     * does not get flooded with a day of history at once.
     */
    private Instant lookbackStart(TopicEntity topic) {
        Instant window = Instant.now().minus(Duration.ofHours(properties.getNewsLookbackHours()));
        Instant lastPolled = topic.getLastPolledAt();
        return lastPolled == null || lastPolled.isBefore(window) ? window : lastPolled;
    }
}
