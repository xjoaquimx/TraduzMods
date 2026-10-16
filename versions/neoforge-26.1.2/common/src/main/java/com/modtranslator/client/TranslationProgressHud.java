package com.modtranslator.client;

import com.modtranslator.core.TranslationService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** Native Minecraft button/sprites; cached widget, snapshot-only rendering, no provider calls. */
public final class TranslationProgressHud {
    private static final Component PAUSE=Component.literal("Pausar"),RESUME=Component.literal("Retomar");
    private static final Component CACHE_ON=Component.literal("Traduções salvas: Ligadas"),CACHE_OFF=Component.literal("Traduções salvas: Desligadas");
    private static final Component COLLAPSE=Component.literal("<"),EXPAND=Component.literal(">");
    private static Button control,cacheControl,minimizeControl;
    private static boolean lastMinimized;
    private static boolean lastCacheDisplay;
    private static boolean lastPaused;
    private static TranslationProgressLayout.View lastView;
    private static String savedText="",sessionText="";
    private TranslationProgressHud() {}
    public static TranslationProgressLayout.View currentView() {
        var progress=TranslationService.translationProgress();
        var view=TranslationProgressLayout.view(progress,System.nanoTime());
        var game=Minecraft.getInstance();
        return view!=null?view:(game.screen!=null||TranslationService.isTranslationPanelMinimized()?TranslationProgressLayout.controlsView(progress):null);
    }
    public static TranslationPanelMotion.Frame currentPlacement(){
        var window=Minecraft.getInstance().getWindow();
        return TranslationPanelMotion.frame(TranslationService.isTranslationPanelMinimized(),window.getGuiScaledWidth(),window.getGuiScaledHeight(),System.nanoTime());
    }
    public static void render(GuiGraphicsExtractor graphics) {render(graphics,currentView());}
    public static void render(GuiGraphicsExtractor graphics,TranslationProgressLayout.View view) {
        var game=Minecraft.getInstance();
        if(view==null||game.options.hideGui||graphics.guiWidth()<120||graphics.guiHeight()<87+TranslationProgressControl.verticalOffset())return;
        var placement=TranslationPanelMotion.frame(TranslationService.isTranslationPanelMinimized(),graphics.guiWidth(),graphics.guiHeight(),System.nanoTime());
        int x=placement.panelX(),y=6+TranslationProgressControl.verticalOffset(),width=TranslationProgressControl.panelWidth(graphics.guiWidth()),inner=width-10;
        var button=game.screen!=null?TranslationProgressControl.bounds(graphics.guiWidth(),graphics.guiHeight()):null;
        graphics.nextStratum();
        var cacheButton=game.screen!=null?TranslationProgressControl.cacheBounds(graphics.guiWidth(),graphics.guiHeight()):null;
        int panelHeight=cacheButton!=null?105:button!=null?82:44;
        var counters=TranslationStatusBar.counters(TranslationService.translationProgress());
        graphics.fill(x,y,x+width,y+panelHeight,TranslationStatusBar.BACKGROUND);
        graphics.fill(x,y,x+width,y+1,TranslationStatusBar.BORDER);
        graphics.fill(x,y,x+1,y+panelHeight,TranslationStatusBar.BORDER);
        graphics.fill(x+width-1,y,x+width,y+panelHeight,0xFF171612);
        graphics.fill(x,y+panelHeight-1,x+width,y+panelHeight,0xFF171612);
        graphics.fill(x+6,y+6,x+10,y+12,view.color());
        graphics.fill(x+5,y+7,x+11,y+11,view.color());
        String percent=view.indeterminate()?"…":counters.percent()+"%";
        graphics.text(game.font,game.font.plainSubstrByWidth("TraduzMods · "+view.title(),inner-77),x+15,y+5,TranslationStatusBar.TEXT,true);
        graphics.text(game.font,percent,x+width-28-game.font.width(percent),y+5,view.color(),true);
        graphics.fill(x+5,y+19,x+width-5,y+24,TranslationStatusBar.TRACK);
        double fraction=counters.total()>0?(double)counters.saved()/counters.total():view.fraction();
        int amount=Math.max(0,Math.min(inner,(int)Math.round(inner*fraction)));
        if(view.indeterminate()){int segment=Math.max(8,inner/4),travel=inner-segment,offset=(int)((Math.sin(System.nanoTime()/600_000_000D)+1D)*0.5D*travel);graphics.fill(x+5+offset,y+19,x+5+offset+segment,y+24,view.color());}
        else if(amount>0)graphics.fill(x+5,y+19,x+5+amount,y+24,view.color());
        String saved=counters.savedText(),queued=counters.queueText(),session=counters.sessionText();
        int savedX=x+width-5-game.font.width(saved),leftWidth=Math.max(0,savedX-x-19);
        graphics.fill(x+6,y+31,x+10,y+35,TranslationStatusBar.TEAL);
        graphics.text(game.font,game.font.plainSubstrByWidth(queued,leftWidth),x+13,y+29,TranslationStatusBar.MUTED,true);
        int middleX=x+16+game.font.width(queued);
        if(middleX+game.font.width(session)+12<savedX)graphics.text(game.font,session,middleX+8,y+29,TranslationStatusBar.MUTED,true);
        graphics.text(game.font,saved,savedX,y+29,TranslationStatusBar.GREEN,true);
        if(button!=null){
            graphics.fill(x+5,y+42,x+width-5,y+43,0xFF61594C);
            graphics.text(game.font,game.font.plainSubstrByWidth(view.reason(),inner),x+5,y+46,0xFFE1C58B,true);
            String edition=com.modtranslator.core.TranslationEdition.current()==com.modtranslator.core.TranslationEdition.NORMAL?"Normal":"Lite";
            graphics.text(game.font,edition+" · salvo em disco",x+94,y+62,TranslationStatusBar.MUTED,true);
        }
        if(button!=null&&placement.expandedInput()){
            boolean paused=TranslationService.isTranslationPaused();
            if(control==null){control=Button.builder(paused?RESUME:PAUSE,b->TranslationService.toggleTranslationPaused()).bounds(button.x(),button.y(),button.width(),button.height()).build();lastPaused=paused;}
            control.setX(button.x());control.setY(button.y());control.setWidth(button.width());
            if(lastPaused!=paused){lastPaused=paused;control.setMessage(paused?RESUME:PAUSE);}
            var window=game.getWindow();int mx=(int)(game.mouseHandler.xpos()*window.getGuiScaledWidth()/Math.max(1,window.getScreenWidth()));int my=(int)(game.mouseHandler.ypos()*window.getGuiScaledHeight()/Math.max(1,window.getScreenHeight()));
            control.extractRenderState(graphics,mx,my,0F);
            if(cacheButton!=null) {
                boolean display=TranslationService.isCachedTranslationDisplayEnabled();
                if(cacheControl==null){cacheControl=Button.builder(display?CACHE_ON:CACHE_OFF,b->TranslationService.toggleCachedTranslationDisplay()).bounds(cacheButton.x(),cacheButton.y(),cacheButton.width(),cacheButton.height()).build();lastCacheDisplay=display;}
                cacheControl.setX(cacheButton.x());cacheControl.setY(cacheButton.y());cacheControl.setWidth(cacheButton.width());
                if(lastCacheDisplay!=display){lastCacheDisplay=display;cacheControl.setMessage(display?CACHE_ON:CACHE_OFF);}
                cacheControl.extractRenderState(graphics,mx,my,0F);
            }
        }
        if(placement.tab()!=null){
            boolean minimized=TranslationService.isTranslationPanelMinimized();var tab=placement.tab();
            if(minimizeControl==null){minimizeControl=Button.builder(minimized?EXPAND:COLLAPSE,b->TranslationService.toggleTranslationPanelMinimized()).bounds(tab.x(),tab.y(),tab.width(),tab.height()).build();lastMinimized=minimized;}
            minimizeControl.setX(tab.x());minimizeControl.setY(tab.y());
            if(lastMinimized!=minimized){lastMinimized=minimized;minimizeControl.setMessage(minimized?EXPAND:COLLAPSE);}
            var window=game.getWindow();int mx=(int)(game.mouseHandler.xpos()*window.getGuiScaledWidth()/Math.max(1,window.getScreenWidth()));int my=(int)(game.mouseHandler.ypos()*window.getGuiScaledHeight()/Math.max(1,window.getScreenHeight()));
            minimizeControl.extractRenderState(graphics,mx,my,0F);
            if(placement.collapsed())graphics.fill(0,11+TranslationProgressControl.verticalOffset(),2,31+TranslationProgressControl.verticalOffset(),view.color());
        }
    }
}
