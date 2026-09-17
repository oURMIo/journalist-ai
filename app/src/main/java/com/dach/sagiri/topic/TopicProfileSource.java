package com.dach.sagiri.topic;

/** Where the search query and the domain of a topic came from. */
public enum TopicProfileSource {

    /** Nothing derived yet: the query is the slug, which is what ambiguity costs. */
    SLUG,

    /** Derived once from the slug by the configured model. */
    MODEL,

    /** Set by hand with {@code /query}; never overwritten automatically. */
    USER
}
