package com.modtranslator.core;

import java.util.*;

/** Worker-confined raw paragraph reuse. Disk remains the authoritative whole-text cache. */
final class TranslationMemo {
    private final LinkedHashMap<String,String> values=new LinkedHashMap<>(16,.75F,true);
    private final int maxBytes,maxEntries;
    private int bytes;
    TranslationMemo(int maxBytes,int maxEntries){this.maxBytes=Math.max(0,maxBytes);this.maxEntries=Math.max(0,maxEntries);}
    String get(String prompt){return values.get(prompt);}
    void put(String prompt,String translation) {
        int weight=weight(prompt,translation);
        if(weight>maxBytes||maxEntries==0)return;
        String previous=values.put(prompt,translation);
        if(previous!=null)bytes-=weight(prompt,previous);
        bytes+=weight;
        Iterator<Map.Entry<String,String>> oldest=values.entrySet().iterator();
        while(bytes>maxBytes||values.size()>maxEntries) {
            var entry=oldest.next();bytes-=weight(entry.getKey(),entry.getValue());oldest.remove();
        }
    }
    int size(){return values.size();}
    int bytes(){return bytes;}
    private static int weight(String key,String value){return Math.toIntExact(Math.min(Integer.MAX_VALUE,96L+2L*(key.length()+value.length())));}
}
