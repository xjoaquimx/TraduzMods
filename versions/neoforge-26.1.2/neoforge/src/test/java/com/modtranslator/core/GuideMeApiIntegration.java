package com.modtranslator.core;

import com.modtranslator.client.GuideMePageRefresh;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Loads the optional installed GuideMe jar; no GuideMe classes are bundled or compile dependencies. */
public final class GuideMeApiIntegration {
    static final class Row { private final Object paragraph; Row(Object paragraph){this.paragraph=paragraph;} }
    static final class Navbar {
        private final List<Row> rows=new ArrayList<>(); int layouts;int scroll=47;
        private void updateLayout(){layouts++;}
    }
    static final class Screen {
        private final Object document, pageTitle;
        private final Navbar navbar=new Navbar();boolean documentLayoutInvalid;int layouts;int scroll=130;
        Screen(Object document,Object pageTitle){this.document=document;this.pageTitle=pageTitle;}
        protected Object getDocument(){return document;}
        private void updateScreenLayout(){layouts++;GuideMePageRefresh.refresh(this);}
    }
    static Class<?> type(String simple)throws Exception{return Class.forName("guideme.document."+simple);}
    static Object paragraph(String text)throws Exception{return type("block.LytParagraph").getMethod("of",String.class).invoke(null,text);}
    static String text(Object node) {
        try {return (String)node.getClass().getMethod("getTextContent").invoke(node);}
        catch(Exception e){throw new RuntimeException(e);}
    }
    public static void main(String[] args)throws Exception {
        TranslationServiceIntegrationTest.root=Path.of(args[0]);Files.createDirectories(TranslationServiceIntegrationTest.root);
        com.mojang.logging.LogUtils.getLogger();
        try(var mock=new TranslationServiceIntegrationTest.Mock()) {
            TranslationServiceIntegrationTest.init("real-guideme",mock,true);
            TranslationServiceIntegrationTest.scan(Map.of(),Map.of(),Map.of());
            mock.translation=s->s.replace("The Charger","O Carregador").replace("Simple Automation","Automação simples")
                .replace("Getting Started","Primeiros passos").replace("provides a way to charge supported tools.","permite carregar ferramentas compatíveis.");
            Object doc=type("block.LytDocument").getConstructor().newInstance();
            // LytImage needs the running Minecraft registry; use an inert real block in this headless fixture.
            Object divider=type("block.LytThematicBreak").getConstructor().newInstance();
            Object body=paragraph("The Charger provides a way to charge supported tools.");
            Object heading=type("block.LytHeading").getConstructor().newInstance();
            Object headingText=type("flow.LytFlowText").getMethod("of",String.class).invoke(null,"Simple Automation");
            heading.getClass().getMethod("append",type("flow.LytFlowContent")).invoke(heading,headingText);
            Method append=doc.getClass().getMethod("append",type("block.LytBlock"));
            append.invoke(doc,divider);append.invoke(doc,body);append.invoke(doc,heading);
            Object title=paragraph("The Charger"), nav=paragraph("Getting Started");
            Screen screen=new Screen(doc,title);screen.navbar.rows.add(new Row(nav));
            Object style=body.getClass().getMethod("getStyle").invoke(body);
            GuideMePageRefresh.refresh(screen);
            TranslationServiceIntegrationTest.await(()->{
                GuideMePageRefresh.refresh(screen);
                return text(body).equals("O Carregador permite carregar ferramentas compatíveis.")
                    &&text(title).equals("O Carregador")&&text(heading).equals("Automação simples")&&text(nav).equals("Primeiros passos");
            },"real GuideMe text graph not translated");
            TranslationServiceIntegrationTest.check(screen.documentLayoutInvalid&&screen.layouts>0&&screen.navbar.layouts>0,"real graph did not request relayout");
            TranslationServiceIntegrationTest.check(screen.scroll==130&&screen.navbar.scroll==47&&style==body.getClass().getMethod("getStyle").invoke(body),"style/scroll changed");
            TranslationServiceIntegrationTest.check(((List<?>)doc.getClass().getMethod("getChildren").invoke(doc)).get(0)==divider,"non-text block replaced");
            TranslationService.setCachedTranslationDisplayEnabled(false);
            TranslationServiceIntegrationTest.await(()->{GuideMePageRefresh.refresh(screen);return text(body).startsWith("The Charger")&&text(title).equals("The Charger")&&text(nav).equals("Getting Started");},"real GuideMe original text not restored");
            System.out.println("PASS real GuideMe 26.1.15-beta: body, heading, detached title, sidebar, non-text block, styles, scroll, cache toggle");
        } finally {TranslationServiceIntegrationTest.closeService();}
    }
}
