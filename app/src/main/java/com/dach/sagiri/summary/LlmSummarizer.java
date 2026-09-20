package com.dach.sagiri.summary;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.dach.sagiri.dedup.SeenArticleService;
import com.dach.sagiri.news.ArticleContextFetcher;
import com.dach.sagiri.news.model.NewsItem;

/**
 * Shared plumbing for every model-backed summariser: the per-article cache, the
 * hourly call budget and — most importantly — the fallback.
 * <p>
 * The model sees the feed title and description and, when the feed carried
 * almost no description, the page's preview text and opening paragraphs from
 * {@link ArticleContextFetcher} — never the whole article. Any failure, throttle,
 * empty answer or "I have no information" non-answer falls back to
 * {@link ExtractiveSummarizer}, so the bot never goes silent because a model is
 * slow, missing or down.
 */
public abstract class LlmSummarizer implements Summarizer, LlmGateway {

    private static final Logger logger = LoggerFactory.getLogger(LlmSummarizer.class);

    static final String PROMPT = """
        Summarize this news item in 1-3 factual sentences in English. \
        No opinions, no speculation, no marketing language, no preamble. \
        Reply with the summary only. \
        Never state that information is missing and never ask for more: \
        when the text is short, summarize the headline itself.""";

    /** Minimum length of an answer that can plausibly be a summary. */
    static final int MIN_SUMMARY_LENGTH = 25;

    /**
     * Small models answer a thin prompt with "I don't have any information about
     * the news item" instead of a summary. Refusals open that way, real summaries
     * do not, so the check is anchored at the start of the answer.
     */
    private static final Pattern NON_ANSWER = Pattern.compile(
        "(?is)^\\W*(unfortunately|regrettably|sorry)?\\W*("
        + "i\\b.{0,60}?\\b(don't|dont|do not|cannot|can't|am unable|have no|was not able)"
        + "\\b.{0,60}?\\b(information|details|content|context|data|text|summar|article|news item)"
        + "|there (is|'s|are) (no|not enough|insufficient) (information|details|content|data)"
        + "\\b.{0,40}?\\b(about|on|in|for|provided|available|given|regarding|to summar)"
        + "|no (information|details|content|context|summary) (is |was |are |were )?"
        + "(provided|available|given|supplied|present)"
        + "|not enough (information|context|details|content) (to|about|for|was|is|has|in)"
        + "|insufficient (information|context|details|content)"
        + "|as an ai\\b|please provide\\b"
        + "|the (input|description|text|article|provided (text|description|input))"
        + " (is|was|appears) (insufficient|empty|missing|too short|blank|not enough|to be empty)"
        + ")");

    private final ExtractiveSummarizer fallback;
    private final SummaryCacheRepository cache;
    private final SeenArticleService seenArticles;
    private final ArticleContextFetcher articles;
    private final int maxCallsPerHour;

    /** Sliding window of call timestamps enforcing the hourly budget. */
    private final Deque<Instant> callTimestamps = new ArrayDeque<>();

    protected LlmSummarizer(ExtractiveSummarizer fallback, SummaryCacheRepository cache,
        SeenArticleService seenArticles, ArticleContextFetcher articles, int maxCallsPerHour) {
        this.fallback = fallback;
        this.cache = cache;
        this.seenArticles = seenArticles;
        this.articles = articles;
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

        String description = ExtractiveSummarizer.plainText(item.rawDescription());
        String context = articles.contextFor(item, description);

        if (!allowCall(maxCallsPerHour)) {
            logger.warn("{} budget of {} call(s) per hour is exhausted, using the extractive "
                        + "summariser", backend(), maxCallsPerHour);
            return fallbackFor(item, context);
        }

        String summary = clean(generate(promptFor(item, description, context), item.title()));
        if (StringUtils.isBlank(summary)) {
            logger.debug("{} returned nothing for '{}', using the extractive summariser",
                backend(), item.title());
            return fallbackFor(item, context);
        }
        if (isNonAnswer(summary)) {
            logger.debug("{} had nothing to say about '{}' ({}), using the extractive summariser",
                backend(), item.title(), StringUtils.abbreviate(summary, 80));
            return fallbackFor(item, context);
        }
        store(fingerprint, summary);
        return summary;
    }

    /** What the model was offered is also the best input the fallback can get. */
    private String fallbackFor(NewsItem item, String context) {
        return StringUtils.isBlank(context)
            ? fallback.summarize(item)
            : fallback.summarize(context, item.title());
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

    static String promptFor(NewsItem item, String description, String context) {
        StringBuilder prompt = new StringBuilder(PROMPT)
            .append("\n\nTitle: ").append(item.title())
            .append("\nSource: ").append(StringUtils.defaultIfBlank(item.sourceName(), "unknown"))
            .append("\nDescription: ").append(StringUtils.defaultIfBlank(description, "(none)"));
        if (StringUtils.isNotBlank(context)) {
            prompt.append("\nArticle: ").append(context);
        }
        return prompt.toString();
    }

    /**
     * A refusal is not a summary: it must never reach a topic and must never be
     * cached, so it is treated exactly like a failed call.
     */
    static boolean isNonAnswer(String summary) {
        return summary.length() < MIN_SUMMARY_LENGTH || NON_ANSWER.matcher(summary).find();
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
