package com.dach.sagiri.topic;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import com.dach.sagiri.config.BotProperties;

import static org.assertj.core.api.Assertions.assertThat;

class TopicSlugParserTest {

    private final TopicSlugParser parser = new TopicSlugParser(properties("news-"));

    private static BotProperties properties(String prefix) {
        BotProperties properties = new BotProperties();
        properties.setTopicPrefix(prefix);
        return properties;
    }

    @ParameterizedTest
    @CsvSource({
        "news-it,                it",
        "news-ai-safety,         ai-safety",
        "News-IT,                it",
        "news_it,                it",
        "NEWS_AI_SAFETY,         ai-safety",
        "'  news-space  ',       space",
        "news-machine learning,  machine-learning",
        "news--double--dash,     double-dash"
    })
    void parses_news_topic_names_into_slugs(String rawName, String expectedSlug) {
        assertThat(parser.parse(rawName)).contains(expectedSlug);
    }

    @Test
    void strips_emoji_and_decoration_from_the_name() {
        assertThat(parser.parse("🤖 news-ai-safety 🔥")).contains("ai-safety");
        assertThat(parser.parse("news-it ✅")).contains("it");
    }

    @ParameterizedTest
    @ValueSource(strings = {"general", "random chat", "newsletter", "news", "announcements"})
    void ignores_topics_that_do_not_carry_the_prefix(String rawName) {
        assertThat(parser.parse(rawName)).isEmpty();
    }

    @Test
    void ignores_blank_and_null_names() {
        assertThat(parser.parse(null)).isEmpty();
        assertThat(parser.parse("   ")).isEmpty();
    }

    @Test
    void honours_a_custom_prefix() {
        TopicSlugParser custom = new TopicSlugParser(properties("feed-"));

        assertThat(custom.parse("feed-space")).contains("space");
        assertThat(custom.parse("news-space")).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
        "biotech,        biotech",
        "news-it,        it",
        "news_it,        it",
        "NEWS-IT,        it",
        "'ai safety',    ai-safety",
        "--trimmed--,    trimmed"
    })
    void normalizes_a_slug_requested_by_a_user(String input, String expected) {
        assertThat(parser.normalizeRequestedSlug(input)).isEqualTo(expected);
    }

    @Test
    void a_bare_prefix_carries_no_subject() {
        assertThat(parser.normalizeRequestedSlug("news")).isEmpty();
        assertThat(parser.normalizeRequestedSlug("news-")).isEmpty();
    }

    @Test
    void accepts_only_ascii_slugs_that_fit_the_telegram_name_limit() {
        assertThat(parser.isProvisionable("ai-safety")).isTrue();
        assertThat(parser.isProvisionable("it")).isTrue();

        assertThat(parser.isProvisionable("a")).as("shorter than two characters").isFalse();
        assertThat(parser.isProvisionable("наука")).as("non-ascii").isFalse();
        assertThat(parser.isProvisionable("")).isFalse();
        assertThat(parser.isProvisionable("x".repeat(130))).as("name would exceed 128").isFalse();
    }

    @Test
    void renders_a_readable_name_for_the_welcome_message() {
        assertThat(TopicSlugParser.displayName("ai-safety")).isEqualTo("Ai Safety");
        assertThat(TopicSlugParser.displayName("it")).isEqualTo("It");
    }

    @Test
    void a_topic_renamed_away_from_the_prefix_stops_being_ours() {
        Optional<String> before = parser.parse("news-crypto");
        Optional<String> after = parser.parse("crypto");

        assertThat(before).contains("crypto");
        assertThat(after).isEmpty();
    }
}
