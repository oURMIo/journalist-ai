package com.dach.sagiri.news;

import java.time.Instant;
import java.util.List;
import com.dach.sagiri.news.model.NewsItem;
import com.dach.sagiri.news.model.TopicQuery;

/** A source of news for one topic. Implementations must never throw on a bad feed. */
public interface NewsProvider {

    String name();

    /** Whether this provider can serve the given topic at all. */
    boolean supports(TopicQuery query);

    /**
     * @param since only articles published after this instant are of interest
     * @param limit soft cap on the number of items to return
     * @return the items found; an empty list on any failure, never {@code null}
     */
    List<NewsItem> fetch(TopicQuery query, Instant since, int limit);
}
