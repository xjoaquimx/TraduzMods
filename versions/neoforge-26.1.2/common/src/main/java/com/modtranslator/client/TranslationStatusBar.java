package com.modtranslator.client;

import com.modtranslator.core.TranslationService;

/** Compact HUD counters include queued background work, even in on-demand mode. */
public final class TranslationStatusBar {
    public static final int BACKGROUND=0xED302D29, BORDER=0xFF8E8067, TEXT=0xFFF1EBDD;
    public static final int MUTED=0xFFB8B1A4, TRACK=0xFFDFD8C8, TEAL=0xFF39B7A9, GREEN=0xFF92AE82;
    public record Counters(long saved,long queued,long session,long total,int percent) {
        public String savedText(){return number(saved)+"/"+number(total);}
        public String queueText(){return number(queued)+" na fila";}
        public String sessionText(){return "+"+number(session)+" novos";}
    }
    private TranslationStatusBar() {}
    public static Counters counters(TranslationService.TranslationProgress p) {
        long saved=Math.max(0,p.completed()),queued=Math.max(0,p.pending());
        long sum=saved>Long.MAX_VALUE-queued?Long.MAX_VALUE:saved+queued;
        long total=Math.max(sum,Math.max(0,p.total()));
        int percent=total==0?0:(int)Math.min(queued>0?99:100,Math.floor(100D*saved/total));
        return new Counters(saved,queued,Math.max(0,p.sessionCompleted()),total,Math.max(0,percent));
    }
    public static String number(long value) {
        String digits=Long.toString(Math.max(0,value));StringBuilder out=new StringBuilder();
        for(int i=0;i<digits.length();i++){if(i>0&&(digits.length()-i)%3==0)out.append('.');out.append(digits.charAt(i));}
        return out.toString();
    }
}
