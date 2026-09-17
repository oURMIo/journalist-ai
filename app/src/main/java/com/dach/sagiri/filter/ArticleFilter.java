package com.dach.sagiri.filter;

import java.util.Optional;
import org.springframework.stereotype.Component;
import com.dach.sagiri.filter.AiRelevanceChecker.Verdict;
import com.dach.sagiri.news.model.NewsItem;
import com.dach.sagiri.topic.TopicEntity;

/**
 * Decides whether an article survives the rules of its topic.
 * <p>
 * Two passes, cheapest first: a literal keyword hit blocks the article without
 * paying for a model call, and only what the keywords let through is offered to
 * {@link AiRelevanceChecker} for the by-meaning check. With no model available
 * the keywords alone decide, and an article that broke no rule is published —
 * the filter narrows the feed, it never silences it.
 * <p>
 * Only the model's own verdict is stored. A model that was merely unreachable
 * produces no verdict at all, and that article is postponed rather than dropped:
 * the cycle must not move its look-back past something it could not judge.
 */
@Component
public class ArticleFilter {

    static final String EXCLUDED_BY_KEYWORD = "keyword";
    static final String EXCLUDED_BY_AI = "ai";
    static final String NO_REQUIRED_KEYWORD = "no-required-keyword";

    private final TopicFilterService filters;
    private final AiRelevanceChecker ai;

    public ArticleFilter(TopicFilterService filters, AiRelevanceChecker ai) {
        this.filters = filters;
        this.ai = ai;
    }

    /** Read once per cycle: the rules cannot change while a topic is polled. */
    public TopicFilters rulesOf(TopicEntity topic) {
        return filters.of(topic);
    }

    public FilterDecision decide(TopicFilters rules, NewsItem item) {
        if (rules.isEmpty()) {
            return FilterDecision.allow();
        }

        String haystack = KeywordMatcher.haystack(item);
        Optional<String> excluded = KeywordMatcher.firstMatch(haystack, rules.exclude());
        if (excluded.isPresent()) {
            return FilterDecision.block(EXCLUDED_BY_KEYWORD + ": " + excluded.get());
        }
        boolean requiredMissing = !rules.include().isEmpty()
                                  && KeywordMatcher.firstMatch(haystack, rules.include()).isEmpty();

        Verdict verdict = ai.check(item, rules);
        if (verdict == Verdict.BLOCK) {
            return FilterDecision.blockAndRemember(EXCLUDED_BY_AI);
        }
        if (verdict == Verdict.ALLOW || !requiredMissing) {
            // The model read the whole headline, so it overrules a missing keyword.
            return FilterDecision.allow();
        }
        // No required keyword and no verdict: final when there is no model to ask,
        // worth one more attempt when the model we do have simply could not answer.
        return ai.available()
            ? FilterDecision.postpone(NO_REQUIRED_KEYWORD)
            : FilterDecision.block(NO_REQUIRED_KEYWORD);
    }
}
