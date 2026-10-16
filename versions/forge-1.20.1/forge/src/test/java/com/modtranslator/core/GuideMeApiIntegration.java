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
            System.out.println("PASS supplied GuideMe JAR: body, heading, detached title, sidebar, non-text block, styles, scroll, cache toggle");
            TranslationService.setCachedTranslationDisplayEnabled(true);
            mock.translation=s->s.replace("Equal distribution cards can be applied to","Cartões de distribuição igualitária podem ser aplicados a")
                .replace("storage cells","células de armazenamento").replace("They split the cell","Eles dividem a célula");
            Object fragmentDoc=type("block.LytDocument").getConstructor().newInstance();
            Object paragraph=type("block.LytParagraph").getConstructor().newInstance();
            List<String> originals=List.of("Equal distribution cards can be applied to ","storage cells",". They split the cell.");
            Object left=type("flow.LytFlowText").getMethod("of",String.class).invoke(null,originals.get(0));
            Object linked=type("flow.LytFlowText").getMethod("of",String.class).invoke(null,originals.get(1));
            Object right=type("flow.LytFlowText").getMethod("of",String.class).invoke(null,originals.get(2));
            Object span=type("flow.LytFlowSpan").getConstructor().newInstance();
            span.getClass().getMethod("append",type("flow.LytFlowContent")).invoke(span,linked);
            Object spanStyle=span.getClass().getMethod("getStyle").invoke(span);
            Method appendFlow=paragraph.getClass().getMethod("append",type("flow.LytFlowContent"));
            appendFlow.invoke(paragraph,left);appendFlow.invoke(paragraph,span);appendFlow.invoke(paragraph,right);append.invoke(fragmentDoc,paragraph);
            @SuppressWarnings("unchecked") Map<String,String> cache=(Map<String,String>)TranslationServiceIntegrationTest.value(TranslationServiceIntegrationTest.service(),"bySource");
            cache.put(com.modtranslator.client.StyledTextCodec.encode(originals),"<mt0>Cartões podem ser aplicados às células</mt0><mt2>. Eles dividem a célula.</mt2><mt1></mt1>");
            Screen fragmentScreen=new Screen(fragmentDoc,paragraph("The Charger"));
            TranslationServiceIntegrationTest.await(()->{GuideMePageRefresh.refresh(fragmentScreen);return text(paragraph).equals("Cartões de distribuição igualitária podem ser aplicados a células de armazenamento. Eles dividem a célula.");},"real flow cached collapsed output never fell back");
            TranslationServiceIntegrationTest.check(((List<?>)span.getClass().getMethod("getChildren").invoke(span)).get(0)==linked
                &&spanStyle==span.getClass().getMethod("getStyle").invoke(span)&&fragmentScreen.scroll==130,"real fallback replaced nested span/style or scroll");
            TranslationService.setCachedTranslationDisplayEnabled(false);
            TranslationServiceIntegrationTest.await(()->{GuideMePageRefresh.refresh(fragmentScreen);return text(paragraph).equals(String.join("",originals));},"real fallback did not restore original text");
            System.out.println("PASS real GuideMe collapsed XML fallback: nested spans, whitespace, original restoration, layout invalidation");
        } finally {TranslationServiceIntegrationTest.closeService();}
    }
}
