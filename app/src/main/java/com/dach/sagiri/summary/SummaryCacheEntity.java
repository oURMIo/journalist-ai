package com.dach.sagiri.summary;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;

/** Generated summaries keyed by article fingerprint, so an LLM is never paid twice. */
@Entity
@Table(name = "summary_cache")
public class SummaryCacheEntity {

    @Id
    @Column(name = "fingerprint", nullable = false)
    private String fingerprint;

    @Column(name = "summary", nullable = false)
    private String summary;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @PrePersist
    void onInsert() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public String getFingerprint() {
        return fingerprint;
    }

    public void setFingerprint(String fingerprint) {
        this.fingerprint = fingerprint;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}
