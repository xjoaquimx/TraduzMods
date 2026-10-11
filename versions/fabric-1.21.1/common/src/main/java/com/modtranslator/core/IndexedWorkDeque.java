package com.modtranslator.core;

import java.util.*;

/** Unique pending work with constant-time promotion, removal and size.
 * Render callbacks and the service worker share this queue; each operation is atomic.
 * Re-adding a value moves its existing node instead of retaining stale queue entries.
 */
final class IndexedWorkDeque extends AbstractCollection<String> implements Deque<String> {
    private static final class Node {
        final String value;
        Node previous,next;
        Node(String value){this.value=value;}
    }
    private final Map<String,Node> index=new HashMap<>();
    private Node first,last;

    private void unlink(Node node){
        if(node.previous==null)first=node.next;else node.previous.next=node.next;
        if(node.next==null)last=node.previous;else node.next.previous=node.previous;
        node.previous=null;node.next=null;
    }
    @Override public synchronized void addFirst(String value){
        Objects.requireNonNull(value);
        Node node=index.get(value);
        if(node==first && node!=null)return;
        if(node==null){node=new Node(value);index.put(value,node);}else unlink(node);
        node.next=first;
        if(first==null)last=node;else first.previous=node;
        first=node;
    }
    @Override public synchronized void addLast(String value){
        Objects.requireNonNull(value);
        Node node=index.get(value);
        if(node==last && node!=null)return;
        if(node==null){node=new Node(value);index.put(value,node);}else unlink(node);
        node.previous=last;
        if(last==null)first=node;else last.next=node;
        last=node;
    }
    @Override public synchronized String pollFirst(){
        if(first==null)return null;
        Node node=first;unlink(node);index.remove(node.value);return node.value;
    }
    @Override public synchronized String pollLast(){
        if(last==null)return null;
        Node node=last;unlink(node);index.remove(node.value);return node.value;
    }
    @Override public synchronized String peekFirst(){return first==null?null:first.value;}
    @Override public synchronized String peekLast(){return last==null?null:last.value;}
    @Override public synchronized boolean remove(Object value){
        Node node=index.remove(value);if(node==null)return false;unlink(node);return true;
    }
    @Override public synchronized boolean contains(Object value){return index.containsKey(value);}
    @Override public synchronized int size(){return index.size();}
    @Override public synchronized boolean isEmpty(){return first==null;}
    @Override public synchronized void clear(){index.clear();first=null;last=null;}
    private synchronized List<String> snapshot(boolean reverse){
        List<String> values=new ArrayList<>(index.size());
        for(Node node=reverse?last:first;node!=null;node=reverse?node.previous:node.next)values.add(node.value);
        return Collections.unmodifiableList(values);
    }
    @Override public Iterator<String> iterator(){return snapshot(false).iterator();}
    @Override public Iterator<String> descendingIterator(){return snapshot(true).iterator();}
    @Override public boolean offerFirst(String value){addFirst(value);return true;}
    @Override public boolean offerLast(String value){addLast(value);return true;}
    @Override public boolean add(String value){addLast(value);return true;}
    @Override public boolean offer(String value){return offerLast(value);}
    @Override public String removeFirst(){String value=pollFirst();if(value==null)throw new NoSuchElementException();return value;}
    @Override public String removeLast(){String value=pollLast();if(value==null)throw new NoSuchElementException();return value;}
    @Override public String getFirst(){String value=peekFirst();if(value==null)throw new NoSuchElementException();return value;}
    @Override public String getLast(){String value=peekLast();if(value==null)throw new NoSuchElementException();return value;}
    @Override public boolean removeFirstOccurrence(Object value){return remove(value);}
    @Override public boolean removeLastOccurrence(Object value){return remove(value);}
    @Override public String poll(){return pollFirst();}
    @Override public String peek(){return peekFirst();}
    @Override public String remove(){return removeFirst();}
    @Override public String element(){return getFirst();}
    @Override public void push(String value){addFirst(value);}
    @Override public String pop(){return removeFirst();}
}
