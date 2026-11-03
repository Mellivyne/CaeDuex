package com.cheatprogram.dev;
import com.cheatprogram.util.KeyBindingCapture;
import java.util.ArrayList;
import java.util.List;

/** Letter/F-key capture must not leak into search, world hotkeys or repeated binding changes. */
public final class KeyBindingCaptureCheck {
    static void require(boolean v,String m){if(!v)throw new AssertionError(m);}
    public static void main(String[] args){
        var capture=new KeyBindingCapture();var assigned=new ArrayList<Integer>();
        require(!capture.key(65,1,false,assigned::add),"Ordinary key remains native");
        require(capture.key(65,1,true,assigned::add)&&capture.blocksText(),"Letter binding owns text");
        require(capture.key(65,2,false,assigned::add)&&capture.key(65,1,false,assigned::add),"Repeat/duplicate press cannot trigger bound module");
        require(assigned.equals(List.of(65)),"Assign once per physical press");
        require(capture.key(65,0,false,assigned::add)&&!capture.blocksText(),"Release stays owned and restores typing");
        require(!capture.key(65,1,false,assigned::add),"Next press can operate newly bound shortcut");
        require(capture.key(294,1,true,assigned::add)&&capture.key(294,0,false,assigned::add),"F5 does not switch perspective during capture");
        require(capture.key(-1,1,true,assigned::add)&&!assigned.contains(-1),"Unknown key not saved");
        require(capture.key(256,1,true,assigned::add)&&capture.key(256,0,false,assigned::add),"Escape cancel is owned through release");
        System.out.println("KEY_BINDING_CAPTURE_PASS ordinary typing, one assignment, text/repeat/release ownership, F5 and unknown key");
    }
}
