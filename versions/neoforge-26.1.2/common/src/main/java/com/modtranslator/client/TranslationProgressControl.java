package com.modtranslator.client;

/** Shared GUI-space bounds. Input cannot activate an invisible button or steal world clicks. */
public final class TranslationProgressControl {
    private TranslationProgressControl() {}
    public record Bounds(int x,int y,int width,int height) {
        public boolean contains(double mouseX,double mouseY){return mouseX>=x&&mouseX<x+width&&mouseY>=y&&mouseY<y+height;}
    }
    public static int panelWidth(int guiWidth){return Math.min(260,guiWidth-12);}
    public static Bounds bounds(int guiWidth,int guiHeight){return guiWidth<120||guiHeight<72?null:new Bounds(6+panelWidth(guiWidth)-95,11,64,20);}
    public static Bounds cacheBounds(int guiWidth,int guiHeight){return guiWidth<120||guiHeight<96?null:new Bounds(11,68,panelWidth(guiWidth)-10,20);}
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
