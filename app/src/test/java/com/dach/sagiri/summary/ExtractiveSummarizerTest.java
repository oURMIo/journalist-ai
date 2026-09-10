package com.dach.sagiri.summary;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import com.dach.sagiri.news.model.NewsItem;

import static org.assertj.core.api.Assertions.assertThat;

class ExtractiveSummarizerTest {

    private final ExtractiveSummarizer summarizer = new ExtractiveSummarizer();

    private static NewsItem item(String description) {
        return new NewsItem("id-1", "Chipmaker unveils a 2nm process",
            "https://example.com/story", "Example", Instant.parse("2025-09-02T10:15:00Z"),
            description, null);
    }

    @Test
    void keeps_at_most_three_sentences() {
        String summary = summarizer.summarize(item(
            "First sentence. Second sentence. Third sentence. Fourth sentence."));

        assertThat(summary)
            .isEqualTo("First sentence. Second sentence. Third sentence.")
            .doesNotContain("Fourth");
    }

    @Test
    void strips_html_so_parse_mode_html_cannot_break() {
        String summary = summarizer.summarize(item(
            "<p>The company announced a <b>2nm</b> process.</p><script>alert(1)</script>"));

        assertThat(summary).isEqualTo("The company announced a 2nm process.");
    }

    @Test
    void decodes_html_entities() {
        assertThat(summarizer.summarize(item("Intel &amp; AMD compete.")))
            .isEqualTo("Intel & AMD compete.");
    }

    @Test
    void falls_back_to_the_title_when_the_feed_carries_no_description() {
        assertThat(summarizer.summarize(item(""))).isEqualTo("Chipmaker unveils a 2nm process");
        assertThat(summarizer.summarize(item(null))).isEqualTo("Chipmaker unveils a 2nm process");
    }

    @Test
    void never_exceeds_the_length_budget() {
        String longSentence = "word ".repeat(400) + ".";

        String summary = summarizer.summarize(item(longSentence));

        assertThat(summary).hasSizeLessThanOrEqualTo(ExtractiveSummarizer.MAX_LENGTH);
    }

    @Test
    void collapses_whitespace_from_a_multiline_description() {
        assertThat(summarizer.summarize(item("A   line\n\n  and   another.")))
            .isEqualTo("A line and another.");
    }
}
