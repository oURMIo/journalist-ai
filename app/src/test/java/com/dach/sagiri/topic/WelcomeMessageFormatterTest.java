package com.dach.sagiri.topic;

import java.util.List;
import org.junit.jupiter.api.Test;
import com.dach.sagiri.config.BotProperties;
import com.dach.sagiri.config.MessageTemplates;
import com.dach.sagiri.news.model.TopicQuery;

import static org.assertj.core.api.Assertions.assertThat;

class WelcomeMessageFormatterTest {

    private final BotProperties properties = new BotProperties();
    private final MessageTemplates messages = new MessageTemplates();
    private final WelcomeMessageFormatter formatter =
        new WelcomeMessageFormatter(messages, properties);

    WelcomeMessageFormatterTest() {
        messages.setWelcome("""
            📌 <b>News topic: {slug}</b>
            Sources: {sources}
            Every {interval} min, up to {maxItems} items.
            Create more with {prefix}<subject>.""");
        messages.setSourcesFallback("search across all English sources");
        messages.setSourcesMore("and {count} more");
        properties.setPollIntervalMinutes(30);
        properties.setMaxItemsPerTopic(5);
    }

    @Test
    void fills_every_placeholder_for_a_preset_topic() {
        String html = formatter.format(new TopicQuery("it", "technology", List.of(
            "https://feeds.arstechnica.com/arstechnica/technology-lab",
            "https://techcrunch.com/feed/"), true));

        assertThat(html)
            .contains("News topic: It")
            .contains("arstechnica.com, techcrunch.com")
            .contains("Every 30 min, up to 5 items.")
            .contains("news-<subject>")
            .doesNotContain("{");
    }

    @Test
    void titles_a_multi_word_slug() {
        String html = formatter.format(new TopicQuery("ai-safety", "ai safety", List.of(), false));

        assertThat(html).contains("News topic: Ai Safety");
    }

    @Test
    void a_topic_without_a_preset_says_so_plainly() {
        String html = formatter.format(new TopicQuery("biotech", "biotech", List.of(), false));

        assertThat(html).contains("search across all English sources");
    }

    @Test
    void lists_at_most_three_domains_and_counts_the_rest() {
        TopicQuery query = new TopicQuery("news", "q", List.of(
            "https://a.example/feed", "https://b.example/feed", "https://c.example/feed",
            "https://d.example/feed", "https://e.example/feed"), true);

        assertThat(formatter.describeSources(query))
            .isEqualTo("a.example, b.example, c.example and 2 more");
    }

    @Test
    void deduplicates_domains_that_appear_in_several_feeds() {
        TopicQuery query = new TopicQuery("it", "q", List.of(
            "https://www.example.com/rss/a", "https://example.com/rss/b"), true);

        assertThat(formatter.describeSources(query)).isEqualTo("example.com");
    }
}
