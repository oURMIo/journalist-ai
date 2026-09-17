package com.dach.sagiri.filter;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/** One keyword rule of one topic. */
@Entity
@Table(name = "topic_filters")
public class TopicFilterEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "topic_id", nullable = false)
    private Long topicId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false)
    private FilterKind kind;

    /** Stored already normalised, so matching never has to normalise it again. */
    @Column(name = "term", nullable = false)
    private String term;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onInsert() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getTopicId() {
        return topicId;
    }

    public void setTopicId(Long topicId) {
        this.topicId = topicId;
    }

    public FilterKind getKind() {
        return kind;
    }

    public void setKind(FilterKind kind) {
        this.kind = kind;
    }

    public String getTerm() {
        return term;
    }

    public void setTerm(String term) {
        this.term = term;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
