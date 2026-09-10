package com.dach.sagiri.telegram;

/** Helpers for building {@code parse_mode=HTML} payloads. */
public final class HtmlText {

    /** Telegram rejects messages longer than this. */
    public static final int MAX_MESSAGE_LENGTH = 4096;

    private HtmlText() {
    }

    /**
     * Escapes the three characters Telegram treats as markup. Everything the bot
     * interpolates from a feed or from a user must go through this.
     */
    public static String escape(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;");
    }

    /** Cuts to {@code max} characters, appending an ellipsis when something was cut. */
    public static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        String stripped = text.strip();
        if (stripped.length() <= max) {
            return stripped;
        }
        return stripped.substring(0, Math.max(0, max - 1)).stripTrailing() + "…";
    }

    /** Last-resort guard so an oversized message is trimmed instead of rejected. */
    public static String fitMessage(String html) {
        return html.length() <= MAX_MESSAGE_LENGTH
            ? html
            : html.substring(0, MAX_MESSAGE_LENGTH - 1) + "…";
    }
}
