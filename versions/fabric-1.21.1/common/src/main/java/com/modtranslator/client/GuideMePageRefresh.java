package com.modtranslator.client;

import com.modtranslator.core.TranslationService;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.WeakHashMap;

/** Translate flow nodes before their layout. Link/style objects and document structure remain intact. */
public final class GuideMePageRefresh {
    private static final System.Logger LOG = System.getLogger("TraduzMods/GuideMe");
    private static final WeakHashMap<Object, State> SCREENS = new WeakHashMap<>();
    private static final WeakHashMap<Object, Block> BLOCKS = new WeakHashMap<>();
    private static final ClassValue<NodeAccess> NODES = new ClassValue<>() {
        protected NodeAccess computeValue(Class<?> type) { return new NodeAccess(type); }
    };
    private static final ClassValue<ScreenAccess> SCREENS_ACCESS = new ClassValue<>() {
        protected ScreenAccess computeValue(Class<?> type) {
            try { return new ScreenAccess(type); }
            catch (ReflectiveOperationException incompatible) { throw new IllegalStateException(incompatible); }
        }
    };
    private static final ClassValue<NavAccess> NAVIGATION = new ClassValue<>() {
        protected NavAccess computeValue(Class<?> type) { return new NavAccess(type); }
    };
    private static final ClassValue<Field> ROW_PARAGRAPHS = new ClassValue<>() {
        protected Field computeValue(Class<?> type) { return field(type, "paragraph"); }
    };
    private GuideMePageRefresh() {}
    public static void refresh(Object screen) {
        State state = SCREENS.computeIfAbsent(screen, ignored -> new State());
        if (state.disabled || state.rebuilding) return;
        long revision = TranslationService.revision(), now = System.nanoTime();
        try {
            ScreenAccess access = SCREENS_ACCESS.get(screen.getClass());
            Object document = access.document.invoke(screen);
            if (document == null) return;
            boolean newDocument = state.document.get() != document;
            if (!newDocument && now < state.nextRefresh) return;
            Object navbar = access.navbar == null ? null : access.navbar.get(screen);
            List<?> rows = navbar == null ? List.of() : NAVIGATION.get(navbar.getClass()).rows(navbar);
            boolean newRows = !state.matchesRows(rows);
            state.nextRefresh = now + 500_000_000L;
            if (!newDocument && revision == state.revision && !newRows) return;
            state.document = new WeakReference<>(document); state.revision = revision;
            state.rememberRows(rows);
            state.rebuilding = true;
            if (update(document, 0)) {
                Method invalidate = method(document.getClass(), "invalidateLayout");
                if (invalidate == null) throw new NoSuchMethodException("Guide layout invalidation");
                invalidate.invoke(document); access.invalid.setBoolean(screen, true);
            }
            boolean navigationChanged = false;
            for (Object row : rows) {
                Field paragraph = ROW_PARAGRAPHS.get(row.getClass());
                if (paragraph != null) navigationChanged |= update(paragraph.get(row), 0);
            }
            if (navigationChanged) NAVIGATION.get(navbar.getClass()).layout.invoke(navbar);
            // GuideScreen removes the first heading from the document into a separate title paragraph.
            if (access.title != null && update(access.title.get(screen), 0) && access.layout != null)
                access.layout.invoke(screen);
        } catch (ReflectiveOperationException | RuntimeException incompatible) {
            state.disabled = true; // One incompatible optional version cannot fail the render loop.
            LOG.log(System.Logger.Level.WARNING, "GuideMe live translation disabled for " + screen.getClass().getName(), incompatible);
        } finally {
            state.rebuilding = false;
        }
    }
    private static boolean update(Object node, int depth) throws ReflectiveOperationException {
        if (node == null || depth > 64 || node.getClass().getName().toLowerCase(java.util.Locale.ROOT).contains("code")) return false;
        NodeAccess access = NODES.get(node.getClass());
        if (access.content != null) {
            List<Object> leaves = new ArrayList<>();
            for (Object child : (Iterable<?>) access.content.invoke(node)) collect(child, leaves, 0);
            if (leaves.isEmpty()) return false;
            Block block = BLOCKS.get(node);
            if (block == null || !block.matches(leaves)) { block = new Block(leaves); BLOCKS.put(node, block); }
            String translated = TranslationService.translateModDocument(null, block.encoded);
            List<StyledTextCodec.Span> spans = StyledTextCodec.decode(translated, leaves.size());
            if (spans == null) return false;
            // A fixed flow tree cannot reorder its link/style nodes. Reject a reordered protocol result.
            for (int i = 0; i < spans.size(); i++) if (spans.get(i).index() != i) return false;
            boolean changed = false;
            for (StyledTextCodec.Span span : spans) {
                Object leaf = leaves.get(span.index()); NodeAccess text = NODES.get(leaf.getClass());
                if (!Objects.equals(text.text.invoke(leaf), span.text())) {
                    text.setText.invoke(leaf, span.text()); changed = true;
                }
            }
            return changed;
        }
        boolean changed = false;
        if (access.children != null) for (Object child : (Iterable<?>) access.children.invoke(node)) changed |= update(child, depth + 1);
        return changed;
    }
    private static void collect(Object node, List<Object> leaves, int depth) throws ReflectiveOperationException {
        if (node == null || depth > 64) return;
        NodeAccess access = NODES.get(node.getClass());
        if (access.text != null && access.setText != null) leaves.add(node);
        else if (access.children != null) for (Object child : (Iterable<?>) access.children.invoke(node)) collect(child, leaves, depth + 1);
    }
    private static Method method(Class<?> type, String name, Class<?>... arguments) {
        try { Method m = type.getMethod(name, arguments); return m.trySetAccessible() ? m : null; }
        catch (ReflectiveOperationException absent) { return null; }
    }
    private static Field field(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass())
            try { Field f = current.getDeclaredField(name); return f.trySetAccessible() ? f : null; }
            catch (NoSuchFieldException absent) {}
        return null;
    }
    private static Method declaredMethod(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass())
            try { Method m = current.getDeclaredMethod(name); return m.trySetAccessible() ? m : null; }
            catch (NoSuchMethodException absent) {}
        return null;
    }
    private static final class NodeAccess {
        final Method content, children, text, setText;
        NodeAccess(Class<?> type) {
            content = method(type,"getContent"); children = method(type,"getChildren");
            text = method(type,"getText"); setText = method(type,"setText",String.class);
        }
    }
    private static final class ScreenAccess {
        final Method document, layout; final Field invalid, title, navbar;
        ScreenAccess(Class<?> type) throws ReflectiveOperationException {
            Method found = null; Field flag = null;
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                if (found == null) try { found = current.getDeclaredMethod("getDocument"); } catch (NoSuchMethodException absent) {}
                if (flag == null) try { flag = current.getDeclaredField("documentLayoutInvalid"); } catch (NoSuchFieldException absent) {}
            }
            if (found == null || flag == null || !found.trySetAccessible() || !flag.trySetAccessible()) throw new NoSuchMethodException("Guide document API");
            document = found; invalid = flag;
            title = field(type, "pageTitle"); navbar = field(type, "navbar");
            layout = declaredMethod(type, "updateScreenLayout");
        }
    }
    private static final class NavAccess {
        final Field rows; final Method layout;
        NavAccess(Class<?> type) { rows = field(type, "rows"); layout = declaredMethod(type, "updateLayout"); }
        List<?> rows(Object navbar) throws IllegalAccessException {
            return rows == null || layout == null ? List.of() : (List<?>) rows.get(navbar);
        }
    }
    private static final class Block {
        final List<String> originals = new ArrayList<>();
        final List<WeakReference<Object>> leaves = new ArrayList<>();
        final String encoded;
        Block(List<Object> objects) throws ReflectiveOperationException {
            for (Object leaf : objects) { leaves.add(new WeakReference<>(leaf)); originals.add((String) NODES.get(leaf.getClass()).text.invoke(leaf)); }
            encoded = StyledTextCodec.encode(originals);
        }
        boolean matches(List<Object> objects) {
            if (objects.size() != leaves.size()) return false;
            for (int i = 0; i < objects.size(); i++) if (leaves.get(i).get() != objects.get(i)) return false;
            return true;
        }
    }
    private static final class State {
        WeakReference<Object> document = new WeakReference<>(null);
        final List<WeakReference<Object>> rows = new ArrayList<>();
        long revision = -1, nextRefresh; boolean disabled, rebuilding;
        boolean matchesRows(List<?> current) {
            if (rows.size() != current.size()) return false;
            for (int i = 0; i < rows.size(); i++) if (rows.get(i).get() != current.get(i)) return false;
            return true;
        }
        void rememberRows(List<?> current) {
            rows.clear(); for (Object row : current) rows.add(new WeakReference<>(row));
        }
    }
}
