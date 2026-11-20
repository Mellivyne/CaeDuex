package com.cheatprogram.gui;

import com.cheatprogram.ai.AiController;
import com.cheatprogram.ai.AiPlayers;
import com.cheatprogram.core.Lang;
import com.cheatprogram.core.Notifications;
import com.cheatprogram.render.Icon;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

final class AiAgentMcpClipboard {
    private static long copyRevision;

    private AiAgentMcpClipboard() {}

    static void copyControl(GuiBase gui, String draft) {
        copy(gui, AiController.startMcp(draft));
    }

    static void copyPlayer(GuiBase gui, UUID playerId, String draft) {
        copy(gui, AiPlayers.startMcp(playerId, draft));
    }

    private static void copy(GuiBase gui, CompletableFuture<String> prompt) {
        long revision = ++copyRevision;
        prompt.whenComplete((text, error) -> gui.mc.execute(() -> {
            if (revision != copyRevision) return;
            if (error == null) {
                gui.mc.keyboardHandler.setClipboard(text);
                Notifications.push(Icon.LINK, Lang.t("ui.ai.exchange.title"), Lang.t("ui.ai.exchange.promptCopied"));
            } else {
                String key = error.getMessage();
                if (key == null || !key.startsWith("ui.ai.mcp.")) key = "ui.ai.mcp.failed";
                Notifications.push(Lang.t("ui.ai.exchange.title"), Lang.t(key), false);
            }
        }));
    }
}
