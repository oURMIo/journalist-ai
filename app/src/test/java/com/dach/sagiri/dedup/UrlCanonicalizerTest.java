package com.dach.sagiri.dedup;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UrlCanonicalizerTest {

    private final UrlCanonicalizer canonicalizer = new UrlCanonicalizer();

    @Test
    void drops_tracking_parameters() {
        String withTracking =
            "https://example.com/story?utm_source=rss&utm_medium=feed&fbclid=abc&id=7";

        assertThat(canonicalizer.canonicalize(withTracking))
            .isEqualTo("https://example.com/story?id=7");
    }

    @Test
    void normalizes_host_case_www_and_trailing_slash() {
        assertThat(canonicalizer.canonicalize("HTTPS://WWW.Example.com/Story/"))
            .isEqualTo("https://example.com/Story");
    }

    @Test
    void ignores_the_fragment() {
        assertThat(canonicalizer.canonicalize("https://example.com/story#section-2"))
            .isEqualTo("https://example.com/story");
    }

    @Test
    void sorts_remaining_parameters_so_ordering_cannot_fool_us() {
        String first = canonicalizer.canonicalize("https://example.com/a?b=2&a=1");
        String second = canonicalizer.canonicalize("https://example.com/a?a=1&b=2");

        assertThat(first).isEqualTo(second);
    }

    @Test
    void the_same_article_shared_two_ways_collapses_to_one_identity() {
        String fromFeed = "https://www.example.com/news/item-42?utm_campaign=daily";
        String fromAggregator = "https://example.com/news/item-42";

        assertThat(canonicalizer.canonicalize(fromFeed))
            .isEqualTo(canonicalizer.canonicalize(fromAggregator));
    }

    @Test
    void an_unparseable_url_still_yields_a_usable_identity() {
        assertThat(canonicalizer.canonicalize("not a url")).isEqualTo("not a url");
        assertThat(canonicalizer.canonicalize("")).isEmpty();
        assertThat(canonicalizer.canonicalize(null)).isEmpty();
    }

    @Test
    void title_hash_ignores_punctuation_and_case() {
        assertThat(Fingerprints.titleHash("Chipmaker unveils 2nm process!"))
            .isEqualTo(Fingerprints.titleHash("chipmaker  unveils, 2nm process"));
    }

    @Test
    void title_hash_separates_different_stories() {
        assertThat(Fingerprints.titleHash("A story about chips"))
            .isNotEqualTo(Fingerprints.titleHash("A story about ships"));
    }
}
