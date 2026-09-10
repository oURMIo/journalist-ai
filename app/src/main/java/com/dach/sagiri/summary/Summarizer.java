package com.dach.sagiri.summary;

import com.dach.sagiri.news.model.NewsItem;

/** Produces the 2–3 sentence English recap shown under a headline. */
public interface Summarizer {

    String summarize(NewsItem item);
}
