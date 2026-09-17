package com.dach.sagiri.topic;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import com.dach.sagiri.summary.LlmGateway;

import static org.assertj.core.api.Assertions.assertThat;

/** What the bot makes of an ambiguous topic name. */
class TopicProfilerTest {

    private static final String JAVA_ANSWER = """
        DOMAIN: the Java programming language and the JVM ecosystem
        QUERY: "java programming" OR jvm OR jdk OR "spring boot"
        EXCLUDE: java island, volcano""";

    private final AtomicReference<String> prompt = new AtomicReference<>();
    private final AtomicReference<String> label = new AtomicReference<>();

    private TopicProfiler profiler(String answer) {
        return new TopicProfiler((asked, about) -> {
            prompt.set(asked);
            label.set(about);
            return answer;
        }, true);
    }

    @Test
    void the_subject_and_a_query_that_avoids_the_other_meanings() {
        Optional<TopicProfile> profile = profiler(JAVA_ANSWER).derive("java");

        assertThat(profile).isPresent();
        assertThat(profile.get().domain())
            .isEqualTo("the Java programming language and the JVM ecosystem");
        assertThat(profile.get().query())
            .startsWith("\"java programming\" OR jvm OR jdk OR \"spring boot\"")
            .as("Google News reads -term as 'not this'")
            .endsWith("-\"java island\" -volcano");
        assertThat(prompt.get()).contains("java");
        assertThat(label.get()).isEqualTo("topic java");
    }

    @Test
    void a_chatty_model_is_still_read() {
        Optional<TopicProfile> profile = profiler("""
            Sure! Here is the profile you asked for.
            **DOMAIN:** the Go programming language
            - QUERY: golang OR "go programming"
            EXCLUDE: none""").derive("go");

        assertThat(profile).isPresent();
        assertThat(profile.get().domain()).isEqualTo("the Go programming language");
        assertThat(profile.get().query())
            .as("'none' is not a term to exclude")
            .isEqualTo("golang OR \"go programming\"");
    }

    @Test
    void an_answer_without_a_query_is_no_profile_at_all() {
        assertThat(profiler("DOMAIN: something vague").derive("java")).isEmpty();
        assertThat(profiler("I am not sure what you mean.").derive("java")).isEmpty();
        assertThat(profiler("").derive("java")).isEmpty();
    }

    @Test
    void an_over_long_query_is_cut_at_a_word_boundary() {
        String query = ("alpha bravo charlie delta echo foxtrot golf hotel india juliett "
                        + "kilo lima mike november oscar papa quebec romeo sierra tango "
                        + "uniform victor whiskey xray yankee zulu").repeat(2);

        Optional<TopicProfile> profile =
            profiler("DOMAIN: radio alphabet\nQUERY: " + query).derive("alphabet");

        assertThat(profile).isPresent();
        assertThat(profile.get().query())
            .hasSizeLessThanOrEqualTo(TopicProfiler.MAX_QUERY_LENGTH)
            .doesNotEndWith(" ")
            .as("a query cut mid-word matches nothing")
            .satisfies(cut -> assertThat(query).startsWith(cut));
    }

    @Test
    void a_backend_that_just_failed_is_not_asked_again_for_every_other_topic() {
        AtomicInteger calls = new AtomicInteger();
        TopicProfiler profiler = new TopicProfiler((asked, about) -> {
            calls.incrementAndGet();
            return "";
        }, true, Duration.ofMinutes(15));

        assertThat(profiler.derive("java")).isEmpty();
        assertThat(profiler.derive("go")).isEmpty();
        assertThat(profiler.derive("rust")).isEmpty();

        assertThat(calls.get())
            .as("a wedged model answers a timeout, not an error, once per topic")
            .isEqualTo(1);
    }

    @Test
    void an_answer_in_the_wrong_shape_does_not_stop_the_other_topics_from_being_read() {
        AtomicInteger calls = new AtomicInteger();
        TopicProfiler profiler = new TopicProfiler((asked, about) -> {
            calls.incrementAndGet();
            return asked.contains("\"ai\"") ? "It depends on what you mean." : JAVA_ANSWER;
        }, true, Duration.ofMinutes(15));

        assertThat(profiler.derive("ai")).isEmpty();

        assertThat(profiler.derive("java"))
            .as("the model is up and talking, it just handled one slug badly")
            .isPresent();
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void once_the_cooldown_is_over_the_model_is_asked_again() {
        AtomicInteger calls = new AtomicInteger();
        TopicProfiler profiler = new TopicProfiler((asked, about) -> {
            calls.incrementAndGet();
            return calls.get() == 1 ? "" : JAVA_ANSWER;
        }, true, Duration.ZERO);

        assertThat(profiler.derive("java")).isEmpty();
        assertThat(profiler.derive("java")).isPresent();
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void without_a_model_nothing_is_derived_and_nothing_is_asked() {
        TopicProfiler disabled = new TopicProfiler(gateway(), false);
        TopicProfiler modelless = new TopicProfiler(null, true);

        assertThat(disabled.available()).isFalse();
        assertThat(disabled.derive("java")).isEmpty();
        assertThat(modelless.available()).isFalse();
        assertThat(modelless.derive("java")).isEmpty();
        assertThat(prompt.get()).as("the gateway was never called").isNull();
    }

    private LlmGateway gateway() {
        return (asked, about) -> {
            prompt.set(asked);
            return JAVA_ANSWER;
        };
    }
}
