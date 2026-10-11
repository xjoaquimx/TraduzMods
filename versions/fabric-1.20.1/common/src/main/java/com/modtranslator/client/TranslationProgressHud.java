package com.modtranslator.client;

import com.modtranslator.core.TranslationService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
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
    public static void render(GuiGraphics graphics) {render(graphics,currentView());}
    public static void render(GuiGraphics graphics,TranslationProgressLayout.View view) {
        var game=Minecraft.getInstance();
        if(view==null||game.options.hideGui||graphics.guiWidth()<120||graphics.guiHeight()<72)return;
        var placement=TranslationPanelMotion.frame(TranslationService.isTranslationPanelMinimized(),graphics.guiWidth(),graphics.guiHeight(),System.nanoTime());
        int x=placement.panelX(),y=6,width=TranslationProgressControl.panelWidth(graphics.guiWidth()),inner=width-10;
        var button=game.screen!=null?TranslationProgressControl.bounds(graphics.guiWidth(),graphics.guiHeight()):null;
        
        var cacheButton=game.screen!=null?TranslationProgressControl.cacheBounds(graphics.guiWidth(),graphics.guiHeight()):null;
        int panelHeight=cacheButton!=null?87:59;
        graphics.fill(x,y,x+width,y+panelHeight,0xEE101010);
        graphics.fill(x,y,x+width,y+1,0xFF686868);
        graphics.fill(x,y+1,x+1,y+panelHeight-1,0xFF686868);
        graphics.fill(x+1,y+panelHeight-1,x+width,y+panelHeight,0xFF202020);
        graphics.fill(x+width-1,y+1,x+width,y+panelHeight-1,0xFF202020);
        if(lastView!=view){lastView=view;savedText=view.title().equals("Tradução concluída")||view.detail().equals("Cache disponível")?view.detail():"Salvos: "+view.detail();sessionText="Nesta sessão: "+view.sessionCompleted()+" novos";}
        int headingWidth=button==null?inner-24:inner-96;
        graphics.drawString(game.font,game.font.plainSubstrByWidth(view.title(),headingWidth),x+5,y+5,view.color(),true);
        graphics.drawString(game.font,game.font.plainSubstrByWidth(savedText,headingWidth),x+5,y+16,0xFFE0E0E0,true);
        graphics.drawString(game.font,game.font.plainSubstrByWidth(sessionText,inner),x+5,y+27,0xFFAAAAAA,true);
        graphics.drawString(game.font,game.font.plainSubstrByWidth(view.reason(),inner),x+5,y+38,0xFFDDCC88,true);
        graphics.fill(x+5,y+51,x+width-5,y+54,0xFF303030);
        int amount=Math.max(0,Math.min(inner,(int)Math.round(inner*view.fraction())));
        if(view.indeterminate()){int segment=Math.max(8,inner/4),travel=inner-segment,offset=(int)((Math.sin(System.nanoTime()/600_000_000D)+1D)*0.5D*travel);graphics.fill(x+5+offset,y+51,x+5+offset+segment,y+54,view.color());}
        else if(amount>0)graphics.fill(x+5,y+51,x+5+amount,y+54,view.color());
        if(button!=null&&placement.expandedInput()){
            boolean paused=TranslationService.isTranslationPaused();
            if(control==null){control=Button.builder(paused?RESUME:PAUSE,b->TranslationService.toggleTranslationPaused()).bounds(button.x(),button.y(),button.width(),button.height()).build();lastPaused=paused;}
            control.setX(button.x());control.setY(button.y());control.setWidth(button.width());
            if(lastPaused!=paused){lastPaused=paused;control.setMessage(paused?RESUME:PAUSE);}
            var window=game.getWindow();int mx=(int)(game.mouseHandler.xpos()*window.getGuiScaledWidth()/Math.max(1,window.getScreenWidth()));int my=(int)(game.mouseHandler.ypos()*window.getGuiScaledHeight()/Math.max(1,window.getScreenHeight()));
            control.render(graphics,mx,my,0F);
            if(cacheButton!=null) {
                boolean display=TranslationService.isCachedTranslationDisplayEnabled();
                if(cacheControl==null){cacheControl=Button.builder(display?CACHE_ON:CACHE_OFF,b->TranslationService.toggleCachedTranslationDisplay()).bounds(cacheButton.x(),cacheButton.y(),cacheButton.width(),cacheButton.height()).build();lastCacheDisplay=display;}
                cacheControl.setX(cacheButton.x());cacheControl.setY(cacheButton.y());cacheControl.setWidth(cacheButton.width());
                if(lastCacheDisplay!=display){lastCacheDisplay=display;cacheControl.setMessage(display?CACHE_ON:CACHE_OFF);}
                cacheControl.render(graphics,mx,my,0F);
            }
        }
        if(placement.tab()!=null){
            boolean minimized=TranslationService.isTranslationPanelMinimized();var tab=placement.tab();
            if(minimizeControl==null){minimizeControl=Button.builder(minimized?EXPAND:COLLAPSE,b->TranslationService.toggleTranslationPanelMinimized()).bounds(tab.x(),tab.y(),tab.width(),tab.height()).build();lastMinimized=minimized;}
            minimizeControl.setX(tab.x());minimizeControl.setY(tab.y());
            if(lastMinimized!=minimized){lastMinimized=minimized;minimizeControl.setMessage(minimized?EXPAND:COLLAPSE);}
            var window=game.getWindow();int mx=(int)(game.mouseHandler.xpos()*window.getGuiScaledWidth()/Math.max(1,window.getScreenWidth()));int my=(int)(game.mouseHandler.ypos()*window.getGuiScaledHeight()/Math.max(1,window.getScreenHeight()));
            minimizeControl.render(graphics,mx,my,0F);
            if(placement.collapsed())graphics.fill(0,11,2,31,view.color());
        }
    }
}
