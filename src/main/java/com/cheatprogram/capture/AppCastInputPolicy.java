package com.cheatprogram.capture;

/** Full-key ownership forwards physical printable keys, rather than replacing
 * them with text characters. Typed text mode keeps its existing single commit. */
public final class AppCastInputPolicy {
    private AppCastInputPolicy(){}
    public static boolean textKey(boolean fullKeys,boolean typing,boolean printable,boolean shortcut){return !fullKeys&&typing&&printable&&!shortcut;}
    public static boolean pasteText(boolean fullKeys,boolean typing,boolean pasteChord){return !fullKeys&&typing&&pasteChord;}
    public static boolean character(boolean fullKeys,boolean typing,char value,boolean shortcut){return typing&&!shortcut&&(!fullKeys||value>127);}
}
