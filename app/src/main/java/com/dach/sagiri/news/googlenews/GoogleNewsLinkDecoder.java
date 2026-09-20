package com.dach.sagiri.news.googlenews;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import com.dach.sagiri.news.HttpFetcher;

/**
 * Turns a {@code news.google.com/rss/articles/...} stub into the publisher's own
 * URL.
 * <p>
 * Following the redirect is not enough any more: the stub answers 200 with an
 * application shell that carries no link. What it does carry is the pair Google
 * News itself posts back to open the article, so the stub page is read for its
 * {@code data-n-a-ts}/{@code data-n-a-sg} and those are sent to the same
 * endpoint the site uses. Without the real URL there is no article to summarise
 * and no link worth publishing — but this is an undocumented endpoint, so every
 * failure simply leaves the stub in place.
 */
@Component
public class GoogleNewsLinkDecoder {

    private static final Logger logger = LoggerFactory.getLogger(GoogleNewsLinkDecoder.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String RPC_ID = "Fbv4je";
    private static final String RPC_URL =
        "https://news.google.com/_/DotsSplashUi/data/batchexecute?rpcids=" + RPC_ID;

    private static final Pattern ARTICLE_ID =
        Pattern.compile("news\\.google\\.com/(?:rss/)?articles/([^/?#]+)");

    /** Both values belong to one signed link; two elements would not match. */
    private static final String SIGNED_LINK = "[data-n-a-ts][data-n-a-sg]";

    private static final int MAX_PARSED_BYTES = 2 * 1024 * 1024;

    /** The shape Google News posts; only the last three values are ours. */
    private static final String REQUEST = "[\"garturlreq\",[[\"X\",\"X\",[\"X\",\"X\"],null,null,"
                                          + "1,1,\"US:en\",null,1,null,null,null,null,null,0,1],"
                                          + "\"X\",\"X\",1,[1,1,1],1,1,null,0,0,null,0],"
                                          + "\"%s\",%s,\"%s\"]";

    private final HttpFetcher fetcher;

    public GoogleNewsLinkDecoder(HttpFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** @return the publisher URL, or empty when the stub could not be decoded */
    public Optional<String> decode(String stubUrl) {
        Optional<String> articleId = match(ARTICLE_ID, stubUrl);
        if (articleId.isEmpty()) {
            return Optional.empty();
        }

        Optional<Element> link = fetcher.getPage(stubUrl)
            .flatMap(page -> signedLink(page, stubUrl));
        if (link.isEmpty()) {
            logger.debug("Google News stub {} carries no signature to decode", stubUrl);
            return Optional.empty();
        }

        String body = form(REQUEST.formatted(articleId.get(), link.get().attr("data-n-a-ts"),
            link.get().attr("data-n-a-sg")));
        return fetcher.postForm(RPC_URL, body)
            .flatMap(GoogleNewsLinkDecoder::readUrl)
            .filter(url -> !url.contains("news.google.com"));
    }

    /** The stub page is an application shell, so what Jsoup is given is bounded. */
    private static Optional<Element> signedLink(byte[] page, String stubUrl) {
        try (ByteArrayInputStream stream = new ByteArrayInputStream(page, 0,
            Math.min(page.length, MAX_PARSED_BYTES))) {

            return Optional.ofNullable(Jsoup.parse(stream, null, stubUrl).selectFirst(SIGNED_LINK));
        } catch (IOException | RuntimeException e) {
            logger.debug("Could not read the Google News stub {}: {}", stubUrl, e.toString());
            return Optional.empty();
        }
    }

    private static String form(String request) {
        ArrayNode call = JSON.createArrayNode();
        call.add(RPC_ID);
        call.add(request);
        call.addNull();
        call.add("generic");
        ArrayNode envelope = JSON.createArrayNode();
        envelope.add(JSON.createArrayNode().add(call));
        return "f.req=" + URLEncoder.encode(envelope.toString(), StandardCharsets.UTF_8);
    }

    /** The answer opens with an anti-hijacking prefix and nests JSON inside JSON. */
    private static Optional<String> readUrl(String payload) {
        int start = payload.indexOf('[');
        if (start < 0) {
            return Optional.empty();
        }
        try {
            for (JsonNode entry : JSON.readTree(payload.substring(start))) {
                if (!RPC_ID.equals(entry.path(1).asText())) {
                    continue;
                }
                String url = JSON.readTree(entry.path(2).asText("[]")).path(1).asText("");
                if (StringUtils.startsWith(url, "http")) {
                    return Optional.of(url);
                }
            }
        } catch (Exception e) {
            logger.debug("Could not read the Google News answer: {}", e.toString());
        }
        return Optional.empty();
    }

    private static Optional<String> match(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(StringUtils.defaultString(text));
        return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
    }
}
