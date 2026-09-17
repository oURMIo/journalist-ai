package com.dach.sagiri.filter;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.dach.sagiri.news.model.NewsItem;

import static org.assertj.core.api.Assertions.assertThat;

class KeywordMatcherTest {

    private static NewsItem item(String title, String description) {
        return new NewsItem("id", title, "https://example.com/a", "Example", Instant.now(),
            description, null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Go 1.24 is out", "Why we rewrote it in Go.", "GO generics, revisited"})
    void matches_a_term_as_a_whole_word(String title) {
        assertThat(KeywordMatcher.matches(KeywordMatcher.haystack(item(title, "")), "go")).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Google ships a new model", "Ongoing outage at a cloud provider",
        "Golang is not spelled out here"})
    void does_not_match_a_term_inside_another_word(String title) {
        assertThat(KeywordMatcher.matches(KeywordMatcher.haystack(item(title, "")), "go")).isFalse();
    }

    @Test
    void matches_a_phrase_only_as_a_whole_sequence() {
        String haystack = KeywordMatcher.haystack(item("A machine learning benchmark", ""));

        assertThat(KeywordMatcher.matches(haystack, "machine learning")).isTrue();
        assertThat(KeywordMatcher.matches(haystack, "learning machine")).isFalse();
    }

    @Test
    void searches_the_description_too_and_ignores_its_html() {
        String haystack =
            KeywordMatcher.haystack(item("Weekly digest", "<p>A post about <b>crypto</b>.</p>"));

        assertThat(KeywordMatcher.matches(haystack, "crypto")).isTrue();
        assertThat(haystack).isEqualTo("weekly digest a post about crypto");
    }

    @Test
    void first_match_reports_which_term_hit() {
        String haystack = KeywordMatcher.haystack(item("Rust and Go at scale", ""));

        assertThat(KeywordMatcher.firstMatch(haystack, List.of("crypto", "go", "rust")))
            .contains("go");
        assertThat(KeywordMatcher.firstMatch(haystack, List.of("crypto", "nft"))).isEmpty();
    }

    @Test
    void a_blank_term_never_matches() {
        assertThat(KeywordMatcher.matches("anything at all", "  ")).isFalse();
        assertThat(KeywordMatcher.matches("anything at all", "!!!")).isFalse();
    }

    @Test
    void normalization_is_case_and_punctuation_insensitive() {
        assertThat(KeywordMatcher.normalize("  C++/Rust: the 2.0 Way!  "))
            .isEqualTo("c rust the 2 0 way");
    }
}
