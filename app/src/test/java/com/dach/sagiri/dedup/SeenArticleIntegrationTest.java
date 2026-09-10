package com.dach.sagiri.dedup;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.dach.sagiri.news.model.NewsItem;
import com.dach.sagiri.topic.TopicEntity;
import com.dach.sagiri.topic.TopicRepository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies the Flyway schema and the de-duplication guarantees against a real
 * PostgreSQL, including the partial unique index that no in-memory database can
 * reproduce faithfully.
 * <p>
 * Backed by an embedded PostgreSQL, so no Docker is needed. Off by default
 * because the first run downloads the server binary; enable with
 * {@code INTEGRATION_TESTS=true ./gradlew test}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({SeenArticleService.class, UrlCanonicalizer.class})
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@EnabledIfEnvironmentVariable(named = "INTEGRATION_TESTS", matches = "true")
class SeenArticleIntegrationTest {

    private static EmbeddedPostgres postgres;

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) throws IOException {
        postgres = EmbeddedPostgres.builder().start();
        registry.add("spring.datasource.url", () -> postgres.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
    }

    @Autowired
    private TopicRepository topics;

    @Autowired
    private SeenArticleService seenArticles;

    private TopicEntity newTopic(long threadId, String slug, boolean active) {
        TopicEntity topic = new TopicEntity();
        topic.setChatId(-100L);
        topic.setThreadId(threadId);
        topic.setRawName("news-" + slug);
        topic.setSlug(slug);
        topic.setQuery(slug);
        topic.setActive(active);
        return topics.saveAndFlush(topic);
    }

    private static NewsItem item(String title, String url) {
        return new NewsItem(url, title, url, "Example", Instant.now(), "", null);
    }

    @Test
    void the_same_thread_cannot_be_registered_twice() {
        newTopic(1L, "it", true);

        assertThatThrownBy(() -> newTopic(1L, "science", true))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void a_subject_can_have_only_one_active_topic() {
        newTopic(2L, "space", true);

        assertThatThrownBy(() -> newTopic(3L, "space", true))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void an_inactive_topic_does_not_block_the_subject() {
        newTopic(4L, "ai", false);

        assertThat(newTopic(5L, "ai", true).getId()).isNotNull();
    }

    @Test
    void a_second_run_over_the_same_articles_sends_nothing() {
        TopicEntity topic = newTopic(6L, "biotech", true);
        List<NewsItem> batch = List.of(
            item("Gene therapy works", "https://example.com/a"),
            item("New enzyme found", "https://example.com/b"));

        List<NewsItem> firstRun = seenArticles.filterUnseen(topic.getId(), batch);
        firstRun.forEach(article -> seenArticles.markSent(topic.getId(), article));

        assertThat(firstRun).hasSize(2);
        assertThat(seenArticles.filterUnseen(topic.getId(), batch)).isEmpty();
    }

    @Test
    void the_same_article_under_a_tracking_url_is_recognised() {
        TopicEntity topic = newTopic(7L, "security", true);
        NewsItem original = item("Breach disclosed", "https://example.com/breach");
        seenArticles.markSent(topic.getId(), original);

        NewsItem reshared = item("Breach disclosed",
            "https://www.example.com/breach?utm_source=rss");

        assertThat(seenArticles.filterUnseen(topic.getId(), List.of(reshared))).isEmpty();
    }

    @Test
    void the_same_story_from_another_aggregator_is_recognised_by_its_title() {
        TopicEntity topic = newTopic(8L, "space", true);
        seenArticles.markSent(topic.getId(), item("Rocket lands", "https://a.example/rocket"));

        NewsItem elsewhere = item("Rocket lands!", "https://b.example/different-url");

        assertThat(seenArticles.filterUnseen(topic.getId(), List.of(elsewhere))).isEmpty();
    }

    @Test
    void a_different_topic_keeps_its_own_history() {
        TopicEntity first = newTopic(9L, "it", true);
        TopicEntity second = newTopic(10L, "science", true);
        NewsItem article = item("Shared story", "https://example.com/shared");
        seenArticles.markSent(first.getId(), article);

        assertThat(seenArticles.filterUnseen(second.getId(), List.of(article))).hasSize(1);
    }

    @Test
    void retention_removes_only_what_is_older_than_the_threshold() {
        TopicEntity topic = newTopic(11L, "ai", true);
        seenArticles.markSent(topic.getId(), item("Recent", "https://example.com/recent"));

        int removed = seenArticles.purgeOlderThan(Instant.now().minusSeconds(3600));

        assertThat(removed).isZero();
        assertThat(seenArticles.filterUnseen(topic.getId(),
            List.of(item("Recent", "https://example.com/recent")))).isEmpty();
    }
}
