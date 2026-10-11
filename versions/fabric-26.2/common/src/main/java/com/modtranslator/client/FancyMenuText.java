package com.modtranslator.client;
import com.modtranslator.core.TranslationService;
/** Whole static display prose before FancyMenu splits/wraps it. Complex markup remains untouched. */
public final class FancyMenuText {
    private FancyMenuText() {}
    public static boolean plainProse(String source) {
        return source!=null&&!source.isBlank()&&!java.util.regex.Pattern.compile("[\\[\\]<>`*_#|]|https?://").matcher(source).find();
    }
    public static String translate(String source) {
        if(!plainProse(source))return source;
        boolean input=StackWalker.getInstance().walk(frames->frames.limit(48).anyMatch(frame->{String name=frame.getClassName().toLowerCase(java.util.Locale.ROOT);return name.contains("textinput")||name.contains("editbox")||name.contains("editor")||name.contains("chatscreen");}));
        return input?source:TranslationService.translateQuest(source);
    }
}
