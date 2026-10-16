package com.modtranslator.core;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/** Paragraph boundaries stay intact; reverse admission keeps the top of a visible page first. */
public final class VisibleDocument {
    private static final Pattern BREAK = Pattern.compile("\\r?\\n[\\t ]*\\r?\\n(?:[\\t ]*\\r?\\n)*");
    private VisibleDocument() {}
    public static String resolve(String source, UnaryOperator<String> resolver) {
        if (source == null || source.isBlank()) return source;
        List<String> blocks = new ArrayList<>(), separators = new ArrayList<>();
        var matcher = BREAK.matcher(source);
        int end = 0;
        while (matcher.find()) {
            blocks.add(source.substring(end, matcher.start()));
            separators.add(matcher.group()); end = matcher.end();
        }
        blocks.add(source.substring(end));
        String[] resolved = new String[blocks.size()];
        for (int i = blocks.size() - 1; i >= 0; i--) {
            String block = blocks.get(i);
            resolved[i] = block.isBlank() ? block : resolver.apply(block);
            if (resolved[i] == null) resolved[i] = block;
        }
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < resolved.length; i++) {
            result.append(resolved[i]);
            if (i < separators.size()) result.append(separators.get(i));
        }
        return result.toString();
    }
}
