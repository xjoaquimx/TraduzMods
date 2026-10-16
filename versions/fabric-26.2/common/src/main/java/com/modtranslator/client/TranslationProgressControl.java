package com.modtranslator.client;

/** Shared GUI-space bounds. Input cannot activate an invisible button or steal world clicks. */
public final class TranslationProgressControl {
    private TranslationProgressControl() {}
    public static int verticalOffset(){return com.modtranslator.core.TranslationService.isModInstalled("agentcraft")?48:0;}
    public record Bounds(int x,int y,int width,int height) {
        public boolean contains(double mouseX,double mouseY){return mouseX>=x&&mouseX<x+width&&mouseY>=y&&mouseY<y+height;}
    }
    public static int panelWidth(int guiWidth){return Math.min(300,guiWidth-12);}
    public static Bounds bounds(int guiWidth,int guiHeight){return guiWidth<120||guiHeight<87+verticalOffset()?null:new Bounds(11,61+verticalOffset(),80,20);}
    public static Bounds cacheBounds(int guiWidth,int guiHeight){return guiWidth<120||guiHeight<117+verticalOffset()?null:new Bounds(11,85+verticalOffset(),panelWidth(guiWidth)-10,20);}
    public static boolean hitCache(boolean screenOpen,boolean hidden,boolean viewVisible,int guiWidth,int guiHeight,double mouseX,double mouseY) {
        Bounds button=cacheBounds(guiWidth,guiHeight);
        return screenOpen&&!hidden&&viewVisible&&button!=null&&button.contains(mouseX,mouseY);
    }
    public static boolean hitMinimize(boolean screenOpen,boolean hidden,boolean viewVisible,TranslationPanelMotion.Frame frame,double mouseX,double mouseY) {
        return screenOpen&&!hidden&&viewVisible&&frame!=null&&frame.tab()!=null&&frame.tab().contains(mouseX,mouseY);
    }
    public static boolean hit(boolean screenOpen,boolean hidden,boolean viewVisible,int guiWidth,int guiHeight,double mouseX,double mouseY) {
        Bounds button=bounds(guiWidth,guiHeight);
        return screenOpen&&!hidden&&viewVisible&&button!=null&&button.contains(mouseX,mouseY);
    }
}
