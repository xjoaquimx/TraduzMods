package com.modtranslator.client;

import com.modtranslator.core.TranslationService;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

/** Optional Aether document cache; rebuild only changed text, never reinitialize the screen. */
public final class AetherPageRefresh {
    private static final WeakHashMap<Object, State> STATES = new WeakHashMap<>();
    private static final ClassValue<Access> ACCESS = new ClassValue<>() {
        protected Access computeValue(Class<?> type) {
            try { return new Access(type); }
            catch (ReflectiveOperationException incompatible) { throw new IllegalStateException(incompatible); }
        }
    };
    private AetherPageRefresh() {}
    public static Component capture(Object screen, Component original) {
        State previous = STATES.get(screen);
        if (previous != null && previous.rebuilding) return original;
        if (!original.getSiblings().isEmpty()) return original;
        String key = null;
        if (original.getContents() instanceof TranslatableContents contents) {
            if (contents.getArgs().length != 0) return original;
            key = contents.getKey();
        }
        String displayed = TranslationService.translateModDocument(key, original.getString());
        STATES.put(screen, new State(original, key, displayed));
        return Component.literal(displayed).setStyle(original.getStyle());
    }
    public static void refresh(Object screen) {
        State state = STATES.get(screen);
        if (state == null || state.rebuilding) return;
        long revision = TranslationService.revision(), now = System.nanoTime();
        if (revision == state.revision || now < state.nextRefresh) return;
        state.revision = revision; state.nextRefresh = now + 500_000_000L;
        String displayed = TranslationService.translateModDocument(state.key, state.original.getString());
        if (displayed.equals(state.displayed)) return;
        try {
            Access access = ACCESS.get(screen.getClass());
            Map<?, ?> pages = (Map<?, ?>) access.pages.get(screen);
            int selected = access.page.getInt(screen);
            state.rebuilding = true;
            pages.clear();
            access.create.invoke(screen, Component.literal(displayed).setStyle(state.original.getStyle()));
            access.page.setInt(screen, Math.max(0, Math.min(selected, pages.size() - 1)));
            state.displayed = displayed;
        } catch (ReflectiveOperationException | RuntimeException incompatible) {
            STATES.remove(screen); // Disable an incompatible optional adapter once, without render-loop retries.
        } finally { state.rebuilding = false; }
    }
    private static final class State {
        final Component original; final String key;
        String displayed; boolean rebuilding; long revision = -1, nextRefresh;
        State(Component original, String key, String displayed) {
            this.original = original; this.key = key; this.displayed = displayed;
        }
    }
    private static final class Access {
        final Field pages, page; final Method create;
        Access(Class<?> type) throws ReflectiveOperationException {
            pages = type.getDeclaredField("pages"); page = type.getDeclaredField("currentPageNumber");
            create = type.getDeclaredMethod("createPages", Component.class);
            if (!pages.trySetAccessible() || !page.trySetAccessible() || !create.trySetAccessible())
                throw new IllegalAccessException("Optional screen cache inaccessible");
        }
    }
}
