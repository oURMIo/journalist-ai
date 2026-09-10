package com.dach.sagiri.telegram;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import com.dach.sagiri.config.BotProperties;
import com.dach.sagiri.news.model.NewsItem;

import static org.assertj.core.api.Assertions.assertThat;

class MessageFormatterTest {

    private final MessageFormatter formatter = new MessageFormatter(new BotProperties());

    private static NewsItem item(String title, String url, String source) {
        return new NewsItem("id", title, url, source,
            Instant.parse("2026-09-06T14:32:00Z"), "", null);
    }

    @Test
    void renders_the_message_template_from_the_spec() {
        String html = formatter.format(
            item("Chipmaker unveils a 2nm process", "https://example.com/story", "Example News"),
            "The company announced a 2nm process.");

        assertThat(html).isEqualTo("""
            🗞 <b>Chipmaker unveils a 2nm process</b>

            The company announced a 2nm process.

            🕒 2026-09-06 14:32 UTC  •  📰 Example News
            🔗 <a href="https://example.com/story">Read full article</a>""");
    }

    @Test
    void escapes_the_three_characters_telegram_treats_as_markup() {
        String html = formatter.format(
            item("Intel & AMD <clash> over 2nm", "https://example.com/a", "R&D Weekly"),
            "A < B & B > C");

        assertThat(html)
            .contains("Intel &amp; AMD &lt;clash&gt; over 2nm")
            .contains("A &lt; B &amp; B &gt; C")
            .contains("R&amp;D Weekly");
    }

    @Test
    void escapes_quotes_inside_the_link_attribute() {
        String html = formatter.format(
            item("Title", "https://example.com/a\"onmouseover=x", "Source"), "Summary");

        assertThat(html).contains("href=\"https://example.com/a&quot;onmouseover=x\"");
    }

    @Test
    void truncates_an_overlong_title() {
        String html = formatter.format(
            item("t".repeat(400), "https://example.com/a", "Source"), "Summary");

        assertThat(html).contains("t".repeat(MessageFormatter.MAX_TITLE - 1) + "…");
    }

    @Test
    void truncates_an_overlong_summary() {
        String html = formatter.format(
            item("Title", "https://example.com/a", "Source"), "s".repeat(900));

        assertThat(html).contains("s".repeat(MessageFormatter.MAX_SUMMARY - 1) + "…");
    }

    @Test
    void the_result_always_fits_a_telegram_message() {
        String html = formatter.format(
            item("t".repeat(5000), "https://example.com/" + "u".repeat(5000), "s".repeat(500)),
            "x".repeat(5000));

        assertThat(html).hasSizeLessThanOrEqualTo(HtmlText.MAX_MESSAGE_LENGTH);
    }

    @Test
    void omits_the_summary_block_when_there_is_nothing_to_say() {
        String html = formatter.format(
            item("Title", "https://example.com/a", "Source"), "");

        assertThat(html).startsWith("🗞 <b>Title</b>\n\n🕒");
    }

    @Test
    void names_an_unknown_source_rather_than_leaving_a_gap() {
        String html = formatter.format(item("Title", "https://example.com/a", ""), "Summary");

        assertThat(html).contains("📰 unknown");
    }
}
