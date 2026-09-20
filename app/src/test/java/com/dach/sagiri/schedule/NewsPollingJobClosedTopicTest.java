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

/**
 * A polling cycle spans minutes, so the open flag it started with can go stale.
 * Telegram accepts sends from a topic-managing bot into a closed topic, which is
 * why the job has to re-read the flag instead of trusting the send to fail.
 */
class NewsPollingJobClosedTopicTest {

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

    private final NewsItem first = item("Kubernetes 1.33 is out", Instant.now().minusSeconds(400));
    private final NewsItem second = item("Go 1.24 is out", Instant.now().minusSeconds(300));
    private final NewsItem third = item("Rust 1.85 is out", Instant.now().minusSeconds(200));
    private final NewsItem fourth = item("Java 25 is out", Instant.now().minusSeconds(100));

    private static TopicEntity topic() {
        TopicEntity topic = new TopicEntity();
        topic.setId(7L);
        topic.setChatId(-1001L);
        topic.setThreadId(42L);
        topic.setSlug("it");
        return topic;
    }

    private static NewsItem item(String title, Instant publishedAt) {
        String url = "https://example.com/" + title.hashCode();
        return new NewsItem(url, title, url, "Example", publishedAt, "", null);
    }

    private void feed(NewsItem... items) {
        when(profiles.queryFor(topic)).thenReturn(new TopicQuery("it", "it", List.of(), false));
        when(articleFilter.rulesOf(topic)).thenReturn(TopicFilters.none("it", ""));
        when(articleFilter.decide(any(), any())).thenReturn(FilterDecision.allow());
        when(aggregator.collect(any(), any(), anyInt())).thenReturn(List.of(items));
        when(seenArticles.filterUnseen(eq(7L), any())).thenReturn(List.of(items));
        when(formatter.format(any(), any())).thenReturn("<b>news</b>");
        when(sender.sendBlocking(any(OutgoingMessage.class))).thenReturn(GSON.fromJson(
            """
            {"ok":true,"result":{"message_id":1,"date":0,"chat":{"id":-1001}}}""",
            SendResponse.class));
    }

    @Test
    void a_topic_closed_before_the_cycle_reaches_it_is_not_even_fetched() {
        feed(first, second);
        when(registry.isPollable(7L)).thenReturn(false);

        assertThat(job.pollTopic(topic)).isZero();

        verify(aggregator, never()).collect(any(), any(), anyInt());
        verify(seenArticles, never()).filterUnseen(anyLong(), any());
        verify(sender, never()).sendBlocking(any(OutgoingMessage.class));
        verify(registry, never()).markPolled(anyLong(), any(Instant.class));
    }

    @Test
    void a_topic_closed_mid_batch_stops_sending_the_rest() {
        feed(first, second, third, fourth);
        // The guard at the entry of pollTopic consumes the first answer, then one
        // answer per article: the topic closes just before the third item.
        when(registry.isPollable(7L)).thenReturn(true, true, true, false);

        assertThat(job.pollTopic(topic)).isEqualTo(2);

        verify(sender, times(2)).sendBlocking(any(OutgoingMessage.class));
        verify(seenArticles).markSent(7L, first);
        verify(seenArticles).markSent(7L, second);
        verify(seenArticles, never()).markSent(7L, third);
        verify(seenArticles, never()).markSent(7L, fourth);
        verify(summarizer, never()).summarize(third);
        verify(summarizer, never()).summarize(fourth);
    }

    @Test
    void a_topic_closed_mid_batch_keeps_the_look_back_before_the_first_undelivered_article() {
        feed(first, second, third, fourth);
        when(registry.isPollable(7L)).thenReturn(true, true, true, false);

        job.pollTopic(topic);

        ArgumentCaptor<Instant> polled = ArgumentCaptor.forClass(Instant.class);
        verify(registry).markPolled(eq(7L), polled.capture());
        assertThat(polled.getValue())
            .as("the article the closed topic never got must be fetched again after a reopen")
            .isBefore(third.publishedAt());
    }

    @Test
    void a_topic_that_stays_open_publishes_the_whole_batch() {
        feed(first, second, third, fourth);
        when(registry.isPollable(7L)).thenReturn(true);

        assertThat(job.pollTopic(topic)).isEqualTo(4);

        verify(sender, times(4)).sendBlocking(any(OutgoingMessage.class));
        verify(seenArticles).markSent(7L, fourth);

        ArgumentCaptor<Instant> polled = ArgumentCaptor.forClass(Instant.class);
        verify(registry).markPolled(eq(7L), polled.capture());
        assertThat(polled.getValue()).isAfter(fourth.publishedAt());
    }

    @Test
    void every_published_article_carries_the_thread_id_of_its_topic() {
        feed(first, second);
        when(registry.isPollable(7L)).thenReturn(true);

        assertThat(job.pollTopic(topic)).isEqualTo(2);

        ArgumentCaptor<OutgoingMessage> sent = ArgumentCaptor.forClass(OutgoingMessage.class);
        verify(sender, times(2)).sendBlocking(sent.capture());
        assertThat(sent.getAllValues()).allSatisfy(message -> {
            assertThat(message.chatId()).isEqualTo(-1001L);
            assertThat(message.threadId()).isEqualTo(42L);
        });
    }
}
