package com.cheatprogram.util;

import java.util.HashSet;
import java.util.Set;
import java.util.function.IntConsumer;

/** A binding press owns its repeats, release and text until the physical key is released. */
public final class KeyBindingCapture {
    private final Set<Integer> held=new HashSet<>();
    public boolean key(int code,int action,boolean waiting,IntConsumer assign){
        if(action==0)return held.remove(code);
        if(held.contains(code))return true;
        if(!waiting)return false;
        if(action==1&&code>=0){held.add(code);assign.accept(code);}
        return true;
    }
    public boolean blocksText(){return !held.isEmpty();}
}
