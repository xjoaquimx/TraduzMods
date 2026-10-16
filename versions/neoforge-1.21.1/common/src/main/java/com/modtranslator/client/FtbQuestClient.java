package com.modtranslator.client;

import com.modtranslator.core.TranslationService;
import java.lang.reflect.Method;

/** Optional integration without a hard FTB dependency. Only presentation getters are intercepted. */
public final class FtbQuestClient {
    private FtbQuestClient() {}
    private static final ClassValue<Method> QUEST_FILE = new ClassValue<>() {
        protected Method computeValue(Class<?> type) {
            try { Method method = type.getMethod("getQuestFile"); method.trySetAccessible(); return method; }
            catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }
    };
    private static final ClassValue<Method> SERVER_SIDE = new ClassValue<>() {
        protected Method computeValue(Class<?> type) {
            try { Method method = type.getMethod("isServerSide"); method.trySetAccessible(); return method; }
            catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }
    };
    public static boolean isClient(Object quest) {
        try {
            Object file = QUEST_FILE.get(quest.getClass()).invoke(quest);
            return file != null && Boolean.FALSE.equals(SERVER_SIDE.get(file.getClass()).invoke(file));
        } catch (ReflectiveOperationException | RuntimeException incompatibleVersion) { return false; }
    }
    public static String translate(Object quest, String source) {
        return TranslationService.isPortugueseActive() && isClient(quest) ? com.modtranslator.core.FtbTextSafety.escapeLiteralAmpersands(TranslationService.translateQuest(source)) : source;
    }
}
