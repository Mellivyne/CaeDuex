package com.cheatprogram.gui;

import com.cheatprogram.core.Lang;
import com.cheatprogram.core.Notifications;
import com.cheatprogram.render.Fonts;
import com.cheatprogram.render.Icon;
import com.cheatprogram.util.Anim;
import com.cheatprogram.util.ProjectRepository;

/** The project attribution is part of the panel chrome, independent of profiles and themes. */
final class RepositoryBadge {
    private RepositoryBadge() { }

    static void draw(GuiBase panel, float minLeft, float right, float top, Runnable open, java.util.function.Consumer<String> tooltip) {
        String label = ProjectRepository.label();
        float starWidth = 22;
        float gap = 6;
        float available = right - minLeft - starWidth - gap;
        float size = 8.5f;
        // Keep the complete address readable, including with wider custom fonts.
        while (size > 5.5f && panel.c.textWidth(Fonts.Face.REGULAR, label, size) + 32 > available) size -= .25f;
        float width = Math.min(panel.c.textWidth(Fonts.Face.REGULAR, label, size) + 32, Math.max(32, available));
        float x = right - width;
        float starX = x - gap - starWidth;
        boolean starHovered = panel.over(starX, top, starWidth, 22);
        float starHover = panel.hoverAnim("hdr.repository.star", starHovered);
        panel.c.lens(starX, top, starWidth, 22, 11, Theme.lift(.045f + starHover * .075f), Theme.lensGlass(panel.c.scale));
        panel.c.icon(Icon.STAR, starX + starWidth / 2, top + 11, 12 + starHover * 1.5f,
                Anim.lerpColor(Theme.TEXT_2, 0xFFFFD277, starHover));
        if (starHovered) tooltip.accept(Lang.t("ui.repository.starHint"));
        if (!panel.hoverBlocked) panel.hit(starX, top, starWidth, 22).left(() -> {
            Notifications.push(Icon.STAR, Lang.t("ui.repository.starThanks"), Lang.t("ui.repository.starAction"));
            open.run();
        });

        boolean hovered = panel.over(x, top, width, 22);
        float hover = panel.hoverAnim("hdr.repository", hovered);
        panel.c.lens(x, top, width, 22, 11, Theme.lift(.045f + hover * .055f), Theme.lensGlass(panel.c.scale));
        int color = Anim.lerpColor(Theme.TEXT_2, Theme.TEXT, hover);
        panel.c.icon(Icon.LINK, x + 12, top + 11, 10, color);
        String displayed = panel.c.ellipsize(Fonts.Face.REGULAR, label, size, width - 32);
        panel.c.textC(Fonts.Face.REGULAR, displayed, x + 23, top + 11, size, color);
        if (hovered) tooltip.accept(ProjectRepository.uri().toASCIIString());
        if (!panel.hoverBlocked) panel.hit(x, top, width, 22).left(open);
    }
}
