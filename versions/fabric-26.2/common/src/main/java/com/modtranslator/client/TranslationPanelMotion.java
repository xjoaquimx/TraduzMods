package com.modtranslator.client;

/** Small render-thread animation; preserves continuity on reversal and never owns translation work. */
public final class TranslationPanelMotion {
    private static final long DURATION=220_000_000L;
    private static boolean initialized,target;
    private static long started;
    private static double from;
    public record Frame(int panelX,TranslationProgressControl.Bounds tab,boolean expandedInput,boolean collapsed) {}
    private TranslationPanelMotion() {}
    public static void reset(){initialized=false;from=0;target=false;started=0;}
    private static double value(long now){double t=Math.max(0D,Math.min(1D,(double)(now-started)/DURATION));double ease=1D-Math.pow(1D-t,3D);return from+((target?1D:0D)-from)*ease;}
    public static Frame frame(boolean minimized,int guiWidth,int guiHeight,long now){
        if(!initialized){initialized=true;target=minimized;from=minimized?1D:0D;started=now;}
        if(target!=minimized){from=value(now);target=minimized;started=now;}
        double amount=value(now);
        int width=TranslationProgressControl.panelWidth(guiWidth);
        int panelX=6-(int)Math.round((width+7)*amount);
        TranslationProgressControl.Bounds tab=guiWidth<120||guiHeight<87+TranslationProgressControl.verticalOffset()?null:new TranslationProgressControl.Bounds((int)Math.round((6+width-23)*(1D-amount)),11+TranslationProgressControl.verticalOffset(),18,20);
        return new Frame(panelX,tab,!target&&amount==0D,target&&amount==1D);
    }
}
