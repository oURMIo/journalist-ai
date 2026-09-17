package com.dach.sagiri.filter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import com.dach.sagiri.config.BotProperties;
import com.dach.sagiri.filter.TopicFilterService.AddResult;
import com.dach.sagiri.topic.TopicEntity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TopicFilterServiceTest {

    private final TopicFilterRepository repository = mock(TopicFilterRepository.class);
    private final BotProperties properties = new BotProperties();
    private final TopicFilterService service = new TopicFilterService(repository, properties);

    private final TopicEntity topic = topic();

    private static TopicEntity topic() {
        TopicEntity topic = new TopicEntity();
        topic.setId(7L);
        topic.setSlug("it");
        return topic;
    }

    private static TopicFilterEntity rule(FilterKind kind, String term) {
        TopicFilterEntity entity = new TopicFilterEntity();
        entity.setTopicId(7L);
        entity.setKind(kind);
        entity.setTerm(term);
        return entity;
    }

    @Test
    void commas_separate_terms_while_spaces_keep_a_phrase_together() {
        assertThat(TopicFilterService.parseTerms("Go, Machine Learning ,  crypto "))
            .containsExactly("go", "machine learning", "crypto");
    }

    @Test
    void duplicate_and_empty_terms_are_dropped() {
        assertThat(TopicFilterService.parseTerms("go, GO, ,,  !!! , go")).containsExactly("go");
        assertThat(TopicFilterService.parseTerms("  ")).isEmpty();
        assertThat(TopicFilterService.parseTerms(null)).isEmpty();
    }

    @Test
    void an_overlong_phrase_is_cut_at_a_word_boundary() {
        String phrase = "machine learning ".repeat(20);

        List<String> terms = TopicFilterService.parseTerms(phrase);

        assertThat(terms).hasSize(1);
        assertThat(terms.getFirst()).hasSizeLessThanOrEqualTo(TopicFilterService.MAX_TERM_LENGTH);
        assertThat(phrase.strip()).as("no word was cut in half")
            .startsWith(terms.getFirst() + " ");
    }

    @Test
    void a_term_that_cannot_match_anything_is_not_stored() {
        assertThat(TopicFilterService.parseTerms("x".repeat(200))).as("one endless word").isEmpty();
        assertThat(TopicFilterService.parseTerms("C++, C#")).as("both normalise to 'c'").isEmpty();
        assertThat(TopicFilterService.parseTerms("go, C++")).containsExactly("go");
    }

    @Test
    void adding_stores_only_the_terms_the_topic_does_not_have_yet() {
        when(repository.countByTopicId(7L)).thenReturn(1L);
        when(repository.findByTopicIdAndKindAndTerm(7L, FilterKind.EXCLUDE, "go"))
            .thenReturn(Optional.of(rule(FilterKind.EXCLUDE, "go")));
        when(repository.findByTopicIdAndKindAndTerm(7L, FilterKind.EXCLUDE, "crypto"))
            .thenReturn(Optional.empty());

        AddResult result = service.add(topic, FilterKind.EXCLUDE, "go, crypto");

        assertThat(result.added()).containsExactly("crypto");
        assertThat(result.limitReached()).isFalse();
        verify(repository).save(any(TopicFilterEntity.class));
    }

    @Test
    void the_per_topic_limit_stops_further_terms() {
        properties.getFilter().setMaxTermsPerTopic(2);
        when(repository.countByTopicId(7L)).thenReturn(2L);
        when(repository.findByTopicIdAndKindAndTerm(anyLong(), any(), any()))
            .thenReturn(Optional.empty());

        AddResult result = service.add(topic, FilterKind.EXCLUDE, "crypto");

        assertThat(result.added()).isEmpty();
        assertThat(result.limitReached()).isTrue();
        verify(repository, never()).save(any(TopicFilterEntity.class));
    }

    @Test
    void reading_the_rules_splits_them_by_kind() {
        when(repository.findByTopicIdOrderByKindAscTermAsc(7L)).thenReturn(List.of(
            rule(FilterKind.EXCLUDE, "go"),
            rule(FilterKind.EXCLUDE, "crypto"),
            rule(FilterKind.INCLUDE, "kubernetes")));

        TopicFilters filters = service.of(topic);

        assertThat(filters.slug()).isEqualTo("it");
        assertThat(filters.exclude()).containsExactly("go", "crypto");
        assertThat(filters.include()).containsExactly("kubernetes");
        assertThat(filters.isEmpty()).isFalse();
    }

    @Test
    void a_topic_without_rows_has_no_rules() {
        when(repository.findByTopicIdOrderByKindAscTermAsc(7L)).thenReturn(List.of());

        assertThat(service.of(topic).isEmpty()).isTrue();
    }

    @Test
    void removing_normalises_the_term_and_names_only_what_was_stored() {
        when(repository.deleteByTopicIdAndTerm(7L, "go"))
            .thenReturn(List.of(rule(FilterKind.EXCLUDE, "go")));
        when(repository.deleteByTopicIdAndTerm(7L, "nft")).thenReturn(new ArrayList<>());

        assertThat(service.remove(topic, " GO ")).containsExactly("go");
        assertThat(service.remove(topic, "nft")).isEmpty();
        assertThat(service.remove(topic, "go, nft")).as("only the one that existed")
            .containsExactly("go");
        assertThat(service.remove(topic, "  ")).isEmpty();
        verify(repository, never()).deleteByTopicIdAndTerm(eq(7L), eq(""));
    }
}
