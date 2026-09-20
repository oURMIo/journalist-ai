package com.dach.sagiri.news;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.dach.sagiri.config.BotProperties;

/**
 * Outbound HTTP for feeds: bounded timeouts, an honest User-Agent and a couple
 * of retries on 5xx and timeouts. A failure is returned as an empty Optional —
 * one broken feed must never abort a polling cycle.
 */
@Component
public class HttpFetcher {

    private static final Logger logger = LoggerFactory.getLogger(HttpFetcher.class);

    /** An article page worth reading is far smaller; anything larger is not HTML. */
    private static final long MAX_PAGE_BYTES = 2L * 1024 * 1024;

    private final HttpClient client;
    private final BotProperties properties;

    public HttpFetcher(HttpClient newsHttpClient, BotProperties properties) {
        this.client = newsHttpClient;
        this.properties = properties;
    }

    /** Feeds are fetched as bytes so the XML declaration decides the encoding. */
    public Optional<byte[]> getBytes(String url) {
        int attempts = properties.getHttp().getRetries() + 1;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                HttpResponse<byte[]> response =
                    client.send(request(url).GET().build(), HttpResponse.BodyHandlers.ofByteArray());

                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return Optional.of(response.body());
                }
                if (response.statusCode() < 500) {
                    logger.warn("Feed {} returned {}, giving up", url, response.statusCode());
                    return Optional.empty();
                }
                logger.warn("Feed {} returned {} (attempt {}/{})", url, response.statusCode(),
                    attempt, attempts);
            } catch (IOException e) {
                logger.warn("Feed {} failed (attempt {}/{}): {}", url, attempt, attempts,
                    e.toString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            } catch (IllegalArgumentException e) {
                logger.warn("Malformed feed URL {}: {}", url, e.toString());
                return Optional.empty();
            }
            backoff(attempt);
        }
        return Optional.empty();
    }

    /**
     * One shot at an article page. No retry: a summary is a nice-to-have and the
     * polling job is waiting, so a slow or broken page is simply skipped. A feed
     * link can point at a PDF or a video, so a body that announces itself as
     * oversized is dropped rather than buffered.
     */
    public Optional<byte[]> getPage(String url) {
        try {
            HttpResponse<byte[]> response = client.send(
                request(url).setHeader("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8")
                    .GET().build(),
                HttpFetcher::cappedBody);

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return Optional.of(response.body());
            }
            logger.debug("Page {} returned {}", url, response.statusCode());
            return Optional.empty();
        } catch (IOException | IllegalArgumentException e) {
            logger.debug("Could not read {}: {}", url, e.toString());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /**
     * Follows redirects and reports where a link actually lands. Google News
     * serves redirect stubs; if one refuses to resolve, the original URL is
     * returned rather than failing the item.
     */
    public String resolveFinalUrl(String url) {
        try {
            HttpResponse<Void> response = client.send(
                request(url).method("HEAD", HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.discarding());
            URI finalUri = response.uri();
            return finalUri == null ? url : finalUri.toString();
        } catch (IOException | IllegalArgumentException e) {
            logger.debug("Could not resolve {}: {}", url, e.toString());
            return url;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return url;
        }
    }

    /**
     * One form POST, no retry. Used to ask Google News where a link really goes;
     * a failure leaves the caller with what it already had.
     */
    public Optional<String> postForm(String url, String body) {
        try {
            HttpResponse<String> response = client.send(
                request(url)
                    .setHeader("Accept", "*/*")
                    .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return Optional.of(response.body());
            }
            logger.debug("POST {} returned {}", url, response.statusCode());
            return Optional.empty();
        } catch (IOException | IllegalArgumentException e) {
            logger.debug("Could not post to {}: {}", url, e.toString());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private static HttpResponse.BodySubscriber<byte[]> cappedBody(HttpResponse.ResponseInfo info) {
        long declared = info.headers().firstValueAsLong("Content-Length").orElse(-1);
        return declared > MAX_PAGE_BYTES
            ? HttpResponse.BodySubscribers.replacing(new byte[0])
            : HttpResponse.BodySubscribers.ofByteArray();
    }

    private HttpRequest.Builder request(String url) {
        return HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(properties.getHttp().getTimeoutSeconds()))
            .header("User-Agent", properties.getHttp().getUserAgent())
            .header("Accept", "application/rss+xml, application/atom+xml, application/xml, "
                              + "text/xml;q=0.9, */*;q=0.8");
    }

    private static void backoff(int attempt) {
        try {
            Thread.sleep(Math.min(4_000L, 300L * (1L << (attempt - 1))));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
