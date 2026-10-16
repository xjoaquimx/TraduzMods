package com.modtranslator.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Protects runtime formatting and markup; rejects unsafe machine output. */
public final class TextProtection {
    private static final Pattern TOKENS = Pattern.compile(
            "%(?:\\d+\\$)?[-#+ 0,(]*\\d*(?:\\.\\d+)?(?:[tT][a-zA-Z]|[a-zA-Z])|%%"
                    + "|\\$\\{[^{}]+}|\\{(?:\\d+|[A-Za-z_][\\w.-]*)(?:,[^{}]*)?}"
                    + "|\\{[A-Za-z_][\\w.-]*:[^{}]*}"
                    + "|&(?![A-Za-z][A-Za-z0-9]*;)[0-9a-fk-orA-FK-OR]|§x(?:§[0-9a-fA-F]){6}|§[0-9a-fk-orA-FK-OR]|</?[A-Za-z][^<>]*>|https?://[^\\s<>]+"
                    + "|\\$\\([^)]*\\)|[a-z0-9_]+:[a-z0-9_/.-]+"
                    + "|`[^`\\r\\n]+`|(?<![\\p{L}\\p{N}_])--[A-Za-z][\\w-]*(?:=[^\\s]+)?"
                    + "|(?<![\\p{L}\\p{N}_])#[\\p{L}\\p{N}_][\\p{L}\\p{N}_-]*"
                    + "|(?:[A-Za-z0-9_.-]+/)+[A-Za-z0-9_.-]+|\\b[A-Za-z0-9_.-]+\\.(?:tsx?|jsx?|json|md|java|py|ya?ml|toml|css|html)\\b"
                    + "|\\b[A-Za-z][\\w.-]*@\\d+(?:\\.\\d+)*\\b|\\bv?\\d+\\.\\d+(?:\\.\\d+)*\\b"
                    + "|[\\r\\n\\t]|MODTRPHX*\\d+X");

    private TextProtection() {}

    private static final Pattern COLORS = Pattern.compile("&(?![A-Za-z][A-Za-z0-9]*;)[0-9a-fk-orA-FK-OR]|§x(?:§[0-9a-fA-F]){6}|§[0-9a-fk-orA-FK-OR]");

    public static ProtectedText protect(String source) {
        String originalSource = source;
        Formatting formatting = null;
        if (!source.startsWith("<mt0>")) {
            Matcher colors = COLORS.matcher(source);
            List<String> prefixes = new ArrayList<>();
            List<String> leading = new ArrayList<>();
            List<String> trailing = new ArrayList<>();
            List<String> labels = new ArrayList<>();
            StringBuilder styled = new StringBuilder();
            String prefix = "";
            int offset = 0;
            while (colors.find()) {
                if (colors.start() > offset) {
                    String body = source.substring(offset, colors.start());
                    appendSpan(styled, prefixes.size(), body);
                    captureWhitespace(body, leading, trailing);
                    labels.add(body.strip());
                    prefixes.add(prefix);
                    prefix = "";
                }
                prefix += colors.group();
                offset = colors.end();
            }
            if (offset > 0 && offset < source.length()) {
                String body = source.substring(offset);
                appendSpan(styled, prefixes.size(), body);
                captureWhitespace(body, leading, trailing);
                labels.add(body.strip());
                prefixes.add(prefix);
                prefix = "";
            }
            if (offset > 0 && !prefixes.isEmpty()) {
                formatting = new Formatting(List.copyOf(prefixes), List.copyOf(leading), List.copyOf(trailing), List.copyOf(labels),GameTranslationContext.isQuestSource(originalSource),prefix);
                source = styled.toString();
            }
        }
        String prefix = "MODTRPH";
        while (source.contains(prefix)) prefix += "X";
        Matcher matcher = ModTextPolicy.protectedTokens(TOKENS).matcher(source);
        StringBuilder value = new StringBuilder();
        List<String> originals = new ArrayList<>();
        List<String> markers = new ArrayList<>();
        while (matcher.find()) {
            // Internal rich-text codec tags remain real XML so providers see the whole phrase.
            if (source.startsWith("<mt0>") && matcher.group().matches("</?mt\\d+>")) {
                matcher.appendReplacement(value, Matcher.quoteReplacement(matcher.group()));
                continue;
            }
            String marker = prefix + markers.size() + "X";
            markers.add(marker);
            originals.add(matcher.group());
            matcher.appendReplacement(value, Matcher.quoteReplacement(marker));
        }
        matcher.appendTail(value);
        return new ProtectedText(value.toString(), List.copyOf(originals), List.copyOf(markers), originalSource, formatting);
    }

    private static void appendSpan(StringBuilder xml, int id, String text) {
        xml.append("<mt").append(id).append('>').append(escapeXml(text)).append("</mt").append(id).append('>');
    }

    private static String escapeXml(String text) { return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
    private static String unescapeXml(String text) { return text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&"); }

    private static void captureWhitespace(String text, List<String> leading, List<String> trailing) {
        int start = 0;int end = text.length();
        while(start < end && Character.isWhitespace(text.charAt(start)))start++;
        while(end > start && Character.isWhitespace(text.charAt(end-1)))end--;
        leading.add(text.substring(0,start));trailing.add(text.substring(end));
    }

    public record Formatting(List<String> prefixes, List<String> leading, List<String> trailing, List<String> labels, boolean quest, String suffix) {
        String restore(String xml) {
            Matcher spans = Pattern.compile("<mt(\\d+)>(.*?)</mt\\1>", Pattern.DOTALL).matcher(xml);
            boolean[] seen = new boolean[prefixes.size()];
            StringBuilder restored = new StringBuilder();
            int offset = 0;
            int count = 0;
            while (spans.find()) {
                if (!xml.substring(offset, spans.start()).isBlank()) throw new IllegalArgumentException("Text outside formatting spans");
                int id = Integer.parseInt(spans.group(1));
                if (id >= seen.length || seen[id]) throw new IllegalArgumentException("Invalid formatting span id");
                seen[id] = true;count++;
                String body=unescapeXml(spans.group(2));
                if(quest)body=GameTranslationContext.canonicalStyledLabel(labels.get(id),body);
                if(!body.isEmpty()&&!leading.get(id).isEmpty()&&Character.isLetterOrDigit(body.charAt(0)))body=leading.get(id)+body;
                if(!body.isEmpty()&&!trailing.get(id).isEmpty()&&!Character.isWhitespace(body.charAt(body.length()-1)))body+=trailing.get(id);
                restored.append(prefixes.get(id)).append(body);
                offset = spans.end();
            }
            if (count != seen.length || !xml.substring(offset).isBlank()) throw new IllegalArgumentException("Translation lost formatting spans");
            return restored.append(suffix).toString();
        }
    }

    public record ProtectedText(String value, List<String> originals, List<String> markers, String source, Formatting formatting) {
        /** Rejects results if any protected token was removed, duplicated, or invented. */
        public String restore(String translated) {
            if (translated == null || translated.isBlank()) throw new IllegalArgumentException("Empty translation result");
            for (String marker : markers) {
                int first = translated.indexOf(marker);
                if (first < 0 || translated.indexOf(marker, first + marker.length()) >= 0) {
                    throw new IllegalArgumentException("Translation changed a protected placeholder");
                }
            }
            String result = translated;
            for (int index = 0; index < markers.size(); index++) {
                result = result.replace(markers.get(index), originals.get(index));
            }
            if (formatting != null) result = formatting.restore(result);
            result = TranslationTerminology.apply(result, TOKENS);
            result = GameTranslationContext.normalizeTranslated(source, result);
            result = ModTextPolicy.normalizeUi(source, result);
            if(!ModTextPolicy.namesPreserved(source,result)) throw new IllegalArgumentException("Translation changed an installed mod name");
            // Machine output must retain exactly the formatting multiset, even if reordered.
            if (!tokens(source).equals(tokens(result))) throw new IllegalArgumentException("Translation changed formatting or markup");
            return result;
        }

        private static java.util.Map<String, Integer> tokens(String text) {
            java.util.Map<String, Integer> counts = new java.util.HashMap<>();
            Matcher matcher = TOKENS.matcher(text);
            while (matcher.find()) counts.merge(matcher.group(), 1, Integer::sum);
            return counts;
        }
    }
}
