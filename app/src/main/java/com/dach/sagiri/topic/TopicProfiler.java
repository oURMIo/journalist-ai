package com.dach.sagiri.topic;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import com.dach.sagiri.config.BotProperties;
import com.dach.sagiri.summary.LlmGateway;
import com.dach.sagiri.summary.Summarizer;

/**
 * Reads a topic slug the way a reader would and says what it is about.
 * <p>
 * A slug is one or two words, and a search engine answers those words: a topic
 * named {@code news-java} is served volcano news because "java" is also an
 * island. One model call per topic turns the name into the subject behind it and
 * into a query that finds that subject — which is the only fix that works at the
 * source, before anything is fetched. Filtering afterwards can only remove the
 * wrong news, it cannot bring the right news in.
 * <p>
 * The call is optional in every sense: no model, a failure, an answer in the
 * wrong shape — the topic keeps working on its slug exactly as before.
 */
@Component
public class TopicProfiler {

    private static final Logger logger = LoggerFactory.getLogger(TopicProfiler.class);

    static final String DOMAIN_LABEL = "domain";
    static final String QUERY_LABEL = "query";
    static final String EXCLUDE_LABEL = "exclude";

    /** Google News reads a long query as a long list of ORs; keep it a query. */
    static final int MAX_QUERY_LENGTH = 200;
    static final int MAX_DOMAIN_LENGTH = 200;

    /** Enough to name the other meanings of a word, short enough to stay a query. */
    static final int MAX_EXCLUDED_TERMS = 4;

    /**
     * A backend that just failed will fail for the next topic of the cycle too,
     * and a wedged Ollama answers a timeout, not an error: without this, ten
     * un-profiled topics cost ten full timeouts before a single feed is fetched,
     * every cycle, and a /now blocks every other command while it waits.
     */
    static final Duration FAILURE_COOLDOWN = Duration.ofMinutes(15);

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern LABEL_NOISE = Pattern.compile("^[^\\p{L}]+");
    /** What a bolded label leaves behind: "**DOMAIN:** the language" -> "the language". */
    private static final Pattern VALUE_NOISE = Pattern.compile("^[*:\\-\\s]+");
    private static final Pattern QUERY_JUNK = Pattern.compile("[<>\\[\\]{}|\\\\`]");
    private static final Pattern EXCLUDE_JUNK = Pattern.compile("[^\\p{L}\\p{N} ]+");

    /** Null when {@code SUMMARIZER=extractive}: there is no model to ask. */
    private final LlmGateway gateway;
    private final boolean enabled;
    private final Duration failureCooldown;

    /** Shared by every topic: the backend is one process, and so is its outage. */
    private volatile Instant askAgainAfter = Instant.EPOCH;

    @Autowired
    public TopicProfiler(@Qualifier("summarizer") Summarizer summarizer,
        BotProperties properties) {
        this(summarizer instanceof LlmGateway llm ? llm : null, properties.isTopicProfile());
    }

    TopicProfiler(LlmGateway gateway, boolean enabled) {
        this(gateway, enabled, FAILURE_COOLDOWN);
    }

    TopicProfiler(LlmGateway gateway, boolean enabled, Duration failureCooldown) {
        this.gateway = gateway;
        this.enabled = enabled;
        this.failureCooldown = failureCooldown;
    }

    public boolean available() {
        return enabled && gateway != null;
    }

    /** @return the subject behind the slug, or empty when the model did not deliver one */
    public Optional<TopicProfile> derive(String slug) {
        if (!available() || StringUtils.isBlank(slug) || Instant.now().isBefore(askAgainAfter)) {
            return Optional.empty();
        }
        String answer = gateway.ask(prompt(slug), "topic " + slug);
        Optional<TopicProfile> profile = parse(answer);
        if (profile.isPresent()) {
            logger.info("Topic '{}' means '{}', searching for: {}", slug,
                profile.get().domain(), profile.get().query());
            return profile;
        }
        if (StringUtils.isBlank(answer)) {
            // Only an empty answer means the backend itself is gone: `ask` returns ""
            // for a timeout, a non-2xx, or a spent budget. An answer in the wrong
            // shape is one slug the model handled badly, and must not stop the bot
            // from reading the other topics of this cycle.
            askAgainAfter = Instant.now().plus(failureCooldown);
            logger.info("The model is not answering, topic '{}' keeps the slug as its search "
                        + "query and nothing is asked again before {}", slug, askAgainAfter);
        } else {
            logger.info("The model did not describe topic '{}' in the expected shape, keeping "
                        + "the slug as the search query", slug);
        }
        return profile;
    }

    static String prompt(String slug) {
        return """
            A news bot collects English news for a reader following the topic "%s".
            A topic name can mean several unrelated things - a language and an island, \
            a company and a river. Decide which one a reader of a news feed named "%s" \
            almost certainly means, then write a news search query that finds it.
            Answer in exactly three lines and nothing else:
            DOMAIN: the subject and its field, one short sentence
            QUERY: English search terms, quoted phrases and OR allowed, at most 12 words
            EXCLUDE: the other meanings of the name, comma separated, or none\
            """.formatted(slug, slug);
    }

    /**
     * Reads the three labelled lines out of whatever the model produced. Small
     * models add a preamble, bold the labels or answer in prose; a missing domain
     * or query is no profile at all, because a half-derived topic would search for
     * something nobody asked for.
     */
    static Optional<TopicProfile> parse(String answer) {
        String domain = "";
        String query = "";
        List<String> excluded = List.of();

        for (String line : StringUtils.defaultString(answer).lines().toList()) {
            String clean = LABEL_NOISE.matcher(line.strip()).replaceFirst("");
            int colon = clean.indexOf(':');
            if (colon < 0) {
                continue;
            }
            String label = clean.substring(0, colon).strip().toLowerCase(Locale.ROOT);
            String value = VALUE_NOISE.matcher(clean.substring(colon + 1)).replaceFirst("");
            switch (label) {
                case DOMAIN_LABEL -> domain = sentence(value);
                case QUERY_LABEL -> query = sanitizeQuery(value);
                case EXCLUDE_LABEL -> excluded = excludedTerms(value);
                default -> { }
            }
        }
        if (domain.isEmpty() || query.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new TopicProfile(domain, withExclusions(query, excluded)));
    }

    private static String sentence(String value) {
        return StringUtils.abbreviate(
            unwrap(WHITESPACE.matcher(value).replaceAll(" ").strip()), MAX_DOMAIN_LENGTH);
    }

    /** The one shape a search query may have, whoever wrote it — the model or a reader. */
    public static String sanitizeQuery(String value) {
        String query = QUERY_JUNK.matcher(value).replaceAll(" ");
        return trimToWord(WHITESPACE.matcher(query).replaceAll(" ").strip(), MAX_QUERY_LENGTH);
    }

    private static List<String> excludedTerms(String value) {
        if (StringUtils.containsIgnoreCase(value, "none")) {
            return List.of();
        }
        List<String> terms = new ArrayList<>();
        for (String part : value.split(",")) {
            String term = WHITESPACE.matcher(EXCLUDE_JUNK.matcher(part).replaceAll(" "))
                .replaceAll(" ").strip().toLowerCase(Locale.ROOT);
            if (!term.isEmpty() && !terms.contains(term) && terms.size() < MAX_EXCLUDED_TERMS) {
                terms.add(term);
            }
        }
        return terms;
    }

    /** Both providers pass the query through; Google News reads {@code -term} as "not this". */
    private static String withExclusions(String query, List<String> excluded) {
        StringBuilder full = new StringBuilder(query);
        for (String term : excluded) {
            String negated = term.contains(" ") ? " -\"" + term + "\"" : " -" + term;
            if (full.length() + negated.length() > MAX_QUERY_LENGTH) {
                break;
            }
            full.append(negated);
        }
        return full.toString();
    }

    /** Small models like to wrap the answer in quotes or asterisks. */
    private static String unwrap(String value) {
        String text = value;
        while (text.length() > 1 && (text.startsWith("\"") && text.endsWith("\"")
                                     || text.startsWith("*") && text.endsWith("*"))) {
            text = text.substring(1, text.length() - 1).strip();
        }
        return text;
    }

    /** A query cut mid-word searches for half a word, which matches nothing sensible. */
    private static String trimToWord(String value, int max) {
        if (value.length() <= max) {
            return value;
        }
        String cut = value.substring(0, max);
        int lastSpace = cut.lastIndexOf(' ');
        return lastSpace > 0 ? cut.substring(0, lastSpace) : "";
    }
}
