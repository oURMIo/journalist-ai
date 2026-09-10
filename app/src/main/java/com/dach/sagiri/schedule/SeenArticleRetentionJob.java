package com.dach.sagiri.schedule;

import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.dach.sagiri.config.BotProperties;
import com.dach.sagiri.dedup.SeenArticleService;

/** Daily clean-up of the de-duplication table. */
@Component
public class SeenArticleRetentionJob {

    private static final Logger logger = LoggerFactory.getLogger(SeenArticleRetentionJob.class);

    private final SeenArticleService seenArticles;
    private final BotProperties properties;

    public SeenArticleRetentionJob(SeenArticleService seenArticles, BotProperties properties) {
        this.seenArticles = seenArticles;
        this.properties = properties;
    }

    @Scheduled(initialDelay = 10, fixedDelay = 24 * 60, timeUnit = java.util.concurrent.TimeUnit.MINUTES)
    public void purge() {
        Instant threshold = Instant.now()
            .minus(Duration.ofDays(properties.getSeenRetentionDays()));
        try {
            int removed = seenArticles.purgeOlderThan(threshold);
            if (removed > 0) {
                logger.info("Purged {} seen article(s) older than {}", removed, threshold);
            }
        } catch (RuntimeException e) {
            logger.warn("Retention job failed", e);
        }
    }
}
