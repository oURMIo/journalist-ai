package com.dach.sagiri.topic;

/**
 * What a topic slug actually means, as the model read it.
 *
 * @param domain the subject in words, e.g. "the Java programming language, the
 *     JVM and its ecosystem" — used to tell the relevance check which of the
 *     several subjects sharing the name the reader wants
 * @param query  the search expression that finds that subject and not the others
 */
public record TopicProfile(String domain, String query) {
}
