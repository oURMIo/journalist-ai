package com.dach.sagiri.dedup;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface SeenArticleRepository extends JpaRepository<SeenArticleEntity, Long> {

    @Query("select s.fingerprint from SeenArticleEntity s "
           + "where s.topicId = :topicId and s.fingerprint in :fingerprints")
    List<String> findKnownFingerprints(@Param("topicId") Long topicId,
        @Param("fingerprints") Collection<String> fingerprints);

    @Query("select s.titleHash from SeenArticleEntity s "
           + "where s.topicId = :topicId and s.titleHash in :titleHashes")
    List<String> findKnownTitleHashes(@Param("topicId") Long topicId,
        @Param("titleHashes") Collection<String> titleHashes);

    @Transactional
    @Modifying
    @Query("delete from SeenArticleEntity s where s.sentAt < :threshold")
    int deleteOlderThan(@Param("threshold") Instant threshold);
}
