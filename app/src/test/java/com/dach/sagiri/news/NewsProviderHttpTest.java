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
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.dach.sagiri.config.BotProperties;
import com.dach.sagiri.news.googlenews.GoogleNewsRssProvider;
import com.dach.sagiri.news.model.NewsItem;
import com.dach.sagiri.news.model.TopicQuery;
import com.dach.sagiri.news.rss.FeedParser;
import com.dach.sagiri.news.rss.RssNewsProvider;

import static org.assertj.core.api.Assertions.assertThat;

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

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/feed", feedHandler("techcrunch-sample.xml"));
        server.createContext("/good", feedHandler("atom-sample.xml"));
        server.createContext("/malformed", feedHandler("broken.xml"));
        server.createContext("/rss/search", feedHandler("google-news-sample.xml"));
        server.createContext("/broken", exchange -> respond(exchange, 500, new byte[0]));
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
    void malformed_xml_yields_no_items() {
        RssNewsProvider provider = new RssNewsProvider(fetcher, parser, properties);

        List<NewsItem> items = provider.fetch(
            new TopicQuery("it", "q", List.of(baseUrl + "/malformed"), true), SINCE, 10);

        assertThat(items).isEmpty();
    }

    @Test
    void google_news_provider_serves_a_topic_with_no_preset() {
        GoogleNewsRssProvider provider = new GoogleNewsRssProvider(
            fetcherResolvingTo("https://bioworld.example/gene"), parser, properties);
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
    }

    @Test
    void google_news_search_url_carries_the_english_locale() {
        GoogleNewsRssProvider provider = new GoogleNewsRssProvider(fetcher, parser, properties);

        assertThat(provider.searchUrl("ai safety"))
            .contains("q=ai+safety")
            .contains("hl=en-US")
            .contains("ceid=US:en");
    }

    @Test
    void an_unresolvable_google_link_is_sent_as_is_rather_than_failing() {
        GoogleNewsRssProvider provider = new GoogleNewsRssProvider(
            fetcherResolvingTo(null), parser, properties);

        List<NewsItem> items = provider.fetch(
            new TopicQuery("biotech", "biotech", List.of(), false), SINCE, 10);

        assertThat(items).singleElement().extracting(NewsItem::url).isEqualTo(GOOGLE_STUB);
    }
}
