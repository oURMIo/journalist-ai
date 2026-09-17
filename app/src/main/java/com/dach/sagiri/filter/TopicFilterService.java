package com.dach.sagiri.filter;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.dach.sagiri.config.BotProperties;
import com.dach.sagiri.topic.TopicEntity;

/** Reads and edits the keyword rules of a topic. */
@Service
public class TopicFilterService {

    private static final Logger logger = LoggerFactory.getLogger(TopicFilterService.class);

    /** Longer than a phrase anybody filters by, and short enough to stay readable. */
    static final int MAX_TERM_LENGTH = 80;

    /**
     * One letter is never a filter: {@code C++} and {@code C#} both normalise to
     * "c", and storing that would block every headline containing a stray "C".
     */
    static final int MIN_TERM_LENGTH = 2;

    /**
     * What one edit did.
     *
     * @param limitReached some terms were dropped because the topic is full
     */
    public record AddResult(List<String> added, boolean limitReached) {

        public AddResult {
            added = List.copyOf(added);
        }
    }

    private final TopicFilterRepository repository;
    private final BotProperties properties;

    public TopicFilterService(TopicFilterRepository repository, BotProperties properties) {
        this.repository = repository;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public TopicFilters of(TopicEntity topic) {
        List<TopicFilterEntity> rules =
            repository.findByTopicIdOrderByKindAscTermAsc(topic.getId());
        if (rules.isEmpty()) {
            return TopicFilters.none(topic.getSlug(), topic.getDomain());
        }
        return new TopicFilters(topic.getSlug(), topic.getDomain(),
            termsOf(rules, FilterKind.EXCLUDE), termsOf(rules, FilterKind.INCLUDE));
    }

    @Transactional
    public AddResult add(TopicEntity topic, FilterKind kind, String rawTerms) {
        List<String> terms = parseTerms(rawTerms);
        long used = repository.countByTopicId(topic.getId());
        int max = properties.getFilter().getMaxTermsPerTopic();

        List<String> added = new ArrayList<>();
        boolean limitReached = false;
        for (String term : terms) {
            if (repository.findByTopicIdAndKindAndTerm(topic.getId(), kind, term).isPresent()) {
                continue;
            }
            if (used >= max) {
                limitReached = true;
                break;
            }
            repository.save(rule(topic.getId(), kind, term));
            added.add(term);
            used++;
        }
        if (!added.isEmpty()) {
            logger.info("Topic '{}' filter {} += {}", topic.getSlug(), kind, added);
        }
        return new AddResult(added, limitReached);
    }

    /**
     * Removes terms whichever list they sit in — the user does not track the kind.
     *
     * @return the terms that were actually stored, so the reply can name them
     */
    @Transactional
    public List<String> remove(TopicEntity topic, String rawTerms) {
        List<String> removed = new ArrayList<>();
        for (String term : parseTerms(rawTerms)) {
            if (!repository.deleteByTopicIdAndTerm(topic.getId(), term).isEmpty()) {
                removed.add(term);
            }
        }
        return List.copyOf(removed);
    }

    @Transactional
    public long clear(TopicEntity topic) {
        return repository.deleteByTopicId(topic.getId());
    }

    /**
     * Splits user input into terms: commas separate, whitespace does not, so
     * {@code /exclude machine learning, crypto} is a phrase and a word, not three
     * words.
     */
    public static List<String> parseTerms(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        Set<String> terms = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String term = trimToWord(KeywordMatcher.normalize(part));
            if (term.length() >= MIN_TERM_LENGTH) {
                terms.add(term);
            }
        }
        return List.copyOf(terms);
    }

    /**
     * Matching compares whole word sequences, so a term cut mid-word could never
     * match anything while still occupying one of the slots of the topic.
     */
    private static String trimToWord(String term) {
        if (term.length() <= MAX_TERM_LENGTH) {
            return term;
        }
        String cut = term.substring(0, MAX_TERM_LENGTH);
        int lastSpace = cut.lastIndexOf(' ');
        return lastSpace > 0 ? cut.substring(0, lastSpace) : "";
    }

    private static List<String> termsOf(List<TopicFilterEntity> rules, FilterKind kind) {
        return rules.stream()
            .filter(rule -> rule.getKind() == kind)
            .map(TopicFilterEntity::getTerm)
            .toList();
    }

    private static TopicFilterEntity rule(Long topicId, FilterKind kind, String term) {
        TopicFilterEntity entity = new TopicFilterEntity();
        entity.setTopicId(topicId);
        entity.setKind(kind);
        entity.setTerm(term);
        return entity;
    }
}
