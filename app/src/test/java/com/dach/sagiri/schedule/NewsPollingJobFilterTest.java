package com.dach.sagiri.schedule;

import com.google.gson.Gson;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.dach.sagiri.config.BotProperties;
import com.dach.sagiri.dedup.SeenArticleService;
import com.dach.sagiri.filter.ArticleFilter;
import com.dach.sagiri.filter.FilterDecision;
import com.dach.sagiri.filter.TopicFilters;
import com.dach.sagiri.news.NewsAggregator;
import com.dach.sagiri.news.model.NewsItem;
import com.dach.sagiri.news.model.TopicQuery;
import com.dach.sagiri.summary.Summarizer;
import com.dach.sagiri.telegram.MessageFormatter;
import com.dach.sagiri.telegram.OutgoingMessage;
import com.dach.sagiri.telegram.TelegramSender;
import com.dach.sagiri.topic.TopicEntity;
import com.dach.sagiri.topic.TopicProfileService;
import com.dach.sagiri.topic.TopicRegistryService;
import com.pengrad.telegrambot.response.SendResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** What the topic filters change in a polling cycle. */
class NewsPollingJobFilterTest {

    private static final Gson GSON = new Gson();

    private final TopicRegistryService registry = mock(TopicRegistryService.class);
    private final TopicProfileService profiles = mock(TopicProfileService.class);
    private final NewsAggregator aggregator = mock(NewsAggregator.class);
    private final SeenArticleService seenArticles = mock(SeenArticleService.class);
    private final ArticleFilter articleFilter = mock(ArticleFilter.class);
    private final Summarizer summarizer = mock(Summarizer.class);
    private final MessageFormatter formatter = mock(MessageFormatter.class);
    private final TelegramSender sender = mock(TelegramSender.class);
    private final BotProperties properties = new BotProperties();

    private final NewsPollingJob job = new NewsPollingJob(registry, profiles, aggregator,
        seenArticles, articleFilter, summarizer, formatter, sender, new PollingState(), properties);

    private final TopicEntity topic = topic();
    private final NewsItem wanted = item("Kubernetes 1.33 is out", "https://example.com/k8s");
    private final NewsItem unwanted = item("Go 1.24 is out", "https://example.com/go");

    private static TopicEntity topic() {
        TopicEntity topic = new TopicEntity();
        topic.setId(7L);
        topic.setChatId(-1001L);
        topic.setThreadId(42L);
        topic.setSlug("it");
        return topic;
    }

    private static NewsItem item(String title, String url) {
        return item(title, url, Instant.now());
    }

    private static NewsItem item(String title, String url, Instant publishedAt) {
        return new NewsItem(url, title, url, "Example", publishedAt, "", null);
    }

    private void feed(NewsItem... items) {
        when(profiles.queryFor(topic)).thenReturn(new TopicQuery("it", "it", List.of(), false));
        when(articleFilter.rulesOf(topic)).thenReturn(TopicFilters.none("it", ""));
        when(aggregator.collect(any(), any(), anyInt())).thenReturn(List.of(items));
        when(seenArticles.filterUnseen(eq(7L), any())).thenReturn(List.of(items));
        when(formatter.format(any(), any())).thenReturn("<b>news</b>");
        when(sender.sendBlocking(any(OutgoingMessage.class))).thenReturn(GSON.fromJson(
            """
            {"ok":true,"result":{"message_id":1,"date":0,"chat":{"id":-1001}}}""",
            SendResponse.class));
    }

    @Test
    void a_blocked_article_is_not_published() {
        feed(unwanted, wanted);
        when(articleFilter.decide(any(), eq(unwanted)))
            .thenReturn(FilterDecision.block("keyword: go"));
        when(articleFilter.decide(any(), eq(wanted))).thenReturn(FilterDecision.allow());

        assertThat(job.pollTopic(topic)).isEqualTo(1);

        verify(seenArticles).markSent(7L, wanted);
        verify(sender).sendBlocking(any(OutgoingMessage.class));
        verify(summarizer, never()).summarize(unwanted);
    }

    @Test
    void a_keyword_rejection_is_not_stored_but_a_model_verdict_is() {
        feed(unwanted, wanted);
        when(articleFilter.decide(any(), eq(unwanted)))
            .thenReturn(FilterDecision.block("keyword: go"));
        when(articleFilter.decide(any(), eq(wanted)))
            .thenReturn(FilterDecision.blockAndRemember("ai"));

        assertThat(job.pollTopic(topic)).isZero();

        verify(seenArticles, never()).markFiltered(7L, unwanted, "keyword: go");
        verify(seenArticles).markFiltered(7L, wanted, "ai");
    }

    @Test
    void a_failing_telegram_stops_the_cycle_instead_of_burning_the_whole_batch() {
        properties.setMaxItemsPerTopic(10);
        NewsItem[] items = new NewsItem[10];
        for (int i = 0; i < items.length; i++) {
            items[i] = item("Article " + i, "https://example.com/" + i);
        }
        feed(items);
        when(articleFilter.decide(any(), any())).thenReturn(FilterDecision.allow());
        when(sender.sendBlocking(any(OutgoingMessage.class)))
            .thenThrow(new IllegalStateException("telegram is down"));

        assertThat(job.pollTopic(topic)).isZero();

        verify(sender, times(3)).sendBlocking(any(OutgoingMessage.class));
        verify(seenArticles, never()).markSent(anyLong(), any());
    }

    @Test
    void a_filtered_article_does_not_use_up_the_per_cycle_budget() {
        properties.setMaxItemsPerTopic(1);
        feed(unwanted, wanted);
        when(articleFilter.decide(any(), eq(unwanted)))
            .thenReturn(FilterDecision.blockAndRemember("ai"));
        when(articleFilter.decide(any(), eq(wanted))).thenReturn(FilterDecision.allow());

        assertThat(job.pollTopic(topic)).isEqualTo(1);

        verify(seenArticles).markSent(7L, wanted);
        verify(registry).markPolled(eq(7L), any(Instant.class));
    }

    @Test
    void nothing_is_evaluated_once_the_batch_is_full() {
        properties.setMaxItemsPerTopic(1);
        feed(wanted, unwanted);
        when(articleFilter.decide(any(), eq(wanted))).thenReturn(FilterDecision.allow());

        assertThat(job.pollTopic(topic)).isEqualTo(1);

        verify(articleFilter, never()).decide(any(), eq(unwanted));
        verify(seenArticles, never()).markFiltered(anyLong(), any(), any());
    }

    @Test
    void a_postponed_article_keeps_the_look_back_where_it_is() {
        Instant published = Instant.now().minusSeconds(600);
        NewsItem postponed = item("A database release", "https://example.com/db", published);
        feed(postponed);
        when(articleFilter.decide(any(), eq(postponed)))
            .thenReturn(FilterDecision.postpone("no-required-keyword"));

        assertThat(job.pollTopic(topic)).isZero();

        ArgumentCaptor<Instant> polled = ArgumentCaptor.forClass(Instant.class);
        verify(registry).markPolled(eq(7L), polled.capture());
        assertThat(polled.getValue())
            .as("the next cycle must still see the article it could not judge")
            .isBefore(published);
        verify(seenArticles, never()).markFiltered(anyLong(), any(), any());
    }

    @Test
    void a_failed_send_keeps_the_look_back_where_it_is() {
        Instant published = Instant.now().minusSeconds(600);
        NewsItem lost = item("Kubernetes 1.33 is out", "https://example.com/k8s", published);
        feed(lost);
        when(articleFilter.decide(any(), any())).thenReturn(FilterDecision.allow());
        when(sender.sendBlocking(any(OutgoingMessage.class)))
            .thenThrow(new IllegalStateException("telegram is down"));

        assertThat(job.pollTopic(topic)).isZero();

        ArgumentCaptor<Instant> polled = ArgumentCaptor.forClass(Instant.class);
        verify(registry).markPolled(eq(7L), polled.capture());
        assertThat(polled.getValue()).isBefore(published);
    }

    @Test
    void a_finished_cycle_moves_the_look_back_to_now() {
        Instant published = Instant.now().minusSeconds(600);
        feed(item("Kubernetes 1.33 is out", "https://example.com/k8s", published));
        when(articleFilter.decide(any(), any())).thenReturn(FilterDecision.allow());

        assertThat(job.pollTopic(topic)).isEqualTo(1);

        ArgumentCaptor<Instant> polled = ArgumentCaptor.forClass(Instant.class);
        verify(registry).markPolled(eq(7L), polled.capture());
        assertThat(polled.getValue()).isAfter(published);
    }

    @Test
    void the_rules_are_read_once_per_cycle_not_once_per_article() {
        feed(wanted, unwanted);
        when(articleFilter.decide(any(), any())).thenReturn(FilterDecision.allow());

        job.pollTopic(topic);

        verify(articleFilter).rulesOf(topic);
    }
}
