package com.dach.sagiri.filter;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface TopicFilterRepository extends JpaRepository<TopicFilterEntity, Long> {

    List<TopicFilterEntity> findByTopicIdOrderByKindAscTermAsc(Long topicId);

    Optional<TopicFilterEntity> findByTopicIdAndKindAndTerm(Long topicId, FilterKind kind,
        String term);

    long countByTopicId(Long topicId);

    List<TopicFilterEntity> deleteByTopicIdAndTerm(Long topicId, String term);

    long deleteByTopicId(Long topicId);
}
