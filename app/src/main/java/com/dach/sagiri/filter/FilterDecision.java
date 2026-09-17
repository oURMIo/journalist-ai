package com.dach.sagiri.filter;

/**
 * What a topic's rules say about one article.
 * <p>
 * The three ways of saying "no" differ in what the next cycle should do, which
 * matters because {@code last_polled_at} moves the look-back forward: an article
 * the cycle walks past is never fetched again.
 */
public record FilterDecision(Outcome outcome, String reason) {

    public enum Outcome {
        ALLOW,
        /** Rejected by a rule that costs nothing to apply again. */
        BLOCK,
        /** Rejected by the model — expensive enough to be worth storing. */
        BLOCK_AND_REMEMBER,
        /** The model was there to answer and could not; ask it again next cycle. */
        POSTPONE
    }

    private static final FilterDecision ALLOWED = new FilterDecision(Outcome.ALLOW, "");

    public static FilterDecision allow() {
        return ALLOWED;
    }

    public static FilterDecision block(String reason) {
        return new FilterDecision(Outcome.BLOCK, reason);
    }

    public static FilterDecision blockAndRemember(String reason) {
        return new FilterDecision(Outcome.BLOCK_AND_REMEMBER, reason);
    }

    public static FilterDecision postpone(String reason) {
        return new FilterDecision(Outcome.POSTPONE, reason);
    }

    public boolean allowed() {
        return outcome == Outcome.ALLOW;
    }

    public boolean remember() {
        return outcome == Outcome.BLOCK_AND_REMEMBER;
    }

    public boolean postponed() {
        return outcome == Outcome.POSTPONE;
    }
}
