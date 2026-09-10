package com.dach.sagiri.news;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import com.dach.sagiri.news.model.NewsItem;
import com.dach.sagiri.news.rss.FeedParser;

import static org.assertj.core.api.Assertions.assertThat;

/** Parsing runs against feed files on disk — the test suite never touches the network. */
class FeedParserTest {

    private final FeedParser parser = new FeedParser();

    static byte[] fixture(String name) {
        try (InputStream stream =
                 FeedParserTest.class.getResourceAsStream("/feeds/" + name)) {
            assertThat(stream).as("fixture %s", name).isNotNull();
            return stream.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void reads_an_rss_feed() {
        List<NewsItem> items = parser.parse(fixture("techcrunch-sample.xml"),
            "https://example.com/feed");

        assertThat(items).hasSize(3);
        NewsItem first = items.getFirst();
        assertThat(first.title()).isEqualTo("Chipmaker unveils a 2nm process");
        assertThat(first.externalId()).isEqualTo("example-guid-1");
        assertThat(first.sourceName()).isEqualTo("Sample Tech Feed");
        assertThat(first.publishedAt()).isEqualTo(Instant.parse("2025-09-02T10:15:00Z"));
        assertThat(first.rawDescription()).contains("<b>2nm</b>");
        assertThat(first.imageUrl()).isEqualTo("https://example.com/img/chip.jpg");
    }

    @Test
    void reads_an_atom_feed() {
        List<NewsItem> items = parser.parse(fixture("atom-sample.xml"),
            "https://science.example.org/feed");

        assertThat(items).singleElement().satisfies(item -> {
            assertThat(item.title()).isEqualTo("Telescope spots an unusual exoplanet");
            assertThat(item.url()).isEqualTo("https://science.example.org/exoplanet");
            assertThat(item.publishedAt()).isEqualTo(Instant.parse("2025-09-02T11:30:00Z"));
        });
    }

    @Test
    void an_item_without_a_date_falls_back_to_now_instead_of_being_dropped() {
        Instant before = Instant.now().minusSeconds(1);

        List<NewsItem> items = parser.parse(fixture("techcrunch-sample.xml"),
            "https://example.com/feed");

        NewsItem undated = items.stream()
            .filter(item -> item.title().equals("Entry without a date"))
            .findFirst()
            .orElseThrow();
        assertThat(undated.publishedAt()).isAfter(before);
    }

    @Test
    void broken_xml_yields_no_items_instead_of_an_exception() {
        assertThat(parser.parse(fixture("broken.xml"), "https://example.com/feed")).isEmpty();
    }

    @Test
    void a_non_feed_payload_yields_no_items() {
        byte[] html = "<html><body>not a feed</body></html>".getBytes(StandardCharsets.UTF_8);

        assertThat(parser.parse(html, "https://example.com/feed")).isEmpty();
    }
}
