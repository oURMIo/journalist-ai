package com.dach.sagiri.news;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.dach.sagiri.config.BotProperties;
import com.dach.sagiri.news.googlenews.GoogleNewsLinkDecoder;
import com.dach.sagiri.news.googlenews.GoogleNewsRssProvider;
import com.dach.sagiri.news.model.NewsItem;
import com.dach.sagiri.news.model.TopicQuery;
import com.dach.sagiri.news.rss.FeedParser;
import com.dach.sagiri.news.rss.RssNewsProvider;

import org.assertj.core.api.InstanceOfAssertFactories;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Providers against a loopback JDK http server: nothing leaves the machine, but
 * the real HTTP paths — 5xx, timeouts and malformed XML — are exercised.
 */
class NewsProviderHttpTest {

    private static final Instant SINCE = Instant.parse("2020-01-01T00:00:00Z");
    private static final String GOOGLE_STUB =
        "https://news.google.com/rss/articles/CBMiT0FVX3lxTE5";

    private HttpServer server;
    private String baseUrl;
    private BotProperties properties;
    private HttpFetcher fetcher;
    private final FeedParser parser = new FeedParser();
    /** Decoding a stub is a network call of its own: mocked, never made. */
    private final GoogleNewsLinkDecoder decoder = mock(GoogleNewsLinkDecoder.class);
    private final AtomicInteger pageErrorHits = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/feed", feedHandler("techcrunch-sample.xml"));
        server.createContext("/good", feedHandler("atom-sample.xml"));
        server.createContext("/malformed", feedHandler("broken.xml"));
        server.createContext("/rss/search", feedHandler("google-news-sample.xml"));
        server.createContext("/broken", exchange -> respond(exchange, 500, new byte[0]));
        server.createContext("/page", exchange -> respond(exchange, 200,
            "<html><body><p>The plant opened.</p></body></html>".getBytes(UTF_8)));
        server.createContext("/huge", NewsProviderHttpTest::respondOversized);
        server.createContext("/rpc", exchange ->
            respond(exchange, 200, exchange.getRequestBody().readAllBytes()));
        server.createContext("/rpc-error", exchange -> respond(exchange, 500, new byte[0]));
        server.createContext("/page-error", exchange -> {
            pageErrorHits.incrementAndGet();
            respond(exchange, 500, new byte[0]);
        });
        server.createContext("/slow", exchange -> {
            sleep();
            respond(exchange, 200, new byte[0]);
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

        properties = new BotProperties();
        properties.getHttp().setTimeoutSeconds(1);
        properties.getHttp().setRetries(1);
        properties.setGoogleNewsBaseUrl(baseUrl + "/rss/search");

        fetcher = new HttpFetcher(newHttpClient(), properties);
        when(decoder.decode(anyString())).thenReturn(Optional.empty());
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static HttpClient newHttpClient() {
        return HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(1))
            .build();
    }

    private static HttpHandler feedHandler(String fixture) {
        return exchange -> respond(exchange, 200, FeedParserTest.fixture(fixture));
    }

    private static void respond(HttpExchange exchange, int status, byte[] body)
        throws IOException {

        exchange.getResponseHeaders().add("Content-Type", "application/rss+xml; charset=utf-8");
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
        exchange.close();
    }

    /** Announces more than the 2 MB cap and actually sends it, so the cap is what decides. */
    private static void respondOversized(HttpExchange exchange) throws IOException {
        byte[] chunk = new byte[64 * 1024];
        java.util.Arrays.fill(chunk, (byte) 'x');
        long declared = 2L * 1024 * 1024 + chunk.length;
        exchange.getResponseHeaders().add("Content-Type", "text/html");
        exchange.sendResponseHeaders(200, declared);
        try (OutputStream out = exchange.getResponseBody()) {
            for (long written = 0; written < declared; written += chunk.length) {
                out.write(chunk);
            }
        }
        exchange.close();
    }

    private static void sleep() {
        try {
            Thread.sleep(3_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Redirect resolution would leave the loopback; stub it out per scenario. */
    private HttpFetcher fetcherResolvingTo(String resolved) {
        return new HttpFetcher(newHttpClient(), properties) {
            @Override
            public String resolveFinalUrl(String url) {
                return resolved == null ? url : resolved;
            }
        };
    }

    @Test
    void rss_provider_returns_items_from_a_preset_feed() {
        RssNewsProvider provider = new RssNewsProvider(fetcher, parser, properties);
        TopicQuery query = new TopicQuery("it", "technology", List.of(baseUrl + "/feed"), true);

        List<NewsItem> items = provider.fetch(query, SINCE, 10);

        assertThat(items).hasSize(3);
        assertThat(items).isSortedAccordingTo(
            (left, right) -> right.publishedAt().compareTo(left.publishedAt()));
        // The undated entry sorts first because it falls back to "now".
        assertThat(items).extracting(NewsItem::title)
            .containsExactly("Entry without a date",
                "Chipmaker unveils a 2nm process",
                "Open source database hits version 20");
    }

    @Test
    void rss_provider_supports_only_topics_that_have_feeds() {
        RssNewsProvider provider = new RssNewsProvider(fetcher, parser, properties);

        assertThat(provider.supports(new TopicQuery("it", "q", List.of("https://f"), true)))
            .isTrue();
        assertThat(provider.supports(new TopicQuery("biotech", "biotech", List.of(), false)))
            .isFalse();
    }

    @Test
    void one_dead_feed_does_not_sink_the_others() {
        RssNewsProvider provider = new RssNewsProvider(fetcher, parser, properties);
        TopicQuery query = new TopicQuery("science", "science",
            List.of(baseUrl + "/broken", baseUrl + "/good"), true);

        List<NewsItem> items = provider.fetch(query, SINCE, 10);

        assertThat(items).singleElement()
            .extracting(NewsItem::title)
            .isEqualTo("Telescope spots an unusual exoplanet");
    }

    @Test
    void a_timeout_yields_nothing_instead_of_an_exception() {
        assertThat(fetcher.getBytes(baseUrl + "/slow")).isEmpty();
    }

    @Test
    void a_5xx_feed_yields_nothing_instead_of_an_exception() {
        assertThat(fetcher.getBytes(baseUrl + "/broken")).isEmpty();
    }

    @Test
    void an_article_page_is_fetched_as_bytes() {
        assertThat(fetcher.getPage(baseUrl + "/page"))
            .get()
            .asInstanceOf(InstanceOfAssertFactories.BYTE_ARRAY)
            .asString(UTF_8)
            .contains("The plant opened.");
    }

    @Test
    void an_oversized_article_page_is_not_buffered() {
        assertThat(fetcher.getPage(baseUrl + "/huge"))
            .as("the response is read, its body is dropped")
            .contains(new byte[0]);
    }

    @Test
    void a_failing_article_page_is_not_retried() {
        assertThat(fetcher.getPage(baseUrl + "/page-error")).isEmpty();
        assertThat(pageErrorHits).hasValue(1);
    }

    @Test
    void a_malformed_article_url_yields_nothing_instead_of_an_exception() {
        assertThat(fetcher.getPage("not a url")).isEmpty();
    }

    @Test
    void malformed_xml_yields_no_items() {
        RssNewsProvider provider = new RssNewsProvider(fetcher, parser, properties);

        List<NewsItem> items = provider.fetch(
            new TopicQuery("it", "q", List.of(baseUrl + "/malformed"), true), SINCE, 10);

        assertThat(items).isEmpty();
    }

    @Test
    void google_news_provider_serves_a_topic_with_no_preset() {
        GoogleNewsRssProvider provider = new GoogleNewsRssProvider(
            fetcherResolvingTo("https://bioworld.example/gene"), parser, decoder, properties);
        TopicQuery query = new TopicQuery("biotech", "biotech", List.of(), false);

        assertThat(provider.supports(query)).isTrue();
        List<NewsItem> items = provider.fetch(query, SINCE, 10);

        assertThat(items).singleElement().satisfies(item -> {
            // The " - BioWorld" tail becomes the source name, and the redirect
            // stub is replaced by the article it points at.
            assertThat(item.title()).isEqualTo("Gene therapy trial reports positive results");
            assertThat(item.sourceName()).isEqualTo("BioWorld");
            assertThat(item.url()).isEqualTo("https://bioworld.example/gene");
        });
        verify(decoder).decode(GOOGLE_STUB);
    }

    @Test
    void a_decoded_stub_wins_over_following_the_redirect() {
        when(decoder.decode(GOOGLE_STUB))
            .thenReturn(Optional.of("https://www.fool.com/investing/story"));
        GoogleNewsRssProvider provider = new GoogleNewsRssProvider(
            fetcherResolvingTo("https://news.google.com/rss/articles/unresolved"), parser,
            decoder, properties);

        List<NewsItem> items = provider.fetch(
            new TopicQuery("biotech", "biotech", List.of(), false), SINCE, 10);

        assertThat(items).singleElement().extracting(NewsItem::url)
            .isEqualTo("https://www.fool.com/investing/story");
    }

    @Test
    void the_same_stub_is_decoded_only_once() {
        when(decoder.decode(GOOGLE_STUB))
            .thenReturn(Optional.of("https://www.fool.com/investing/story"));
        GoogleNewsRssProvider provider = new GoogleNewsRssProvider(fetcher, parser, decoder,
            properties);
        TopicQuery query = new TopicQuery("biotech", "biotech", List.of(), false);

        provider.fetch(query, SINCE, 10);
        provider.fetch(query, SINCE, 10);

        verify(decoder, times(1)).decode(GOOGLE_STUB);
    }

    @Test
    void a_stub_that_could_not_be_resolved_is_not_cached() {
        GoogleNewsRssProvider provider = new GoogleNewsRssProvider(
            fetcherResolvingTo(null), parser, decoder, properties);
        TopicQuery query = new TopicQuery("biotech", "biotech", List.of(), false);

        provider.fetch(query, SINCE, 10);
        provider.fetch(query, SINCE, 10);

        // One bad afternoon at Google must not pin the link to a stub for the
        // rest of the process.
        verify(decoder, times(2)).decode(GOOGLE_STUB);
    }

    @Test
    void the_decoder_can_be_switched_off() {
        properties.setGoogleNewsDecodeLinks(false);
        GoogleNewsRssProvider provider = new GoogleNewsRssProvider(
            fetcherResolvingTo("https://bioworld.example/gene"), parser, decoder, properties);

        List<NewsItem> items = provider.fetch(
            new TopicQuery("biotech", "biotech", List.of(), false), SINCE, 10);

        assertThat(items).singleElement().extracting(NewsItem::url)
            .isEqualTo("https://bioworld.example/gene");
        verifyNoInteractions(decoder);
    }

    @Test
    void a_form_post_hands_back_what_the_server_answered() {
        assertThat(fetcher.postForm(baseUrl + "/rpc", "f.req=%5B%5D"))
            .contains("f.req=%5B%5D");
    }

    @Test
    void a_failing_form_post_yields_nothing_instead_of_an_exception() {
        assertThat(fetcher.postForm(baseUrl + "/rpc-error", "f.req=%5B%5D")).isEmpty();
    }

    @Test
    void a_google_news_item_carries_no_description_to_summarise() {
        GoogleNewsRssProvider provider = new GoogleNewsRssProvider(
            fetcherResolvingTo("https://bioworld.example/gene"), parser, decoder, properties);

        List<NewsItem> items = provider.fetch(
            new TopicQuery("biotech", "biotech", List.of(), false), SINCE, 10);

        // The feed description is the headline and the source in an <a>: nothing
        // to summarise, and printing it back would echo the title.
        assertThat(items).singleElement().extracting(NewsItem::rawDescription)
            .isEqualTo("");
    }

    @Test
    void google_news_search_url_carries_the_english_locale() {
        GoogleNewsRssProvider provider = new GoogleNewsRssProvider(fetcher, parser, decoder,
            properties);

        assertThat(provider.searchUrl("ai safety"))
            .contains("q=ai+safety")
            .contains("hl=en-US")
            .contains("ceid=US:en");
    }

    @Test
    void an_unresolvable_google_link_is_sent_as_is_rather_than_failing() {
        GoogleNewsRssProvider provider = new GoogleNewsRssProvider(
            fetcherResolvingTo(null), parser, decoder, properties);

        List<NewsItem> items = provider.fetch(
            new TopicQuery("biotech", "biotech", List.of(), false), SINCE, 10);

        assertThat(items).singleElement().extracting(NewsItem::url).isEqualTo(GOOGLE_STUB);
    }
}
