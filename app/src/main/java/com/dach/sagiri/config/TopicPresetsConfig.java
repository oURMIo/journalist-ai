package com.dach.sagiri.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Curated feeds per topic slug, loaded from {@code topic-presets.yml}.
 * <p>
 * A preset is a quality optimisation only. A slug with no preset still works via
 * the Google News search fallback, which is what makes "create a topic and get
 * news" work for an arbitrary subject.
 */
@Component
@ConfigurationProperties
public class TopicPresetsConfig {

    private Map<String, Preset> presets = new LinkedHashMap<>();

    public Optional<Preset> find(String slug) {
        return slug == null ? Optional.empty() : Optional.ofNullable(presets.get(slug));
    }

    public Map<String, Preset> getPresets() {
        return presets;
    }

    public void setPresets(Map<String, Preset> presets) {
        this.presets = presets;
    }

    public static class Preset {

        private String query = "";
        private List<String> feeds = List.of();

        public String getQuery() {
            return query;
        }

        public void setQuery(String query) {
            this.query = query;
        }

        public List<String> getFeeds() {
            return feeds;
        }

        public void setFeeds(List<String> feeds) {
            this.feeds = feeds;
        }
    }
}
