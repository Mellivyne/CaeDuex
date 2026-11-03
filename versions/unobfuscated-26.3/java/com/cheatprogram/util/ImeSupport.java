package com.cheatprogram.util;

/** SDL owns native composition and candidates in 26.3. GuiBase reports its
 * focused field to Minecraft's TextInputManager; vanilla widgets do so natively.
 * Do not attach the legacy Win32 input-context bridge to an SDL window. */
public final class ImeSupport {
    private ImeSupport() { }
    public static void custom(Object screen) { }
    public static void tick() { }
}
