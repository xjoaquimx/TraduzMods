package com.modtranslator.core;

import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/** Compare optimized structures against exhaustive independent references. */
final class OptimizationChecks {
    static void require(boolean result,String reason) { if(!result)throw new AssertionError(reason); }
    static void queue() throws Exception {
        IndexedWorkDeque actual=new IndexedWorkDeque();
        LinkedList<String> reference=new LinkedList<>();
        Random random=new Random(42);
        for(int i=0;i<20_000;i++) {
            String value="job-"+random.nextInt(400);
            switch(random.nextInt(6)) {
                case 0 -> {actual.addFirst(value);reference.remove(value);reference.addFirst(value);}
                case 1 -> {actual.addLast(value);reference.remove(value);reference.addLast(value);}
                case 2 -> require(actual.remove(value)==reference.remove(value),"removal differs");
                case 3 -> require(Objects.equals(actual.pollFirst(),reference.pollFirst()),"head differs");
                case 4 -> require(Objects.equals(actual.pollLast(),reference.pollLast()),"tail differs");
                default -> require(actual.contains(value)==reference.contains(value),"membership differs");
            }
            require(actual.size()==reference.size(),"queue count drift");
            if(i%100==0)require(new ArrayList<>(actual).equals(reference),"queue order differs");
        }
        actual.clear();
        ExecutorService threads=Executors.newFixedThreadPool(4);
        List<Future<?>> tasks=new ArrayList<>();
        try {
            for(int t=0;t<4;t++)tasks.add(threads.submit(()->{
                for(int i=0;i<10_000;i++) {actual.addFirst("job-"+(i%400));actual.addLast("job-"+(i%400));}
            }));
            for(Future<?> task:tasks)task.get(10,TimeUnit.SECONDS);
            require(actual.size()==400&&new HashSet<>(actual).size()==400,"concurrent promotion lost/duplicated jobs");
            for(int i=0;i<400;i++)require(actual.pollFirst()!=null,"concurrent queue lost nodes");
            require(actual.isEmpty()&&actual.pollLast()==null,"empty endpoints corrupt");
        } finally { threads.shutdownNow(); }
    }
    static void terminology() {
        List<String> terms=new ArrayList<>(List.of("he","she","hers","his","Wooden Pickaxe","Pickaxe","Stone","stones","Iris","İris","σ"));
        for(int i=0;i<20_000;i++)terms.add("Machine Component "+i);
        TerminologyIndex index=new TerminologyIndex(terms);
        List<String> texts=new ArrayList<>(List.of("ushers","WOODEN PICKAXE and stones","İRIS σς","There are no relevant terms.","Machine Component 19999 and Machine Component 0"));
        Random random=new Random(43);
        for(int i=0;i<100;i++)texts.add("Use "+terms.get(random.nextInt(terms.size()))+" with "+terms.get(random.nextInt(terms.size()))+".");
        for(String text:texts) {
            BitSet candidates=index.candidates(text);
            for(int i=0;i<terms.size();i++) {
                boolean exact=Pattern.compile(Pattern.quote(terms.get(i)),Pattern.CASE_INSENSITIVE|Pattern.UNICODE_CASE).matcher(text).find();
                require(!exact||candidates.get(i),"index lost Unicode/suffix candidate: "+terms.get(i));
            }
        }
        require(index.candidates("WOODEN PICKAXE").get(4)&&index.candidates("WOODEN PICKAXE").get(5),"overlapping terms lost");
    }
    public static void main(String[] args) throws Exception {
        queue();terminology();System.out.println("PASS randomized queue, concurrent promotions, 20k-term Unicode/suffix equivalence");
    }
}
