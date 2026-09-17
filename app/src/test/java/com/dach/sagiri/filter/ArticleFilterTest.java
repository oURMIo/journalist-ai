package com.dach.sagiri.filter;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.dach.sagiri.filter.AiRelevanceChecker.Verdict;
import com.dach.sagiri.news.model.NewsItem;
import com.dach.sagiri.topic.TopicEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ArticleFilterTest {

    private final TopicFilterService filters = mock(TopicFilterService.class);
    private final AiRelevanceChecker ai = mock(AiRelevanceChecker.class);
    private final ArticleFilter articleFilter = new ArticleFilter(filters, ai);

    private final TopicEntity topic = topic();

    private static TopicEntity topic() {
        TopicEntity topic = new TopicEntity();
        topic.setId(7L);
        topic.setSlug("it");
        return topic;
    }

    private static NewsItem item(String title) {
        return new NewsItem("id", title, "https://example.com/a", "Example", Instant.now(), "",
            null);
    }

    private TopicFilters rules(List<String> exclude, List<String> include) {
        TopicFilters rules = new TopicFilters("it", exclude, include);
        when(filters.of(topic)).thenReturn(rules);
        return rules;
    }

    /** The job reads the rules once per cycle and hands them to every decision. */
    private FilterDecision decide(TopicFilters rules, NewsItem item) {
        return articleFilter.decide(rules, item);
    }

    @Test
    void a_topic_without_rules_publishes_everything_without_paying_for_a_model() {
        TopicFilters rules = rules(List.of(), List.of());

        assertThat(decide(rules, item("Go 1.24 is out")).allowed()).isTrue();
        verifyNoInteractions(ai);
    }

    @Test
    void an_excluded_keyword_blocks_before_the_model_is_asked() {
        TopicFilters rules = rules(List.of("go"), List.of());

        FilterDecision decision = decide(rules, item("Go 1.24 is out"));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo("keyword: go");
        assertThat(decision.remember()).as("free to re-apply next cycle").isFalse();
        verify(ai, never()).check(any(), any());
    }

    @Test
    void the_model_blocks_what_the_keyword_missed() {
        TopicFilters rules = rules(List.of("go"), List.of());
        when(ai.check(any(), any())).thenReturn(Verdict.BLOCK);

        FilterDecision decision = decide(rules, item("Golang generics, revisited"));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo("ai");
        assertThat(decision.remember()).as("cost a model call").isTrue();
    }

    @Test
    void a_model_failure_lets_the_article_through() {
        TopicFilters rules = rules(List.of("go"), List.of());
        when(ai.check(any(), any())).thenReturn(Verdict.UNKNOWN);

        assertThat(decide(rules, item("Rust 2.0 is out")).allowed()).isTrue();
    }

    @Test
    void a_required_keyword_is_enforced_when_there_is_no_model_to_ask() {
        TopicFilters rules = rules(List.of(), List.of("kubernetes"));
        when(ai.available()).thenReturn(false);
        when(ai.check(any(), any())).thenReturn(Verdict.UNKNOWN);

        FilterDecision decision = decide(rules, item("A new database release"));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.reason()).isEqualTo("no-required-keyword");
        assertThat(decision.postponed()).as("nobody to ask, the answer will not change")
            .isFalse();
        assertThat(decide(rules, item("Kubernetes 1.33 is out")).allowed()).isTrue();
    }

    @Test
    void a_model_that_could_not_answer_postpones_instead_of_dropping() {
        TopicFilters rules = rules(List.of(), List.of("kubernetes"));
        when(ai.available()).thenReturn(true);
        when(ai.check(any(), any())).thenReturn(Verdict.UNKNOWN);

        FilterDecision decision = decide(rules, item("A new database release"));

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.postponed()).as("ask again once the model is back").isTrue();
        assertThat(decision.remember()).isFalse();
    }

    @Test
    void the_model_may_accept_an_article_that_carries_no_required_keyword() {
        TopicFilters rules = rules(List.of(), List.of("kubernetes"));
        when(ai.check(any(), any())).thenReturn(Verdict.ALLOW);

        assertThat(decide(rules, item("K8s operators in practice")).allowed()).isTrue();
    }
}
