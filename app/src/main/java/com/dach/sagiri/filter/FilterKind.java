package com.dach.sagiri.filter;

/** Which way a keyword acts on the news of a topic. */
public enum FilterKind {

    /** An article mentioning the term is never delivered. */
    EXCLUDE,

    /** At least one such term must be present for an article to be delivered. */
    INCLUDE
}
