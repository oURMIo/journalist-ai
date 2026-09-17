package com.dach.sagiri.filter;

import java.util.List;
import java.util.Locale;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import com.dach.sagiri.config.BotProperties;
import com.dach.sagiri.news.model.NewsItem;
import com.dach.sagiri.summary.ExtractiveSummarizer;
import com.dach.sagiri.summary.LlmGateway;
import com.dach.sagiri.summary.Summarizer;

/**
 * Asks the model whether an article belongs in a topic.
 * <p>
 * Keyword matching only sees the literal word; the model catches the same
 * subject under another name — "Golang", "the Go team", "gopher" for a filter on
 * {@code go} — and, given the domain of the topic, the opposite case too: an
 * article that matches the name of the topic but not its subject, "Java" the
 * island under a topic about the language. Only the headline and the feed
 * description are sent, exactly as for summaries.
 * <p>
 * Anything short of a clear answer is {@link Verdict#UNKNOWN}: a model that is
 * down, slow or out of budget must not silently swallow the news.
 */
@Component
public class AiRelevanceChecker {

    private static final Logger logger = LoggerFactory.getLogger(AiRelevanceChecker.class);

    public enum Verdict {
        ALLOW,
        BLOCK,
        /** No opinion — the caller decides on keywords alone. */
        UNKNOWN
    }

    static final String ALLOW_TOKEN = "ALLOW";
    static final String BLOCK_TOKEN = "BLOCK";

    /** "Answer:", "Verdict:" - anything longer is prose, not a label. */
    private static final int MAX_LABEL_LENGTH = 8;

    /** Null when {@code SUMMARIZER=extractive}: there is no model to ask. */
    private final LlmGateway gateway;
    private final boolean enabled;

    @Autowired
    public AiRelevanceChecker(@Qualifier("summarizer") Summarizer summarizer,
        BotProperties properties) {
        this(summarizer instanceof LlmGateway llm ? llm : null,
            properties.getFilter().isAiCheck());
    }

    AiRelevanceChecker(LlmGateway gateway, boolean enabled) {
        this.gateway = gateway;
        this.enabled = enabled;
        if (enabled && gateway == null) {
            logger.info("FILTER_AI_CHECK is on but the summariser uses no model, "
                        + "filters run on keywords only");
        }
    }

    public boolean available() {
        return enabled && gateway != null;
    }

    public Verdict check(NewsItem item, TopicFilters filters) {
        if (!available()) {
            return Verdict.UNKNOWN;
        }
        Verdict verdict = parse(gateway.ask(prompt(item, filters), item.title()));
        logger.debug("AI verdict {} for '{}' in topic '{}'", verdict, item.title(),
            filters.slug());
        return verdict;
    }

    static String prompt(NewsItem item, TopicFilters filters) {
        StringBuilder prompt = new StringBuilder()
            .append("You are a news filter for a reader following the topic \"")
            .append(filters.slug()).append("\".\n");
        if (filters.hasDomain()) {
            // Without this the model is as lost as the search engine: "java" alone
            // does not say which java the reader meant.
            prompt.append("That topic means: ").append(filters.domain()).append(".\n")
                .append("An article that only matches the name of the topic while being ")
                .append("about a different subject is unwanted.\n");
        }
        if (!filters.exclude().isEmpty()) {
            prompt.append("The reader does NOT want anything about: ")
                .append(join(filters.exclude())).append(".\n");
        }
        if (!filters.include().isEmpty()) {
            prompt.append("The reader wants only articles about: ")
                .append(join(filters.include())).append(".\n");
        }
        return prompt
            .append("Judge by meaning, not by spelling: a synonym, an abbreviation, ")
            .append("a product or a project name of an unwanted subject counts as that subject.\n")
            .append("Headline: ").append(StringUtils.trimToEmpty(item.title())).append('\n')
            .append("Description: ")
            .append(StringUtils.defaultIfBlank(
                ExtractiveSummarizer.plainText(item.rawDescription()), "(none)"))
            .append("\n\nAnswer with one word, ").append(BLOCK_TOKEN)
            .append(" if this article is unwanted, otherwise ").append(ALLOW_TOKEN).append('.')
            .toString();
    }

    /**
     * The prompt asks for one word and a small model answers "ALLOW, there is
     * nothing to block here", so the verdict is read from the start of the answer
     * only. A token mentioned in passing - "I would not block this one" - is
     * deliberately no verdict at all: a BLOCK is stored and buries the article for
     * good, while no verdict costs nothing but one more attempt.
     */
    static Verdict parse(String answer) {
        String head = stripToLetter(
            StringUtils.upperCase(StringUtils.trimToEmpty(answer), Locale.ROOT));
        Verdict verdict = readVerdict(head);
        if (verdict != Verdict.UNKNOWN) {
            return verdict;
        }
        // "Answer: BLOCK" and friends put the verdict behind a short label.
        int label = head.indexOf(':');
        return label >= 0 && label <= MAX_LABEL_LENGTH
            ? readVerdict(stripToLetter(head.substring(label + 1)))
            : Verdict.UNKNOWN;
    }

    private static Verdict readVerdict(String head) {
        if (head.startsWith(BLOCK_TOKEN)) {
            return Verdict.BLOCK;
        }
        return head.startsWith(ALLOW_TOKEN) ? Verdict.ALLOW : Verdict.UNKNOWN;
    }

    private static String stripToLetter(String text) {
        int start = 0;
        while (start < text.length() && !Character.isLetter(text.charAt(start))) {
            start++;
        }
        return text.substring(start);
    }

    private static String join(List<String> terms) {
        return String.join(", ", terms);
    }
}
