package com.modtranslator.client;

import com.modtranslator.core.TranslationService;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

/** Refresh immutable quest text widgets after async translation, retaining scroll and the current page. */
public final class FtbQuestViewRefresh {
    private FtbQuestViewRefresh() {}
    private static final WeakHashMap<Object, State> STATES = new WeakHashMap<>();
    private static final ClassValue<Access> ACCESS = new ClassValue<>() {
        protected Access computeValue(Class<?> type) {
            try { return new Access(type); }
            catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }
    };

    public static void refresh(Object view) {
        if (!TranslationService.isPortugueseActive()) return;
        try {
            Access access = ACCESS.get(view.getClass());
            Object quest = access.quest.get(view);
            if (quest == null || !FtbQuestClient.isClient(quest)) return;
            long revision = TranslationService.revision(), now = System.nanoTime();
            State state = STATES.computeIfAbsent(view, ignored -> new State(revision, now));
            if (state.revision == revision || now - state.lastRefresh < 1_000_000_000L) return;
            Object file = quest.getClass().getMethod("getQuestFile").invoke(quest);
            // canEdit requires both editor permission and the player's active edit mode in this FTB version.
            if (Boolean.TRUE.equals(file.getClass().getMethod("canEdit").invoke(file))) return;
            if (Boolean.TRUE.equals(access.mouseDown.invoke(null, access.left))
                    || Boolean.TRUE.equals(access.mouseDown.invoke(null, access.right))) return;
            List<Scroll> scroll = new ArrayList<>();
            for (Field field : access.panels) {
                Object panel = field.get(view);
                if (panel != null) scroll.add(new Scroll(field,
                        ((Number)panel.getClass().getMethod("getScrollX").invoke(panel)).doubleValue(),
                        ((Number)panel.getClass().getMethod("getScrollY").invoke(panel)).doubleValue()));
            }
            access.refresh.invoke(view);
            for (Scroll position : scroll) {
                Object panel = position.field.get(view);
                if (panel != null) {
                    panel.getClass().getMethod("setScrollX", double.class).invoke(panel, position.x);
                    panel.getClass().getMethod("setScrollY", double.class).invoke(panel, position.y);
                }
            }
            state.revision = revision;
            state.lastRefresh = now;
        } catch (ReflectiveOperationException | RuntimeException incompatibleVersion) {
            // Optional adapter fails closed when the installed FTB API differs.
        }
    }
    private static Field field(Class<?> type, String name) throws ReflectiveOperationException {
        Field result = type.getDeclaredField(name); result.trySetAccessible(); return result;
    }
    private static final class Access {
        final Field quest;
        final List<Field> panels;
        final Method refresh, mouseDown;
        final Object left, right;
        Access(Class<?> type) throws ReflectiveOperationException {
            quest = field(type, "quest");
            panels = List.of(field(type, "panelContent"), field(type, "panelTasks"), field(type, "panelRewards"), field(type, "panelText"));
            refresh = type.getMethod("refreshWidgets");
            ClassLoader loader = type.getClassLoader();
            Class<?> mouse = Class.forName("dev.ftb.mods.ftblibrary.ui.input.MouseButton", false, loader);
            Class<?> widget = Class.forName("dev.ftb.mods.ftblibrary.ui.Widget", false, loader);
            mouseDown = widget.getMethod("isMouseButtonDown", mouse);
            left = mouse.getField("LEFT").get(null);
            right = mouse.getField("RIGHT").get(null);
        }
    }
    private record Scroll(Field field, double x, double y) {}
    private static final class State {
        long revision, lastRefresh;
        State(long revision, long now) { this.revision = revision; lastRefresh = now; }
    }
}
