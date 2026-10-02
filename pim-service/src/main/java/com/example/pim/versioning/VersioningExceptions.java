package com.example.pim.versioning;

public final class VersioningExceptions {

    private VersioningExceptions() {
    }

    public static class NotFound extends RuntimeException {
        public NotFound(String message) {
            super(message);
        }
    }

    /** The version exists but its status does not allow the operation (e.g. editing an APPROVED version). */
    public static class InvalidState extends RuntimeException {
        public InvalidState(String message) {
            super(message);
        }
    }

    /** Optimistic-lock failure: someone else changed the draft since the caller read it. */
    public static class StaleDraft extends RuntimeException {
        public StaleDraft(String message) {
            super(message);
        }
    }
}
