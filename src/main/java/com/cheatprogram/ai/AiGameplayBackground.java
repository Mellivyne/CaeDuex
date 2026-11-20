package com.cheatprogram.ai;

import net.minecraft.client.Minecraft;

/** Share one temporary background-play setting across active local MCP sessions. */
final class AiGameplayBackground implements AutoCloseable {
    private static int sessions;
    private static boolean previousPause;
    private boolean closed;

    private AiGameplayBackground() {
        var options = Minecraft.getInstance().options;
        if (sessions++ == 0) previousPause = options.pauseOnLostFocus;
        options.pauseOnLostFocus = false;
    }

    static AiGameplayBackground hold() {
        return new AiGameplayBackground();
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        if (--sessions == 0) {
            var options = Minecraft.getInstance().options;
            if (!options.pauseOnLostFocus && previousPause) {
                options.pauseOnLostFocus = true;
                // Other settings screens may save while the session is active.
                options.save();
            }
        }
    }
}
