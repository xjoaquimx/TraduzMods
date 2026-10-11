package com.modtranslator.core;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.regex.Pattern;

/** Microbenchmarks of dispatch paths, not inference speed or Minecraft FPS. */
public final class OptimizationBenchmark {
    private static volatile long sink;
    public static void main(String[] args) {
        List<String> terms=new ArrayList<>();
        for(int i=0;i<20_000;i++)terms.add("machine component "+i);
        List<Pattern> patterns=terms.stream().map(term->Pattern.compile("(?<![\\p{L}\\p{N}_])"+Pattern.quote(term)+"(?![\\p{L}\\p{N}_])",Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE)).toList();
        TerminologyIndex indexed=new TerminologyIndex(terms);
        String text="Craft Machine Component 19999 using Machine Component 9999 and Machine Component 4999.";
        for(int i=0;i<40;i++){exhaustive(text,terms,patterns);indexed(text,indexed,patterns);}
        List<Double> before=new ArrayList<>(),after=new ArrayList<>(),queueBefore=new ArrayList<>(),queueAfter=new ArrayList<>();
        for(int round=0;round<7;round++) {
            long start=System.nanoTime();for(int i=0;i<200;i++)exhaustive(text,terms,patterns);before.add((System.nanoTime()-start)/1_000_000D);
            start=System.nanoTime();for(int i=0;i<200;i++)indexed(text,indexed,patterns);after.add((System.nanoTime()-start)/1_000_000D);
            Deque<String> baseline=new ConcurrentLinkedDeque<>(),fast=new IndexedWorkDeque();
            for(int i=0;i<30_000;i++){baseline.addLast("job-"+i);fast.addLast("job-"+i);}
            queueBefore.add(promote(baseline));queueAfter.add(promote(fast));
        }
        System.out.printf(Locale.ROOT,"{\"glossaryEntries\":20000,\"referenceCallsPerRound\":200,\"queueEntries\":30000,\"promotionsPerRound\":1000,\"rounds\":7,\"referenceBeforeMs\":%.3f,\"referenceAfterMs\":%.3f,\"queueBeforeMs\":%.3f,\"queueAfterMs\":%.3f,\"sink\":%d}%n",median(before),median(after),median(queueBefore),median(queueAfter),sink);
    }
    private static double promote(Deque<String> queue) {
        long start=System.nanoTime();
        for(int i=0;i<1000;i++){String value="job-"+(29999-i);queue.remove(value);queue.addFirst(value);sink+=queue.size();}
        return (System.nanoTime()-start)/1_000_000D;
    }
    private static void exhaustive(String text,List<String> terms,List<Pattern> patterns) {
        String folded=text.toLowerCase(Locale.ROOT);
        for(int i=0;i<terms.size();i++)if(folded.contains(terms.get(i))&&patterns.get(i).matcher(text).find())sink++;
    }
    private static void indexed(String text,TerminologyIndex index,List<Pattern> patterns) {
        BitSet candidates=index.candidates(text);
        for(int i=candidates.nextSetBit(0);i>=0;i=candidates.nextSetBit(i+1))if(patterns.get(i).matcher(text).find())sink++;
    }
    private static double median(List<Double> values){values.sort(Double::compare);return values.get(values.size()/2);}
}
