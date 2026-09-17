package com.dach.sagiri.filter;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import com.dach.sagiri.filter.AiRelevanceChecker.Verdict;
import com.dach.sagiri.news.model.NewsItem;
import com.dach.sagiri.summary.LlmGateway;

import static org.assertj.core.api.Assertions.assertThat;

class AiRelevanceCheckerTest {

    private static final NewsItem ITEM = new NewsItem("id", "Go 1.24 lands generics tweaks",
        "https://example.com/go", "Example", Instant.now(), "<p>The Go team shipped it.</p>", null);

    private static final TopicFilters FILTERS =
        new TopicFilters("it", List.of("go"), List.of("kubernetes"));

    private static AiRelevanceChecker checker(String answer, AtomicReference<String> prompt) {
        return new AiRelevanceChecker((asked, item) -> {
            prompt.set(asked);
            return answer;
        }, true);
    }

    @Test
    void a_block_answer_blocks() {
        assertThat(checker("BLOCK", new AtomicReference<>()).check(ITEM, FILTERS))
            .isEqualTo(Verdict.BLOCK);
        assertThat(checker("BLOCK - the article is about Go", new AtomicReference<>())
            .check(ITEM, FILTERS)).isEqualTo(Verdict.BLOCK);
    }

    @Test
    void an_allow_answer_allows() {
        assertThat(checker("ALLOW", new AtomicReference<>()).check(ITEM, FILTERS))
            .isEqualTo(Verdict.ALLOW);
    }

    @Test
    void a_chatty_answer_is_read_by_the_verdict_it_opens_with() {
        assertThat(checker("ALLOW, there is nothing to block here", new AtomicReference<>())
            .check(ITEM, FILTERS)).isEqualTo(Verdict.ALLOW);
        assertThat(checker("BLOCK. I would not allow this one", new AtomicReference<>())
            .check(ITEM, FILTERS)).isEqualTo(Verdict.BLOCK);
        assertThat(checker("Answer: BLOCK", new AtomicReference<>()).check(ITEM, FILTERS))
            .isEqualTo(Verdict.BLOCK);
    }

    @Test
    void a_token_mentioned_in_passing_is_not_a_verdict() {
        assertThat(checker("I would not block this one", new AtomicReference<>())
            .check(ITEM, FILTERS))
            .as("a wrong BLOCK is stored and buries the article")
            .isEqualTo(Verdict.UNKNOWN);
    }

    @Test
    void an_unusable_answer_is_no_opinion() {
        assertThat(checker("", new AtomicReference<>()).check(ITEM, FILTERS))
            .isEqualTo(Verdict.UNKNOWN);
        assertThat(checker("I am not sure", new AtomicReference<>()).check(ITEM, FILTERS))
            .isEqualTo(Verdict.UNKNOWN);
    }

    @Test
    void the_prompt_carries_the_topic_the_rules_and_only_the_feed_text() {
        AtomicReference<String> prompt = new AtomicReference<>();

        checker("ALLOW", prompt).check(ITEM, FILTERS);

        assertThat(prompt.get())
            .contains("\"it\"")
            .contains("does NOT want anything about: go")
            .contains("only articles about: kubernetes")
            .contains("Go 1.24 lands generics tweaks")
            .contains("The Go team shipped it.")
            .doesNotContain("<p>");
    }

    @Test
    void without_a_model_the_checker_has_no_opinion_and_asks_nobody() {
        AiRelevanceChecker offline = new AiRelevanceChecker((LlmGateway) null, true);
        AiRelevanceChecker disabled = new AiRelevanceChecker((prompt, item) -> "BLOCK", false);

        assertThat(offline.available()).isFalse();
        assertThat(offline.check(ITEM, FILTERS)).isEqualTo(Verdict.UNKNOWN);
        assertThat(disabled.available()).isFalse();
        assertThat(disabled.check(ITEM, FILTERS)).isEqualTo(Verdict.UNKNOWN);
    }
}
