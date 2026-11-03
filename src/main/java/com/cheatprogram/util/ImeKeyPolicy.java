package com.cheatprogram.util;

/** Only composition navigation belongs to the IME; system shortcuts stay native. */
public final class ImeKeyPolicy {
    private ImeKeyPolicy(){}
    public static boolean compositionKey(int message,long key,boolean active,boolean composing,boolean systemShortcut){
        if(!active||!composing||systemShortcut||message!=0x100)return false;
        return key==8||key==9||key==13||key==27||key==32||key>=33&&key<=40
            ||key==46||key>=48&&key<=90||key>=96&&key<=111||key>=186&&key<=222;
    }
}
