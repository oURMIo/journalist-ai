package com.dach.sagiri.news;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.dach.sagiri.config.BotProperties;
import com.dach.sagiri.news.model.NewsItem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The extra context a thin feed description buys: a mocked {@link HttpFetcher}
 * hands over the page bytes, nothing leaves the machine.
 */
class ArticleContextFetcherTest {

    private static final String URL = "https://example.com/story";

    /** Body paragraphs have to clear the 80-character bar the fetcher sets. */
    private static final String PROCESS = "The company said the new process enters risk "
                                          + "production next quarter, with volume ramping "
                                          + "through the following year.";
    private static final String ANALYSTS = "Analysts expect mobile chip designers to be the "
                                           + "first customers, since they need the density "
                                           + "more than the clock speed.";
    private static final String PLANT = "The plant in Dresden opened on Tuesday after four "
                                        + "years of construction work and a ten billion euro "
                                        + "investment.";

    private HttpFetcher fetcher;
    private BotProperties properties;
    private ArticleContextFetcher context;

    @BeforeEach
    void setUp() {
        fetcher = mock(HttpFetcher.class);
        properties = new BotProperties();
        context = new ArticleContextFetcher(fetcher, properties);
    }

    private static NewsItem item(String url) {
        return new NewsItem("id-1", "Chipmaker unveils a 2nm process", url, "Example",
            Instant.parse("2025-09-02T10:15:00Z"), "<a href=\"...\">Example</a>", null);
    }

    private void page(String html) {
        when(fetcher.getPage(anyString()))
            .thenReturn(Optional.of(html.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void takes_the_preview_text_and_the_leading_paragraphs() {
        page("""
            <html><head>
            <meta property="og:description" content="A chipmaker unveiled a 2nm process.">
            </head><body>
            <nav><p>%s</p></nav>
            <article>
            <p>Short caption.</p>
            <p>%s</p>
            <p>%s</p>
            </article>
            </body></html>""".formatted(PLANT, PROCESS, ANALYSTS));

        String text = context.contextFor(item(URL), "Example");

        assertThat(text)
            .isEqualTo("A chipmaker unveiled a 2nm process. " + PROCESS + " " + ANALYSTS);
        assertThat(text).as("navigation outside <article> is not article text")
            .doesNotContain("Dresden");
        verify(fetcher).getPage(URL);
    }

    @Test
    void drops_paragraphs_too_short_to_be_body_text() {
        page("""
            <html><body><article>
            <p>By Jane Doe</p>
            <p>Photo: Example</p>
            <p>%s</p>
            </article></body></html>""".formatted(PLANT));

        assertThat(context.contextFor(item(URL), ""))
            .isEqualTo(PLANT)
            .doesNotContain("Jane Doe");
    }

    @Test
    void keeps_the_preview_text_once_when_it_repeats_the_first_paragraph() {
        page("""
            <html><head>
            <meta property="og:description" content="%s">
            <meta name="description" content="%s">
            </head><body><article>
            <p>%s</p>
            </article></body></html>""".formatted(PLANT, PLANT, PLANT));

        assertThat(context.contextFor(item(URL), "")).isEqualTo(PLANT);
    }

    @Test
    void uses_the_meta_description_when_there_is_no_open_graph_tag() {
        page("""
            <html><head><meta name="description" content="A chipmaker unveiled a 2nm process.">
            </head><body></body></html>""");

        assertThat(context.contextFor(item(URL), ""))
            .isEqualTo("A chipmaker unveiled a 2nm process.");
    }

    @Test
    void reads_the_body_when_the_page_has_no_article_element() {
        page("<html><body><p>%s</p></body></html>".formatted(PLANT));

        assertThat(context.contextFor(item(URL), "")).isEqualTo(PLANT);
    }

    @Test
    void stops_at_the_configured_length() {
        properties.getArticleContext().setMaxChars(60);
        page("<html><body><article><p>%s</p><p>%s</p></article></body></html>"
            .formatted(PLANT, PROCESS));

        assertThat(context.contextFor(item(URL), ""))
            .hasSizeLessThanOrEqualTo(60)
            .endsWith("…")
            .doesNotContain("risk production");
    }

    @Test
    void asks_for_nothing_when_the_feed_description_is_long_enough() {
        properties.getArticleContext().setMinDescriptionChars(10);

        assertThat(context.contextFor(item(URL), "0123456789")).isEmpty();
        verifyNoInteractions(fetcher);
    }

    @Test
    void asks_for_nothing_when_the_feature_is_off() {
        properties.getArticleContext().setEnabled(false);

        assertThat(context.contextFor(item(URL), "")).isEmpty();
        verifyNoInteractions(fetcher);
    }

    @Test
    void asks_for_nothing_when_the_item_has_no_url() {
        assertThat(context.contextFor(item("   "), "")).isEmpty();
        assertThat(context.contextFor(item(null), "")).isEmpty();
        verifyNoInteractions(fetcher);
    }

    @Test
    void asks_for_nothing_when_the_link_is_an_unresolved_google_news_stub() {
        assertThat(context.contextFor(
            item("https://news.google.com/rss/articles/CBMiT0FVX3lxTE5"), "")).isEmpty();
        verifyNoInteractions(fetcher);
    }

    @Test
    void a_missing_description_still_counts_as_too_thin() {
        page("<html><body><article><p>%s</p></article></body></html>".formatted(PLANT));

        assertThat(context.contextFor(item(URL), null)).isEqualTo(PLANT);
        verify(fetcher).getPage(URL);
    }

    @Test
    void an_unreadable_page_yields_no_context_instead_of_an_error() {
        when(fetcher.getPage(anyString())).thenReturn(Optional.empty());

        assertThat(context.contextFor(item(URL), "")).isEmpty();
    }

    @Test
    void a_page_without_usable_text_yields_no_context() {
        when(fetcher.getPage(anyString()))
            .thenReturn(Optional.of(new byte[] {0x00, 0x01, 0x02, 0x03}));

        assertThat(context.contextFor(item(URL), "")).isEmpty();
    }
}
