package com.cheatprogram.dev;
import com.cheatprogram.capture.AppCastInputPolicy;
public final class AppCastFullKeyCheck {
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args){
        for(char key:new char[]{'W','A','S','D','Q','E','R','F','1','9',' '}){
            require(!AppCastInputPolicy.textKey(true,true,true,false),"Full-key game controls must emit down/up for "+key);
            require(!AppCastInputPolicy.character(true,true,key,false),"No second ASCII character event for physical key "+key);
        }
        require(!AppCastInputPolicy.pasteText(true,true,true),"Game Ctrl+V remains a physical combo");
        require(AppCastInputPolicy.pasteText(false,true,true),"Typing mode retains explicit clipboard text");
        require(AppCastInputPolicy.textKey(false,true,true,false),"Typing mode defers printable keys to text callback");
        require(AppCastInputPolicy.character(false,true,'w',false),"Typing mode sends exactly one character");
        require(AppCastInputPolicy.character(true,true,'中',false),"Full-key mode can still commit IME Unicode");
        require(!AppCastInputPolicy.character(true,true,'中',true),"Shortcut modifiers do not add text");
        require(!AppCastInputPolicy.textKey(false,true,true,true),"Shortcuts use physical key events");
        require(!AppCastInputPolicy.textKey(false,false,true,false),"Movement-first keeps its physical non-text handling");
        System.out.println("APPCAST_FULL_KEYS_PASS physical WASD/space/numbers, combos, repeat ownership and text/IME separation");
    }
}
