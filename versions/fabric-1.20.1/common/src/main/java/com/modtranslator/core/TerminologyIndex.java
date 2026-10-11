package com.modtranslator.core;

import java.util.*;

/** Immutable multi-pattern candidate index. Original regexes still decide word boundaries.
 * ASCII case folding is exact here; Unicode text takes the exhaustive safe path.
 */
final class TerminologyIndex {
    private static final class Node {
        final Map<Character,Node> next=new HashMap<>();
        // Store only terminal IDs: a BitSet on every node scales with the whole
        // glossary and wastes tens of MiB for large modpacks.
        List<Integer> terminals;
        Node failure;
        Node output;
    }
    private final Node root=new Node();
    private final BitSet unicode=new BitSet();
    private final int count;
    TerminologyIndex(List<String> terms){
        count=terms.size();root.failure=root;
        for(int index=0;index<count;index++){
            String term=terms.get(index);
            if(!ascii(term)){unicode.set(index);continue;}
            Node node=root;
            for(int i=0;i<term.length();i++)node=node.next.computeIfAbsent(fold(term.charAt(i)),key->new Node());
            if(node.terminals==null)node.terminals=new ArrayList<>(1);
            node.terminals.add(index);
        }
        ArrayDeque<Node> queue=new ArrayDeque<>();
        for(Node node:root.next.values()){node.failure=root;queue.addLast(node);}
        while(!queue.isEmpty()){
            Node parent=queue.removeFirst();
            for(var edge:parent.next.entrySet()){
                char character=edge.getKey();Node child=edge.getValue(),fallback=parent.failure;
                while(fallback!=root&&!fallback.next.containsKey(character))fallback=fallback.failure;
                child.failure=fallback.next.getOrDefault(character,root);
                child.output=child.failure.terminals!=null?child.failure:child.failure.output;
                queue.addLast(child);
            }
        }
    }
    BitSet candidates(String text){
        BitSet matches=(BitSet)unicode.clone();
        if(!ascii(text)){matches.set(0,count);return matches;}
        Node node=root;
        for(int i=0;i<text.length();i++){
            char character=fold(text.charAt(i));
            while(node!=root&&!node.next.containsKey(character))node=node.failure;
            node=node.next.getOrDefault(character,root);
            for(Node terminal=node;terminal!=null;terminal=terminal.output)
                if(terminal.terminals!=null)for(int index:terminal.terminals)matches.set(index);
        }
        return matches;
    }
    private static char fold(char character){return character>='A'&&character<='Z'?(char)(character+32):character;}
    private static boolean ascii(String text){for(int i=0;i<text.length();i++)if(text.charAt(i)>127)return false;return true;}
}
