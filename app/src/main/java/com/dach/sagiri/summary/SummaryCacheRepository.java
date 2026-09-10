package com.dach.sagiri.summary;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SummaryCacheRepository extends JpaRepository<SummaryCacheEntity, String> {
}
