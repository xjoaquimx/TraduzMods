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
    private GuideMePageRefresh() {}
    public static void refresh(Object screen) {
        State state = SCREENS.computeIfAbsent(screen, ignored -> new State());
        if (state.disabled) return;
        long revision = TranslationService.revision(), now = System.nanoTime();
        try {
            ScreenAccess access = SCREENS_ACCESS.get(screen.getClass());
            Object document = access.document.invoke(screen);
            if (document == null) return;
            boolean newDocument = state.document.get() != document;
            if (!newDocument && (revision == state.revision || now < state.nextRefresh)) return;
            state.document = new WeakReference<>(document); state.revision = revision;
            state.nextRefresh = now + 500_000_000L;
            if (update(document, 0)) {
                Method invalidate = method(document.getClass(), "invalidateLayout");
                if (invalidate == null) throw new NoSuchMethodException("Guide layout invalidation");
                invalidate.invoke(document); access.invalid.setBoolean(screen, true);
            }
        } catch (ReflectiveOperationException | RuntimeException incompatible) {
            state.disabled = true; // One incompatible optional version cannot fail the render loop.
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
            String encoded = StyledTextCodec.encode(block.originals);
            String translated = TranslationService.translateModDocument(null, encoded);
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
    private static final class NodeAccess {
        final Method content, children, text, setText;
        NodeAccess(Class<?> type) {
            content = method(type,"getContent"); children = method(type,"getChildren");
            text = method(type,"getText"); setText = method(type,"setText",String.class);
        }
    }
    private static final class ScreenAccess {
        final Method document; final Field invalid;
        ScreenAccess(Class<?> type) throws ReflectiveOperationException {
            Method found = null; Field flag = null;
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                if (found == null) try { found = current.getDeclaredMethod("getDocument"); } catch (NoSuchMethodException absent) {}
                if (flag == null) try { flag = current.getDeclaredField("documentLayoutInvalid"); } catch (NoSuchFieldException absent) {}
            }
            if (found == null || flag == null || !found.trySetAccessible() || !flag.trySetAccessible()) throw new NoSuchMethodException("Guide document API");
            document = found; invalid = flag;
        }
    }
    private static final class Block {
        final List<String> originals = new ArrayList<>();
        final List<WeakReference<Object>> leaves = new ArrayList<>();
        Block(List<Object> objects) throws ReflectiveOperationException {
            for (Object leaf : objects) { leaves.add(new WeakReference<>(leaf)); originals.add((String) NODES.get(leaf.getClass()).text.invoke(leaf)); }
        }
        boolean matches(List<Object> objects) {
            if (objects.size() != leaves.size()) return false;
            for (int i = 0; i < objects.size(); i++) if (leaves.get(i).get() != objects.get(i)) return false;
            return true;
        }
    }
    private static final class State {
        WeakReference<Object> document = new WeakReference<>(null);
        long revision = -1, nextRefresh; boolean disabled;
    }
}
