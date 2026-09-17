package com.dach.sagiri.summary;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.dach.sagiri.dedup.SeenArticleService;
import com.dach.sagiri.news.model.NewsItem;

/**
 * Shared plumbing for every model-backed summariser: the per-article cache, the
 * hourly call budget and — most importantly — the fallback.
 * <p>
 * Only the feed title and description are ever sent to a model; the full article
 * text is deliberately not fetched. Any failure, throttle or empty answer falls
 * back to {@link ExtractiveSummarizer}, so the bot never goes silent because a
 * model is slow, missing or down.
 */
public abstract class LlmSummarizer implements Summarizer, LlmGateway {

    private static final Logger logger = LoggerFactory.getLogger(LlmSummarizer.class);

    static final String PROMPT = """
        Summarize this news item in 1-3 factual sentences in English. \
        No opinions, no speculation, no marketing language, no preamble. \
        Reply with the summary only. \
        If the input is insufficient, return the description as is.""";

    private final ExtractiveSummarizer fallback;
    private final SummaryCacheRepository cache;
    private final SeenArticleService seenArticles;
    private final int maxCallsPerHour;

    /** Sliding window of call timestamps enforcing the hourly budget. */
    private final Deque<Instant> callTimestamps = new ArrayDeque<>();

    protected LlmSummarizer(ExtractiveSummarizer fallback, SummaryCacheRepository cache,
        SeenArticleService seenArticles, int maxCallsPerHour) {
        this.fallback = fallback;
        this.cache = cache;
        this.seenArticles = seenArticles;
        this.maxCallsPerHour = maxCallsPerHour;
    }

    /** Name of the backend, for logs. */
    protected abstract String backend();

    /**
     * Asks the model for a summary.
     *
     * @param label what the prompt is about, for logs only
     * @return the summary, or an empty string on any failure — never throws
     */
    protected abstract String generate(String prompt, String label);

    @Override
    public String summarize(NewsItem item) {
        String fingerprint = seenArticles.fingerprintOf(item);

        Optional<String> cached = cache.findById(fingerprint)
            .map(SummaryCacheEntity::getSummary)
            .filter(StringUtils::isNotBlank);
        if (cached.isPresent()) {
            return cached.get();
        }

        if (!allowCall(maxCallsPerHour)) {
            logger.warn("{} budget of {} call(s) per hour is exhausted, using the extractive "
                        + "summariser", backend(), maxCallsPerHour);
            return fallback.summarize(item);
        }

        String summary = clean(generate(promptFor(item), item.title()));
        if (StringUtils.isBlank(summary)) {
            logger.debug("{} returned nothing for '{}', using the extractive summariser",
                backend(), item.title());
            return fallback.summarize(item);
        }
        store(fingerprint, summary);
        return summary;
    }

    /**
     * Half the hourly budget is reserved for summaries: switching a filter on
     * must not quietly turn every recap of the cycle extractive.
     */
    @Override
    public String ask(String prompt, String label) {
        if (!allowCall(Math.max(1, maxCallsPerHour / 2))) {
            logger.warn("{} filter share of the {} call(s) per hour budget is spent, "
                        + "the prompt is skipped", backend(), maxCallsPerHour);
            return "";
        }
        return clean(generate(prompt, label));
    }

    static String promptFor(NewsItem item) {
        String description = ExtractiveSummarizer.plainText(item.rawDescription());
        return PROMPT
               + "\n\nTitle: " + item.title()
               + "\nDescription: " + StringUtils.defaultIfBlank(description, "(none)");
    }

    /**
     * Small models like to wrap the answer in quotes or open with "Summary:".
     * The message template has its own length budget, so trim here too.
     */
    static String clean(String raw) {
        if (StringUtils.isBlank(raw)) {
            return "";
        }
        String summary = raw.strip()
            .replaceAll("(?is)^\\s*(here (is|'s) (a |the )?summary\\s*:?|summary\\s*:)\\s*", "")
            .strip();
        if (summary.length() > 1 && summary.startsWith("\"") && summary.endsWith("\"")) {
            summary = summary.substring(1, summary.length() - 1).strip();
        }
        return summary.replaceAll("\\s+", " ");
    }

    private void store(String fingerprint, String summary) {
        SummaryCacheEntity entity = new SummaryCacheEntity();
        entity.setFingerprint(fingerprint);
        entity.setSummary(summary);
        entity.setCreatedAt(Instant.now());
        try {
            cache.save(entity);
        } catch (RuntimeException e) {
            // A cache miss costs time, not correctness.
            logger.debug("Could not cache the summary for {}: {}", fingerprint, e.toString());
        }
    }

    /** A budget of zero or less means unlimited — sensible for a local model. */
    private synchronized boolean allowCall(int limit) {
        if (maxCallsPerHour <= 0) {
            return true;
        }
        Instant cutoff = Instant.now().minus(Duration.ofHours(1));
        while (!callTimestamps.isEmpty() && callTimestamps.peekFirst().isBefore(cutoff)) {
            callTimestamps.removeFirst();
        }
        if (callTimestamps.size() >= limit) {
            return false;
        }
        callTimestamps.addLast(Instant.now());
        return true;
    }
}
