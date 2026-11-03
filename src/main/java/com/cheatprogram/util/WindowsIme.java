package com.cheatprogram.util;

import com.sun.jna.*;
import com.sun.jna.win32.*;

/** Route composition keys only; the selected native IME owns its context, UI, placement and caret. */
public final class WindowsIme {
    private WindowsIme(){}
    public interface Subclass extends StdCallLibrary.StdCallCallback {
        long invoke(Pointer window,int message,long wparam,long lparam,long id,long data);
    }
    public interface Api extends StdCallLibrary {
        Pointer GetFocus();
        short GetKeyState(int key);
    }
    public interface Controls extends StdCallLibrary {
        boolean SetWindowSubclass(Pointer window,Subclass callback,long id,long data);
        boolean RemoveWindowSubclass(Pointer window,Subclass callback,long id);
        long DefSubclassProc(Pointer window,int message,long wparam,long lparam);
    }
    private static final class NativeApi {
        static final Api USER=Native.load("user32",Api.class,W32APIOptions.DEFAULT_OPTIONS);
        static final Controls CONTROLS=Native.load("comctl32",Controls.class,W32APIOptions.DEFAULT_OPTIONS);
    }
    private static final long ID=0x4350494dL;
    private static final Subclass CALLBACK=WindowsIme::message;
    private static Pointer window;
    private static boolean active,composing,failed;
    private static long message(Pointer hwnd,int msg,long wp,long lp,long id,long data){
        try{
            if(msg==0x10d){composing=active;}
            else if(msg==0x10e)composing=false;
            else if(msg==0x10f&&active){composing=(lp&0x800)==0;}
            else if(msg==8){composing=false;}
            else if(msg==0x82){deactivate();NativeApi.CONTROLS.RemoveWindowSubclass(hwnd,CALLBACK,ID);window=null;}
            if(msg==0x100&&active&&composing&&ImeKeyPolicy.compositionKey(msg,wp,true,true,
                NativeApi.USER.GetKeyState(0x11)<0||NativeApi.USER.GetKeyState(0x12)<0))return 0;
        }catch(Throwable ignored){/* Never unwind a Java exception through the native window procedure. */}
        // In particular WM_IME_*, WM_CHAR, WM_UNICHAR and language switching
        // retain their normal chain; no duplicated result text or forced layout.
        return NativeApi.CONTROLS.DefSubclassProc(hwnd,msg,wp,lp);
    }
    public static void update(long hwnd,boolean editing){
        if(!Platform.isWindows()||Native.POINTER_SIZE!=8||failed)return;
        try{
            Pointer next=hwnd==0?null:new Pointer(hwnd);
            if(window!=null&&!window.equals(next))shutdown();
            if(next==null||!editing||!next.equals(NativeApi.USER.GetFocus())){deactivate();return;}
            if(window==null){
                // Called from the client/render thread which owns the GLFW window.
                if(!NativeApi.CONTROLS.SetWindowSubclass(next,CALLBACK,ID,0))return;
                window=next;
            }
            if(!active){active=true;composing=false;}
        }catch(LinkageError|RuntimeException error){failed=true;shutdown();}
    }
    private static void deactivate(){active=false;composing=false;}
    public static void shutdown(){
        if(!Platform.isWindows())return;
        try{deactivate();if(window!=null)NativeApi.CONTROLS.RemoveWindowSubclass(window,CALLBACK,ID);}catch(LinkageError|RuntimeException ignored){}
        window=null;
    }
}
