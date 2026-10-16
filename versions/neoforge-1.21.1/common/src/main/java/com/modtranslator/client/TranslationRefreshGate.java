package com.modtranslator.client;

/** Coalesces model commits into at most two optional-mod relayouts per second. */
public final class TranslationRefreshGate {
    private long seen=Long.MIN_VALUE,next;
    public boolean changed(long revision,long now) {
        if(seen==revision||now<next)return false;
        seen=revision;next=now+500_000_000L;return true;
    }
}
