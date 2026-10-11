package com.modtranslator.client;

import com.modtranslator.core.TranslationService;

/** Render-thread-only presentation of an immutable worker snapshot. No IO or provider operations. */
public final class TranslationProgressLayout {
    public record View(String title,String detail,double fraction,int color,boolean indeterminate,String reason,long sessionCompleted) {
        public View(String title,String detail,double fraction,int color,boolean indeterminate){this(title,detail,fraction,color,indeterminate,"",0);}
    }
    private static final long COMPLETION_NANOS = 5_000_000_000L;
    private static long completionUntil;
    private static String previousPhase = "idle";
    private static View completion;
    private static TranslationService.TranslationProgress previousProgress;
    private static View activeView;
    private static boolean observedActive;
    private static TranslationService.TranslationProgress menuProgress;
    private static View menuView;
    private TranslationProgressLayout() {}

    public static View view(TranslationService.TranslationProgress progress, long now) {
        if (!progress.enabled()) {
            completionUntil=0; completion=null; previousPhase="disabled"; previousProgress=null; activeView=null; observedActive=false; return null;
        }
        String phase=progress.phase();
        if (progress.active()) {
            observedActive=true;
            previousPhase=phase; completionUntil=0; completion=null;
            if(previousProgress==progress && activeView!=null)return activeView;
            previousProgress=progress;
            String title=switch (phase) {
                case "starting" -> "Preparando tradução";
                case "user_paused" -> "Pausado por você";
                case "paused" -> progress.reason().startsWith("VRAM")?"Aguardando memória da GPU":progress.reason().startsWith("RAM")?"Aguardando memória":"Tradução pausada";
                case "waiting" -> "Aguardando tradução";
                case "translating" -> progress.reason().startsWith("Prioridade")?"Traduzindo texto aberto":"Traduzindo textos";
                default -> "Traduzindo textos";
            };
            long done=Math.max(0,progress.completed()), total=Math.max(done,progress.total());
            String detail=total>0?compact(done)+" de "+compact(total):"Lendo textos…";
            double fraction=total>0?Math.min(1D,(double)done/total):0D;
            activeView=new View(title,detail,fraction,("user_paused".equals(phase)||"paused".equals(phase)||"waiting".equals(phase))?0xFFE6B65C:0xFF66D9C6,total==0 && !"paused".equals(phase) && !"user_paused".equals(phase),progress.reason(),progress.sessionCompleted());
            return activeView;
        }
        if ("completed".equals(phase) && !"completed".equals(previousPhase) && observedActive) {
            observedActive=false;
            completionUntil=now+COMPLETION_NANOS;
            long done=Math.max(0,progress.completed());
            completion=new View("Tradução concluída",compact(done)+(done==1?" texto traduzido":" textos traduzidos"),1D,0xFF80D18C,false,"Tudo salvo em disco.",progress.sessionCompleted());
        }
        previousPhase=phase;
        if (completion!=null && now<completionUntil) return completion;
        completion=null;
        return null;
    }
    /** Keep display controls reachable in an open menu after the completion notification expires. */
    public static View controlsView(TranslationService.TranslationProgress progress) {
        if(!progress.enabled())return null;
        if(menuProgress==progress&&menuView!=null)return menuView;
        menuProgress=progress;
        long done=Math.max(0,progress.completed()),total=Math.max(done,progress.total());
        menuView=new View("Traduções salvas",total>0?compact(done)+" de "+compact(total):"Cache disponível",total>0?(double)done/total:0D,0xFFE0E0E0,false,"Cache pronto para reutilizar",progress.sessionCompleted());
        return menuView;
    }
    private static String compact(long value) {
        String digits=Long.toString(value);
        if(digits.length()<=3)return digits;
        StringBuilder grouped=new StringBuilder(digits.length()+6);
        for(int i=0;i<digits.length();i++) {
            if(i>0 && (digits.length()-i)%3==0)grouped.append('.');
            grouped.append(digits.charAt(i));
        }
        return grouped.toString();
    }
}
