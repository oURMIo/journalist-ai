package com.dach.sagiri.summary;

/**
 * Direct access to the configured model for callers that need a verdict rather
 * than a summary. Implemented by the model-backed summarisers, so the relevance
 * filter reuses whichever backend {@code SUMMARIZER} already selected instead of
 * opening a second one.
 */
public interface LlmGateway {

    /**
     * Runs one prompt through the backend.
     *
     * @param label what the prompt is about, for logs only — a headline, a topic
     * @return the answer, or an empty string when the model failed or the hourly
     *     budget is spent — never throws
     */
    String ask(String prompt, String label);
}
