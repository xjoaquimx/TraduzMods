package com.modtranslator.core;

import com.modtranslator.client.StyledTextCodec;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Reapply styles to a translation of the whole phrase; never translates isolated prose fragments. */
public final class StyledTranslationAlignment {
    private StyledTranslationAlignment() {}
    private record Anchor(int start,int end,int id) {}
    public static String plain(String xml) {
        String text=StyledTextCodec.plainText(xml);
        if(text==null)throw new IllegalArgumentException("Invalid style source");
        return text;
    }
    public static String align(String source,String translated,Function<String,String> nativeLabel) {
        int count=0;for(int at=0;(at=source.indexOf("<mt",at))>=0;at+=3)count++;
        var spans=StyledTextCodec.decode(source,count);
        if(spans==null)throw new IllegalArgumentException("Invalid style source");
        List<Anchor> anchors=new ArrayList<>();
        for(var span:spans) {
            String original=span.text().strip();
            if(original.isEmpty()||original.length()>100||original.contains("\n")||original.contains("\\n"))continue;
            String target=nativeLabel.apply(original);
            int start=-1,end=-1;
            for(String candidate:target==null?List.of(original):List.of(target,original)) {
                var matcher=Pattern.compile("(?<![\\p{L}\\p{N}_])"+Pattern.quote(candidate)+"(?![\\p{L}\\p{N}_])",Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE).matcher(translated);
                while(matcher.find()) {
                    int from=matcher.start(),to=matcher.end();
                    if(anchors.stream().noneMatch(a->a.start<to&&a.end>from)){start=from;end=to;break;}
                }
                if(start>=0)break;
            }
            if(start<0)continue;
            anchors.add(new Anchor(start,end,span.index()));
        }
        String encoded;
        if(anchors.isEmpty()) {
            StringBuilder[] bodies=new StringBuilder[count];Arrays.setAll(bodies,i->new StringBuilder());int offset=0;
            // With no terminology anchor, retain all styles using word boundaries.
            // Text itself remains exactly the coherent whole-phrase translation.
            int sourceLength=spans.stream().mapToInt(s->s.text().length()).sum(),weight=0;
            for(int i=0;i<count;i++) {
                weight+=spans.get(i).text().length();int end=i==count-1?translated.length():(int)((long)translated.length()*weight/Math.max(1,sourceLength));
                while(end<translated.length()&&!Character.isWhitespace(translated.charAt(end)))end++;
                end=Math.max(offset,end);bodies[i].append(translated,offset,end);offset=end;
            }
            encoded=StyledTextCodec.encode(Arrays.stream(bodies).map(StringBuilder::toString).toList());
        } else {
            anchors.sort(Comparator.comparingInt(Anchor::start));
            Set<Integer> unavailable=new HashSet<>();anchors.forEach(a->unavailable.add(a.id));
            List<StyledTextCodec.Span> ordered=new ArrayList<>();int offset=0,previous=-1;
            for(Anchor anchor:anchors) {
                if(anchor.start>offset) {
                    // A sentence may acquire an article before an initially
                    // colored item. Keep that article with the item, reserving
                    // the reset span for the prose that follows it.
                    if(ordered.isEmpty()&&anchor.id==0){ordered.add(new StyledTextCodec.Span(anchor.id,translated.substring(offset,anchor.end)));offset=anchor.end;previous=anchor.id;continue;}
                    int id=gapId(unavailable,count,previous<0?0:previous+1,anchor.id-1);
                    if(id>=0){ordered.add(new StyledTextCodec.Span(id,translated.substring(offset,anchor.start)));unavailable.add(id);}
                    else if(!ordered.isEmpty()){var last=ordered.remove(ordered.size()-1);ordered.add(new StyledTextCodec.Span(last.index(),last.text()+translated.substring(offset,anchor.start)));}
                    else {ordered.add(new StyledTextCodec.Span(anchor.id,translated.substring(offset,anchor.end)));offset=anchor.end;previous=anchor.id;continue;}
                }
                ordered.add(new StyledTextCodec.Span(anchor.id,translated.substring(anchor.start,anchor.end)));offset=anchor.end;previous=anchor.id;
            }
            if(offset<translated.length()) {
                int id=gapId(unavailable,count,previous+1,count-1);
                if(id>=0){ordered.add(new StyledTextCodec.Span(id,translated.substring(offset)));unavailable.add(id);}
                else {var last=ordered.remove(ordered.size()-1);ordered.add(new StyledTextCodec.Span(last.index(),last.text()+translated.substring(offset)));}
            }
            for(int i=0;i<count;i++)if(!unavailable.contains(i))ordered.add(new StyledTextCodec.Span(i,""));
            StringBuilder xml=new StringBuilder();
            for(var span:ordered) {
                String body=span.text().replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");
                xml.append("<mt").append(span.index()).append('>').append(body).append("</mt").append(span.index()).append('>');
            }
            encoded=xml.toString();
        }
        if(!plain(encoded).equals(translated))throw new IllegalArgumentException("Style alignment changed translation");
        return encoded;
    }
    private static int gapId(Set<Integer> used,int count,int preferred,int alternate) {
        if(preferred>=0&&preferred<count&&!used.contains(preferred))return preferred;
        if(alternate>=0&&alternate<count&&!used.contains(alternate))return alternate;
        for(int i=0;i<count;i++)if(!used.contains(i))return i;
        return -1;
    }
}
