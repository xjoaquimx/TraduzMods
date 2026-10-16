package com.modtranslator.core;

/** Presentation-only escaping for literal ampersands passed to FTB's text parser. */
public final class FtbTextSafety {
    private FtbTextSafety() {}

    public static String escapeLiteralAmpersands(String text) {
        if (text == null || text.isEmpty()) return text;
        StringBuilder safe = null;
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            // FTB checks only the immediately preceding character, not backslash parity.
            boolean escaped = i > 0 && text.charAt(i - 1) == '\\';
            boolean literal = current == '&' && !escaped
                    && (i + 1 == text.length() || Character.isWhitespace(text.charAt(i + 1))
                    || Character.isSpaceChar(text.charAt(i + 1)));
            if (literal) {
                if (safe == null) safe = new StringBuilder(text.length() + 8).append(text, 0, i);
                safe.append('\\');
            }
            if (safe != null) safe.append(current);
        }
        return safe == null ? text : safe.toString();
    }
}
