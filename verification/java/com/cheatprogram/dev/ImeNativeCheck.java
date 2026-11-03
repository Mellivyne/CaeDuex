package com.cheatprogram.dev;

import com.cheatprogram.util.*;
import com.sun.jna.*;
import com.sun.jna.win32.*;
import java.util.*;

/** Hidden window owned by this test only. Never types into or activates a user's application. */
public final class ImeNativeCheck {
    public interface TestImm extends StdCallLibrary {
        Pointer ImmGetContext(Pointer window);
        boolean ImmReleaseContext(Pointer window,Pointer context);
    }
    public interface TestUser extends WindowsIme.Api {
        Pointer CreateWindowExW(int ex,WString type,WString name,int style,int x,int y,int w,int h,Pointer parent,Pointer menu,Pointer instance,Pointer param);
        Pointer SetFocus(Pointer window);
        long SendMessageW(Pointer window,int message,long wparam,long lparam);
        boolean DestroyWindow(Pointer window);
    }
    private static void require(boolean value,String why){if(!value)throw new AssertionError(why);}
    public static void main(String[] args){
        for(int message:new int[]{0x101,0x102,0x109,0x10d,0x10e,0x10f,0x281,0x282,0x286,0x287,0x51})
            require(!ImeKeyPolicy.compositionKey(message,65,true,true,false),"Native text/control messages must remain untouched");
        require(ImeKeyPolicy.compositionKey(0x100,49,true,true,false),"Candidate number is not a hotbar action");
        require(ImeKeyPolicy.compositionKey(0x100,13,true,true,false),"Candidate confirmation is not a form submit");
        require(!ImeKeyPolicy.compositionKey(0x100,65,true,true,true),"Keep system shortcuts");
        require(!ImeKeyPolicy.compositionKey(0x100,65,false,true,false),"Never suppress gameplay");
        if(!Platform.isWindows()){System.out.println("IME_POLICY_PASS (native Windows check unavailable)");return;}
        TestUser user=Native.load("user32",TestUser.class,W32APIOptions.DEFAULT_OPTIONS);
        var control=Native.load("comctl32",WindowsIme.Controls.class,W32APIOptions.DEFAULT_OPTIONS);
        var imm=Native.load("imm32",TestImm.class,W32APIOptions.DEFAULT_OPTIONS);
        Pointer hwnd=user.CreateWindowExW(0,new WString("STATIC"),new WString("IME verification"),0,0,0,640,480,null,null,null,null);
        require(hwnd!=null,"Own hidden window created");
        var events=new ArrayList<Integer>();var characters=new ArrayList<Long>();
        WindowsIme.Subclass observer=(window,msg,wp,lp,id,data)->{
            if(msg==0x100||msg==0x102||msg==0x109||msg==0x10d||msg==0x10e)events.add(msg);
            if(msg==0x102||msg==0x109)characters.add(wp);
            return control.DefSubclassProc(window,msg,wp,lp);
        };
        try{
            require(control.SetWindowSubclass(hwnd,observer,7,0),"Observer installed");
            user.SetFocus(hwnd);require(hwnd.equals(user.GetFocus()),"Own-thread hidden focus");
            Pointer before=imm.ImmGetContext(hwnd);if(before!=null)imm.ImmReleaseContext(hwnd,before);
            WindowsIme.update(Pointer.nativeValue(hwnd),true);
            Pointer after=imm.ImmGetContext(hwnd);require(Objects.equals(before,after),"Preserve the selected IME context, including a null native context");if(after!=null)imm.ImmReleaseContext(hwnd,after);
            events.clear();characters.clear();
            user.SendMessageW(hwnd,0x10d,0,0);user.SendMessageW(hwnd,0x100,49,0);
            user.SendMessageW(hwnd,0x102,0x4e2d,0);user.SendMessageW(hwnd,0x109,0x1f642,0);
            require(!events.contains(0x100),"Composing 1 cannot select an inventory slot");
            require(events.contains(0x10d)&&characters.equals(List.of(0x4e2dL,0x1f642L)),"Commit and third-party Unicode forwarded once");
            user.SendMessageW(hwnd,0x10e,0,0);user.SendMessageW(hwnd,0x100,65,0);require(events.contains(0x100),"Raw keys restored when composition ends");
            WindowsIme.update(Pointer.nativeValue(hwnd),false);WindowsIme.shutdown();
            System.out.println("IME_NATIVE_PASS composition isolation, Unicode delivery, existing context, focus lifecycle, hidden own-window subclass chain");
        }finally{WindowsIme.shutdown();control.RemoveWindowSubclass(hwnd,observer,7);user.DestroyWindow(hwnd);}
    }
}
