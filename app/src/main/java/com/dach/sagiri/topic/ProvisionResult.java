package com.dach.sagiri.topic;

/**
 * Outcome of one topic provisioning attempt.
 *
 * @param threadId id of the created or already existing topic; {@code null} on failure
 * @param message  human readable explanation, ready to be shown in the chat
 */
public record ProvisionResult(String slug, Status status, Long threadId, String message) {

    public enum Status { CREATED, ALREADY_EXISTS, INVALID_SLUG, FAILED }

    public static ProvisionResult created(String slug, long threadId, String message) {
        return new ProvisionResult(slug, Status.CREATED, threadId, message);
    }

    public static ProvisionResult alreadyExists(String slug, long threadId, String message) {
        return new ProvisionResult(slug, Status.ALREADY_EXISTS, threadId, message);
    }

    public static ProvisionResult invalidSlug(String slug, String message) {
        return new ProvisionResult(slug, Status.INVALID_SLUG, null, message);
    }

    public static ProvisionResult failed(String slug, String message) {
        return new ProvisionResult(slug, Status.FAILED, null, message);
    }
}
