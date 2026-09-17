package com.dach.sagiri.telegram.command;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import com.dach.sagiri.config.BotProperties;
import com.dach.sagiri.config.MessageTemplates;
import com.dach.sagiri.filter.AiRelevanceChecker;
import com.dach.sagiri.filter.FilterKind;
import com.dach.sagiri.filter.TopicFilterService;
import com.dach.sagiri.filter.TopicFilterService.AddResult;
import com.dach.sagiri.filter.TopicFilters;
import com.dach.sagiri.telegram.TelegramSender;
import com.dach.sagiri.topic.TopicEntity;
import com.dach.sagiri.topic.TopicRegistryService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FilterCommandTest {

    private static final long CHAT_ID = -1001L;
    private static final long THREAD_ID = 42L;

    private final TelegramSender sender = mock(TelegramSender.class);
    private final TopicRegistryService registry = mock(TopicRegistryService.class);
    private final TopicFilterService filters = mock(TopicFilterService.class);
    private final AiRelevanceChecker ai = mock(AiRelevanceChecker.class);
    private final MessageTemplates messages = messages();
    private final FilterCommand command =
        new FilterCommand(sender, messages, new BotProperties(), registry, filters, ai);

    private final TopicEntity topic = topic();

    private static MessageTemplates messages() {
        MessageTemplates messages = new MessageTemplates();
        messages.setNotInTopic("not-in-topic");
        messages.setUnbindUnknown("unknown-topic");
        messages.setFilterUsage("usage");
        messages.setFilterHeader("filters of {slug}");
        messages.setFilterEmpty("no filters for {slug}");
        messages.setFilterExcludeLabel("skip:");
        messages.setFilterIncludeLabel("only:");
        messages.setFilterAiOn("ai-on");
        messages.setFilterAiOff("ai-off");
        messages.setFilterAdded("added ({kind}): {terms}");
        messages.setFilterNothingAdded("nothing-added");
        messages.setFilterLimit("limit {max}");
        messages.setFilterRemoved("removed {terms}");
        messages.setFilterNotFound("not-found {terms}");
        messages.setFilterCleared("cleared {count}");
        messages.setFilterKindExclude("skip");
        messages.setFilterKindInclude("only");
        return messages;
    }

    private static TopicEntity topic() {
        TopicEntity topic = new TopicEntity();
        topic.setId(7L);
        topic.setChatId(CHAT_ID);
        topic.setThreadId(THREAD_ID);
        topic.setSlug("it");
        return topic;
    }

    private CommandContext context(String name, String... args) {
        when(registry.find(CHAT_ID, THREAD_ID)).thenReturn(java.util.Optional.of(topic));
        return new CommandContext(null, CHAT_ID, THREAD_ID, name, List.of(args), true);
    }

    private String reply() {
        ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
        verify(sender).reply(eq(CHAT_ID), eq(THREAD_ID), text.capture());
        return text.getValue();
    }

    @Test
    void outside_a_topic_it_explains_where_to_send_the_command() {
        command.execute(new CommandContext(null, CHAT_ID, null, "/filter", List.of(), true));

        verify(sender).reply(CHAT_ID, null, "not-in-topic");
        verify(filters, never()).of(any());
    }

    @Test
    void an_unregistered_topic_is_reported_instead_of_being_filtered() {
        when(registry.find(CHAT_ID, THREAD_ID)).thenReturn(java.util.Optional.empty());

        command.execute(new CommandContext(null, CHAT_ID, THREAD_ID, "/filter", List.of(), true));

        assertThat(reply()).isEqualTo("unknown-topic");
    }

    @Test
    void exclude_adds_the_terms_and_shows_the_updated_list() {
        when(filters.add(topic, FilterKind.EXCLUDE, "go, crypto"))
            .thenReturn(new AddResult(List.of("go", "crypto"), false));
        when(filters.of(topic))
            .thenReturn(new TopicFilters("it", List.of("go", "crypto"), List.of()));
        when(ai.available()).thenReturn(true);

        command.execute(context("/exclude", "go,", "crypto"));

        assertThat(reply())
            .contains("added (skip): <code>go</code>, <code>crypto</code>")
            .contains("filters of it")
            .contains("skip: <code>go</code>, <code>crypto</code>")
            .contains("ai-on");
    }

    @Test
    void include_goes_into_the_other_list() {
        when(filters.add(topic, FilterKind.INCLUDE, "kubernetes"))
            .thenReturn(new AddResult(List.of("kubernetes"), false));
        when(filters.of(topic))
            .thenReturn(new TopicFilters("it", List.of(), List.of("kubernetes")));

        command.execute(context("/include", "kubernetes"));

        assertThat(reply()).contains("added (only): <code>kubernetes</code>")
            .contains("only: <code>kubernetes</code>")
            .contains("ai-off");
    }

    @Test
    void a_full_topic_reports_the_limit() {
        when(filters.add(topic, FilterKind.EXCLUDE, "nft"))
            .thenReturn(new AddResult(List.of(), true));

        command.execute(context("/exclude", "nft"));

        assertThat(reply()).isEqualTo("limit 30");
    }

    @Test
    void a_bare_filter_command_lists_the_rules() {
        when(filters.of(topic)).thenReturn(new TopicFilters("it", List.of("go"), List.of()));

        command.execute(context("/filter"));

        assertThat(reply()).contains("filters of it").contains("skip: <code>go</code>");
        verify(filters, never()).add(any(), any(), any());
    }

    @Test
    void a_topic_without_rules_gets_the_usage_hint() {
        when(filters.of(topic)).thenReturn(TopicFilters.none("it"));

        command.execute(context("/filter"));

        assertThat(reply()).contains("no filters for it").contains("usage");
    }

    @Test
    void remove_reaches_the_service_and_names_what_went() {
        when(filters.remove(topic, "go")).thenReturn(List.of("go"));
        when(filters.of(topic)).thenReturn(TopicFilters.none("it"));

        command.execute(context("/filter", "remove", "go"));

        assertThat(reply()).contains("removed <code>go</code>");
        verify(filters).remove(topic, "go");
    }

    @Test
    void removing_a_term_the_topic_never_had_does_not_claim_success() {
        when(filters.remove(topic, "go, crypto")).thenReturn(List.of());

        command.execute(context("/filter", "remove", "go,", "crypto"));

        assertThat(reply()).isEqualTo("not-found <code>go</code>, <code>crypto</code>");
    }

    @Test
    void a_batch_that_hit_the_limit_says_so_even_when_part_of_it_landed() {
        when(filters.add(topic, FilterKind.EXCLUDE, "go, crypto, nft"))
            .thenReturn(new AddResult(List.of("go"), true));
        when(filters.of(topic)).thenReturn(new TopicFilters("it", List.of("go"), List.of()));

        command.execute(context("/exclude", "go,", "crypto,", "nft"));

        assertThat(reply()).contains("added (skip): <code>go</code>").contains("limit 30");
    }

    @Test
    void clear_reports_how_many_rules_went() {
        when(filters.clear(topic)).thenReturn(3L);

        command.execute(context("/filter", "clear"));

        assertThat(reply()).isEqualTo("cleared 3");
    }

    @Test
    void an_unknown_subcommand_prints_the_usage() {
        command.execute(context("/filter", "wat"));
        assertThat(reply()).isEqualTo("usage");
    }

    @Test
    void a_term_nothing_could_be_made_of_prints_the_usage_instead_of_a_wrong_answer() {
        command.execute(context("/exclude", "???"));

        assertThat(reply()).isEqualTo("usage");
        verify(filters, never()).add(any(), any(), any());
    }
}
