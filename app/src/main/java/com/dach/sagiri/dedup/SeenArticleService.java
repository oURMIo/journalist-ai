package com.dach.sagiri.dedup;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.dach.sagiri.news.model.NewsItem;

/**
 * Cross-run de-duplication: which articles a topic has already received.
 * <p>
 * Two axes, because one is not enough — the canonical URL catches the same link
 * with different tracking parameters, the normalised title catches the same
 * story republished by another aggregator under its own URL.
 */
@Service
public class SeenArticleService {

    private static final Logger logger = LoggerFactory.getLogger(SeenArticleService.class);

    private final SeenArticleRepository repository;
    private final UrlCanonicalizer canonicalizer;

    public SeenArticleService(SeenArticleRepository repository, UrlCanonicalizer canonicalizer) {
        this.repository = repository;
        this.canonicalizer = canonicalizer;
    }

    public String fingerprintOf(NewsItem item) {
        return Fingerprints.sha256(canonicalizer.canonicalize(item.url()));
    }

    /** Keeps only the items this topic has not seen before, order preserved. */
    @Transactional(readOnly = true)
    public List<NewsItem> filterUnseen(Long topicId, List<NewsItem> items) {
        if (items.isEmpty()) {
            return List.of();
        }
        List<String> fingerprints = items.stream().map(this::fingerprintOf).toList();
        List<String> titleHashes = items.stream()
            .map(item -> Fingerprints.titleHash(item.title()))
            .toList();

        Set<String> knownFingerprints =
            new HashSet<>(repository.findKnownFingerprints(topicId, fingerprints));
        Set<String> knownTitles =
            new HashSet<>(repository.findKnownTitleHashes(topicId, titleHashes));

        List<NewsItem> unseen = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            if (!knownFingerprints.contains(fingerprints.get(i))
                && !knownTitles.contains(titleHashes.get(i))) {
                unseen.add(items.get(i));
            }
        }
        return unseen;
    }

    /** Records an article as delivered. Called only after Telegram accepted it. */
    @Transactional
    public void markSent(Long topicId, NewsItem item) {
        SeenArticleEntity entity = new SeenArticleEntity();
        entity.setTopicId(topicId);
        entity.setFingerprint(fingerprintOf(item));
        entity.setTitleHash(Fingerprints.titleHash(item.title()));
        entity.setUrl(item.url());
        entity.setSentAt(Instant.now());
        try {
            repository.save(entity);
        } catch (DataIntegrityViolationException e) {
            // The unique index already holds this article: nothing left to do.
            logger.debug("Article {} was already recorded for topic {}", item.url(), topicId);
        }
    }

    @Transactional
    public int purgeOlderThan(Instant threshold) {
        return repository.deleteOlderThan(threshold);
    }
}
