package com.modtranslator.core;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small Minecraft vocabulary shared by machine providers; never rewrites complete quests. */
public final class TranslationTerminology {
    private static final Pattern TERMS = Pattern.compile("\\b(?:Early Birds|Early Bird|Netherwart|Nether Wart)\\b", Pattern.CASE_INSENSITIVE);
    private TranslationTerminology() {}

    static String apply(String translated, Pattern protectedTokens) {
        Matcher tokens = protectedTokens.matcher(translated);
        StringBuilder result = new StringBuilder();
        int offset = 0;
        while (tokens.find()) {
            result.append(visible(translated.substring(offset, tokens.start()))).append(tokens.group());
            offset = tokens.end();
        }
        return result.append(visible(translated.substring(offset))).toString();
    }

    private static String visible(String text) {
        Matcher matcher = TERMS.matcher(text);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String term = matcher.group().toLowerCase(java.util.Locale.ROOT);
            String replacement = term.equals("early birds") ? "madrugadores" : term.equals("early bird") ? "madrugador" : "Fungo do Nether";
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
