package com.modtranslator.client;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A small, strict inline XML protocol: translate the whole sentence without dropping styles. */
public final class StyledTextCodec {
    private static final Pattern SPAN = Pattern.compile("<mt(0|[1-9][0-9]*)>([^<]*)</mt\\1>");
    private static final Pattern ENTITY = Pattern.compile("&(#(?:[0-9]+|x[0-9a-fA-F]+)|amp|lt|gt|quot|apos);");
    private StyledTextCodec() {}
    public record Span(int index, String text) {}

    public static String encode(List<String> texts) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < texts.size(); i++) {
            result.append("<mt").append(i).append('>')
                    .append(texts.get(i).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))
                    .append("</mt").append(i).append('>');
        }
        return result.toString();
    }

    /** The provider may reorder spans, but must retain every original span exactly once. */
    public static List<Span> decode(String source, int count) {
        if (source == null || count < 1 || count > 4096) return null;
        List<Span> result = new ArrayList<>(count);
        boolean[] seen = new boolean[count];
        Matcher matcher = SPAN.matcher(source);
        int end = 0;
        while (matcher.find()) {
            if (!source.substring(end, matcher.start()).isBlank()) return null;
            int index;
            try { index = Integer.parseInt(matcher.group(1)); }
            catch (NumberFormatException invalid) { return null; }
            if (index >= count || seen[index]) return null;
            String text = unescape(matcher.group(2));
            if (text == null) return null;
            seen[index] = true;
            result.add(new Span(index, text));
            end = matcher.end();
        }
        return result.size() == count && source.substring(end).isBlank() ? result : null;
    }

    /** Count and decode a source produced by encode; invalid markup is never treated as text. */
    public static String plainText(String source) {
        if (source == null) return null;
        Matcher matcher = SPAN.matcher(source);
        int count = 0;
        while (matcher.find()) count++;
        List<Span> spans = decode(source, count);
        if (spans == null) return null;
        StringBuilder result = new StringBuilder();
        spans.forEach(span -> result.append(span.text));
        return result.toString();
    }

    private static String unescape(String source) {
        Matcher matcher = ENTITY.matcher(source);
        StringBuilder result = new StringBuilder();
        int end = 0;
        while (matcher.find()) {
            String gap = source.substring(end, matcher.start());
            if (gap.indexOf('&') >= 0) return null;
            result.append(gap);
            String entity = matcher.group(1);
            switch (entity) {
                case "amp" -> result.append('&');
                case "lt" -> result.append('<');
                case "gt" -> result.append('>');
                case "quot" -> result.append('"');
                case "apos" -> result.append('\'');
                default -> {
                    int codePoint;
                    try { codePoint = Integer.parseInt(entity.substring(entity.startsWith("#x") ? 2 : 1), entity.startsWith("#x") ? 16 : 10); }
                    catch (NumberFormatException invalid) { return null; }
                    if (!Character.isValidCodePoint(codePoint) || codePoint == 0 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) return null;
                    result.appendCodePoint(codePoint);
                }
            }
            end = matcher.end();
        }
        String tail = source.substring(end);
        return tail.indexOf('&') >= 0 ? null : result.append(tail).toString();
    }
}
