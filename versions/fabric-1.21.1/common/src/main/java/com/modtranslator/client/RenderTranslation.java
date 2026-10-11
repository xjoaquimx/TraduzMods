package com.modtranslator.client;

import com.modtranslator.core.TranslationService;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentContents;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.contents.PlainTextContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.util.FormattedCharSequence;

import java.lang.StackWalker;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Nonblocking render adapters. Never flatten a Component and discard its interactive style. */
public final class RenderTranslation {
    private static final StackWalker WALKER = StackWalker.getInstance();
    private static final ThreadLocal<Boolean> BUSY = ThreadLocal.withInitial(() -> false);
    private static final IdentityHashMap<FormattedCharSequence, SequenceResult> SEQUENCES = new IdentityHashMap<>();
    private static final int CACHE_LIMIT = 4096;
    private static final java.util.Set<String> INPUT_PATHS = java.util.Set.of(
            net.minecraft.client.gui.components.ChatComponent.class.getName(),
            net.minecraft.client.gui.screens.ChatScreen.class.getName(),
            net.minecraft.client.gui.components.EditBox.class.getName(),
            net.minecraft.client.gui.screens.inventory.BookEditScreen.class.getName(),
            net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen.class.getName(),
            net.minecraft.client.renderer.blockentity.SignRenderer.class.getName(),
            net.minecraft.client.gui.components.PlayerTabOverlay.class.getName(),
            net.minecraft.client.renderer.entity.EntityRenderer.class.getName());
    private RenderTranslation() {}

    private static boolean allowed() {
        if (BUSY.get() || !TranslationService.isPortugueseActive()) return false;
        // Inspect the submission call stack, before deferred GUI drawing erases its origin.
        // Do not translate user input, chat, signs, or the vanilla player-name paths.
        return !WALKER.walk(frames -> frames.limit(48).anyMatch(frame -> {
            String name = frame.getClassName().toLowerCase(Locale.ROOT);
            String method = frame.getMethodName().toLowerCase(Locale.ROOT);
            return INPUT_PATHS.contains(frame.getClassName()) || name.startsWith("dev.ftb.mods.ftbquests.")
                    || name.startsWith("de.keksuccino.fancymenu.util.rendering.text.markdown.")
                    || name.startsWith("guideme.")
                    || name.contains("chatcomponent") || name.contains("chatscreen")
                    || name.contains("editbox") || name.contains("textinput")
                    || name.contains("bookeditscreen") || name.contains("signeditscreen")
                    || name.contains("signrenderer") || name.contains("nametag")
                    || name.contains("playertaboverlay")
                    || method.contains("rendernametag") || method.contains("drawplayername");
        }));
    }

    public static String string(String source) {
        if (source == null || source.isBlank() || !allowed()) return source;
        BUSY.set(true);
        try { return TranslationService.translateLiteral(source); }
        finally { BUSY.set(false); }
    }

    public static Component component(Component source) {
        if (source == null || !allowed()) return source;
        BUSY.set(true);
        try { return componentUnchecked(source, 0); }
        finally { BUSY.set(false); }
    }

    private static Component componentUnchecked(Component source, int depth) {
        if (depth > 64) return source;
        if (literalTree(source, depth)) {
            List<Run> runs = collect(source);
            List<Run> translated = translateRuns(runs);
            if (translated == runs) return source;
            MutableComponent result = Component.empty();
            for (Run run : translated) result.append(Component.literal(run.text.toString()).setStyle(run.style));
            return result;
        }
        ComponentContents contents = source.getContents();
        boolean changed = false;
        MutableComponent result;
        if (contents instanceof PlainTextContents literal) {
            String translated = TranslationService.translateLiteral(literal.text());
            changed = !literal.text().equals(translated);
            result = Component.literal(translated);
        } else {
            // Preserve keys, arguments, objects, selectors and dynamic component semantics.
            result = MutableComponent.create(contents);
        }
        result.setStyle(source.getStyle());
        for (Component sibling : source.getSiblings()) {
            Component translated = componentUnchecked(sibling, depth + 1);
            changed |= translated != sibling;
            result.append(translated);
        }
        return changed ? result : source;
    }

    public static FormattedText formatted(FormattedText source) {
        if (source instanceof Component component) return component(component);
        if (source == null || !allowed()) return source;
        BUSY.set(true);
        try {
            List<Run> runs = collect(source);
            List<Run> translated = translateRuns(runs);
            if (translated == runs) return source;
            List<FormattedText> parts = new ArrayList<>(translated.size());
            for (Run run : translated) parts.add(FormattedText.of(run.text.toString(), run.style));
            return FormattedText.composite(parts);
        } finally { BUSY.set(false); }
    }

    public static FormattedCharSequence sequence(FormattedCharSequence source) {
        if (source == null || !allowed()) return source;
        BUSY.set(true);
        try {
            long revision = TranslationService.revision();
            synchronized (SEQUENCES) {
                SequenceResult cached = SEQUENCES.get(source);
                if (cached != null && cached.revision == revision) return cached.result;
            }
            List<Run> runs = new ArrayList<>();
            source.accept((index, style, codePoint) -> {
                Run run = runs.isEmpty() ? null : runs.get(runs.size() - 1);
                if (run == null || !run.style.equals(style)) {
                    run = new Run(style);
                    runs.add(run);
                }
                run.text.appendCodePoint(codePoint);
                return true;
            });
            List<Run> translated = translateRuns(runs);
            List<FormattedCharSequence> translatedRuns = new ArrayList<>(translated.size());
            for (Run run : translated) translatedRuns.add(FormattedCharSequence.forward(run.text.toString(), run.style));
            FormattedCharSequence result = translated != runs ? FormattedCharSequence.composite(translatedRuns) : source;
            synchronized (SEQUENCES) {
                if (SEQUENCES.size() >= CACHE_LIMIT) SEQUENCES.clear();
                SEQUENCES.put(source, new SequenceResult(revision, result));
            }
            return result;
        } finally { BUSY.set(false); }
    }

    private static boolean literalTree(Component component, int depth) {
        if (depth > 64) return false;
        if (!(component.getContents() instanceof PlainTextContents)) {
            if (!(component.getContents() instanceof TranslatableContents translated)) return false;
            for(Object argument:translated.getArgs()) if(argument instanceof Component nested&&!literalTree(nested,depth+1)) return false;
        }
        for (Component sibling : component.getSiblings()) if (!literalTree(sibling, depth + 1)) return false;
        return true;
    }

    private static List<Run> collect(FormattedText source) {
        List<Run> runs = new ArrayList<>();
        source.visit((style, text) -> {
            if (!text.isEmpty()) {
                Run run = runs.isEmpty() ? null : runs.get(runs.size() - 1);
                if (run == null || !run.style.equals(style)) {
                    run = new Run(style);
                    runs.add(run);
                }
                run.text.append(text);
            }
            return Optional.empty();
        }, Style.EMPTY);
        return runs;
    }

    private static List<Run> translateRuns(List<Run> runs) {
        if (runs.isEmpty()) return runs;
        if (runs.size() == 1) {
            Run source = runs.get(0);
            String text = source.text.toString();
            String translated = TranslationService.translateLiteral(text);
            if (text.equals(translated)) return runs;
            Run result = new Run(source.style);
            result.text.append(translated);
            return List.of(result);
        }
        String tagged = StyledTextCodec.encode(runs.stream().map(run -> run.text.toString()).toList());
        String translated = TranslationService.translateStyled(tagged);
        if (tagged.equals(translated)) return runs;
        List<StyledTextCodec.Span> spans = StyledTextCodec.decode(translated, runs.size());
        if (spans == null) return runs; // Never show protocol tags or drop interactive spans.
        List<Run> result = new ArrayList<>(spans.size());
        for (StyledTextCodec.Span span : spans) {
            Run run = new Run(runs.get(span.index()).style);
            run.text.append(span.text());
            result.add(run);
        }
        return result;
    }

    private static final class Run {
        final Style style;
        final StringBuilder text = new StringBuilder();
        Run(Style style) { this.style = style; }
    }
    private record SequenceResult(long revision, FormattedCharSequence result) {}
}
