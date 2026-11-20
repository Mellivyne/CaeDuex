package com.cheatprogram.gui;

import com.cheatprogram.core.Lang;
import com.cheatprogram.media.LrcLyrics;
import com.cheatprogram.media.player.MusicArtworkCache;
import com.cheatprogram.media.player.MusicModels.Artist;
import com.cheatprogram.media.player.MusicModels.Track;
import com.cheatprogram.media.player.MusicPlayer;
import com.cheatprogram.module.visual.Personal;
import com.cheatprogram.render.Backdrop;
import com.cheatprogram.render.Canvas;
import com.cheatprogram.render.Icon;
import com.cheatprogram.render.PersonalAvatar;
import com.cheatprogram.render.R2D;
import com.cheatprogram.util.Anim;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongConsumer;

/** The full-width song view shares the music player's playback bar and browsing session. */
final class MusicTrackDetails {
    private enum Tab { LYRICS, INFO, SIMILAR }

    private final GuiBase gui;
    private final Canvas canvas;
    private final MusicArtistCatalog catalog;
    private final GuiBase.Scroller lyricScroll = new GuiBase.Scroller();
    private final GuiBase.Scroller similarScroll = new GuiBase.Scroller();
    private Track track;
    private CompletableFuture<MusicPlayer.TrackLyrics> lyricRequest;
    private CompletableFuture<List<Track>> similarRequest;
    private LrcLyrics shownLyrics;
    private LrcLyrics shownTranslation;
    private List<LyricRow> lyricRows = List.of();
    private float lyricContentHeight;
    private float typographyScale;
    private int activeLine = -2;
    private boolean following = true;
    private int color = 0xff5c526b;
    private float recordAngle;
    private Tab tab = Tab.LYRICS;
    private record LyricRow(LrcLyrics.Line source, List<String> original, List<String> translation, float offset, float height) {}

    MusicTrackDetails(GuiBase gui) {
        this.gui = gui;
        canvas = gui.c;
        catalog = new MusicArtistCatalog(gui);
    }

    Track track() { return track; }

    void open(Track next) {
        if (track != null && track.key().equals(next.key())) return;
        cancelRequests();
        track = next;
        recordAngle = 0;
        resetLyrics();
    }

    void close() {
        cancelRequests();
        track = null;
        catalog.close();
        tab = Tab.LYRICS;
        resetLyrics();
    }

    boolean back() { return catalog.back(); }

    void resetTypography() { typographyScale = 0; }

    private void resetLyrics() {
        shownLyrics = null;
        shownTranslation = null;
        lyricRows = List.of();
        lyricScroll.target = 0;
        lyricScroll.value.snap(0);
        similarScroll.target = 0;
        similarScroll.value.snap(0);
        following = true;
        activeLine = -2;
    }

    private void cancelRequests() {
        if (lyricRequest != null) lyricRequest.cancel(false);
        if (similarRequest != null) similarRequest.cancel(false);
        lyricRequest = null;
        similarRequest = null;
    }

    void draw(MusicArtworkCache artwork, MusicPlayer player, MusicPlayer.TrackLyrics liveLyrics,
              float height, long position, long duration, boolean playing,
              Runnable back, Runnable comments, Runnable albumComments, Runnable platform,
              LongConsumer seek, java.util.function.Consumer<Track> openSimilar, Runnable enablePlayback) {
        var image = artwork.artwork(track.provider(), track.cover());
        int dominant = artwork.dominantColor(track.provider(), track.cover());
        color = Anim.lerpColor(color, dominant, 1 - (float) Math.exp(-gui.dt * 9));
        float foot = height - 88;
        drawGlass(height, !catalog.open());
        if (playing) recordAngle = (recordAngle + Math.min(.1f, gui.dt) * 30) % 360;

        gui.button("music.detail.back", Lang.t("ui.back"), Icon.CHEVRON_DOWN, 28, 24, 116, 32, false, () -> { if (!catalog.back()) back.run(); });
        canvas.textRight(GuiBase.MED, track.provider().label, 1082, 40, 11, Theme.TEXT_2);
        if (foot < 147) return;
        if (catalog.open()) {
            catalog.draw(artwork, player, foot, enablePlayback);
            return;
        }
        float recordY = (foot + 52) / 2;
        float radius = Math.min(214, (foot - 79) * .43f);
        drawRecord(radius, recordY, image == null, () -> {
            float coverRadius = radius * .69f;
            PersonalAvatar.drawImage(canvas, image, 1, 1, Personal.AvatarCrop.FULL,
                    290 - coverRadius, recordY - coverRadius, 2 * coverRadius, 2 * coverRadius, coverRadius, 1, 0, recordAngle);
        });
        canvas.textC(GuiBase.BOLD, canvas.ellipsize(GuiBase.BOLD, track.title(), 27, 500), 559, 86, 27, Theme.TEXT);
        drawArtists(player);

        boolean similar = player.supportsSimilarTracks(track);
        if (!similar && tab == Tab.SIMILAR) tab = Tab.LYRICS;
        gui.button("music.detail.lyrics", Lang.t("ui.music.detailLyrics"), null, 557, 143, 92, 29, tab == Tab.LYRICS, () -> tab = Tab.LYRICS);
        gui.button("music.detail.info", Lang.t("ui.music.detailInfo"), null, 655, 143, 98, 29, tab == Tab.INFO, () -> tab = Tab.INFO);
        if (similar) gui.button("music.detail.similar", Lang.t("ui.music.detailSimilar"), null, 759, 143, 114, 29,
                tab == Tab.SIMILAR, () -> tab = Tab.SIMILAR);
        if (comments != null) gui.button("music.detail.comments", Lang.t("ui.music.comments"), null,
                964, 143, 114, 29, false, comments);
        float bodyTop = 195;
        float bodyBottom = foot - 19;
        if (bodyBottom - bodyTop < 24) return;
        switch (tab) {
            case LYRICS -> {
                boolean translations = player.capabilities().translation();
                if (lyricRequest == null && (liveLyrics == null || liveLyrics.original().isEmpty()
                        || translations && liveLyrics.translated().isEmpty()))
                    lyricRequest = player.previewLyrics(track, translations);
                MusicPlayer.TrackLyrics preview = lyricRequest == null ? null : lyricRequest.getNow(null);
                MusicPlayer.TrackLyrics lyrics = liveLyrics != null && !liveLyrics.original().isEmpty() ? liveLyrics : preview;
                drawLyrics(lyrics, preview, bodyTop, bodyBottom, position, duration, seek);
            }
            case INFO -> drawInfo(bodyTop, bodyBottom, duration, platform, albumComments);
            case SIMILAR -> {
                if (similarRequest == null) similarRequest = player.similarTracks(track);
                drawSimilar(artwork, bodyTop, bodyBottom, openSimilar);
            }
        }
    }

    private void drawGlass(float height, boolean showTrack) {
        // The inner surfaces and their controls sample the same tinted glass, so a button
        // cannot reveal an unfiltered patch of the world through the panel.
        Backdrop.capture(Math.max(.28f, Math.min(.40f, Theme.frost())));
        R2D.applyState();
        canvas.shadow(0, 7, 1120, height, 25, 26, 0x3b000000);
        glass(0, 0, 1120, height, 25, .42f);
        float bodyBottom = height - 98;
        if (showTrack && bodyBottom > 162) glass(538, 58, 559, bodyBottom - 58, 21, .44f);
        glass(20, height - 84, 1080, 73, 19, .44f);
        Backdrop.capture(0);
        R2D.applyState();
    }

    void glass(float x, float y, float width, float height, float radius, float opacity) {
        int tint = Anim.lerpColor(0xff181d2a, color, .20f);
        int top = R2D.withAlpha(tint, opacity);
        int bottom = R2D.withAlpha(Anim.lerpColor(tint, 0xff121521, .36f), opacity + .08f);
        float scale = canvas.scale;
        float bend = Theme.lensGlass(scale).refraction();
        canvas.glass(x, y, width, height, radius, top, bottom,
                new R2D.GlassStyle(bend, 14 * scale, 0, 2.8f * scale, 0, .60f));
    }

    int playbackAccent() {
        return Anim.lerpColor(0xfff38799, Anim.lerpColor(color, 0xffffffff, .50f), .30f);
    }

    private void drawRecord(float radius, float y, boolean noImage, Runnable cover) {
        float x = 290;
        canvas.shadow(x - radius, y - radius + 9, 2 * radius, 2 * radius, radius, 31, 0x68000000);
        glass(x - radius - 5, y - radius - 5, 2 * radius + 10, 2 * radius + 10,
                radius + 5, .29f);
        canvas.rrect(x - radius, y - radius, 2 * radius, 2 * radius, radius, 0xff252632, 0xff111118);
        for (int groove = 0; groove < 9; groove++) {
            float ring = radius * (.97f - groove * .032f);
            canvas.rrect4(x - ring, y - ring, 2 * ring, 2 * ring, ring, ring, ring, ring,
                    0, 0, .65f * canvas.scale, 0x29ffffff, 0x08000000);
        }
        if (noImage) {
            canvas.circle(x, y, radius * .69f, Anim.lerpColor(0xff262533, color, .65f));
            canvas.icon(Icon.MUSIC, x, y, radius * .41f, Theme.TEXT_2);
        } else cover.run();
    }

    private void drawArtists(MusicPlayer player) {
        List<Artist> artists = player.artists(track);
        if (artists.isEmpty()) {
            canvas.textC(GuiBase.REG, canvas.ellipsize(GuiBase.REG, track.artist(), 13, 500),
                    559, 116, 13, Theme.TEXT_2);
            return;
        }
        float x = 557;
        float width = Math.min(500, (514f - (artists.size() - 1) * 6) / artists.size());
        for (Artist artist : artists) {
            if (width < 32) break;
            float buttonWidth = Math.min(width, canvas.textWidth(GuiBase.MED, artist.name(), 10) + 34);
            gui.button("music.artist." + artist.key(), canvas.ellipsize(GuiBase.MED, artist.name(), 10, buttonWidth - 29),
                    Icon.USER, x, 106, buttonWidth, 26, false, () -> catalog.open(artist));
            x += buttonWidth + 6;
        }
    }

    private void drawInfo(float top, float bottom, long duration, Runnable platform, Runnable albumComments) {
        canvas.clip(549, top, 534, bottom - top);
        gui.pushHitClip(549, top, 534, bottom - top);
        try {
            infoLine("ui.music.artist", track.artist(), top + 24);
            infoLine("ui.music.album", track.album(), top + 54);
            infoLine("ui.music.duration", duration > 0
                    ? String.format(java.util.Locale.ROOT, "%d:%02d", duration / 60000, duration / 1000 % 60) : "", top + 84);
            if (platform != null) gui.button("music.detail.platform", Lang.t("ui.music.commentOpenPlatform"), Icon.LINK,
                    568, top + 117, 160, 31, false, platform);
            if (albumComments != null) gui.button("music.detail.albumComments", Lang.t("ui.music.commentAlbum"), null,
                    739, top + 117, 151, 31, false, albumComments);
        } finally {
            gui.popHitClip();
            canvas.unclip();
        }
    }

    private void infoLine(String key, String value, float y) {
        if (value == null || value.isBlank()) return;
        canvas.textC(GuiBase.REG, Lang.t(key), 570, y, 11, Theme.TEXT_3);
        canvas.textC(GuiBase.MED, canvas.ellipsize(GuiBase.MED, value, 13, 397), 662, y, 13, Theme.TEXT);
    }

    private void drawLyrics(MusicPlayer.TrackLyrics lyrics, MusicPlayer.TrackLyrics preview,
                            float top, float bottom, long position, long duration, LongConsumer seek) {
        if (lyrics == null || lyrics.original().isEmpty()) {
            canvas.textC(GuiBase.REG, Lang.t(lyricRequest != null && !lyricRequest.isDone()
                    ? "ui.music.loading" : "ui.musicPlayer.noLyrics"), 560, top + 24, 13, Theme.TEXT_2);
            return;
        }
        LrcLyrics original = lyrics.original();
        LrcLyrics translation = lyrics.translated().isEmpty() && preview != null ? preview.translated() : lyrics.translated();
        if (original != shownLyrics || translation != shownTranslation || typographyScale != canvas.scale) {
            layoutLyrics(original, translation);
        }
        float view = bottom - top;
        int lineIndex = seek == null ? -1 : original.lineAt(position / 1000d, duration / 1000d);
        lyricScroll.max = Math.max(0, lyricContentHeight - view);
        if (following && lineIndex != activeLine && lineIndex >= 0) {
            lyricScroll.target = lyricRows.get(lineIndex).offset() - view * .34f;
        }
        activeLine = lineIndex;
        lyricScroll.target = Math.max(0, Math.min(lyricScroll.max, lyricScroll.target));
        lyricScroll.value.to(lyricScroll.target, gui.dt, 8.5f);
        float scroll = Math.min(lyricScroll.get(), lyricScroll.max);
        gui.hit(550, top, 540, view).scroll(amount -> { following = false; lyricScroll.scroll(amount); });
        canvas.clip(550, top, 540, view);
        gui.pushHitClip(550, top, 540, view);
        try {
            for (int index = firstLyricRow(scroll); index < lyricRows.size(); index++) {
                LyricRow row = lyricRows.get(index);
                float y = top + row.offset() - scroll;
                if (y >= bottom) break;
                boolean active = index == lineIndex;
                float emphasis = gui.anim("music.detail.lyric." + index, active ? 1 : 0, 8);
                float inset = 9 * emphasis;
                float edge = Math.min(1, Math.min((y + row.height() - top) / 30, (bottom - y) / 36));
                float visibility = Math.max(0, edge) * (.58f + .42f * emphasis);
                if (emphasis > .01f) {
                    float glowHeight = Math.max(17, row.height() - 17);
                    canvas.shadow(553, y + 8, 3, glowHeight, 1.5f, 11, Anim.white(emphasis * .17f));
                    canvas.rrect(553, y + 8, 2.5f, glowHeight, 1.25f, Anim.white(emphasis * .87f));
                }
                if (seek != null && row.source() != null) {
                    if (!active && gui.over(552, y, 528, row.height())) canvas.rrect(552, y, 528, row.height() - 3, 11, 0x12ffffff);
                    gui.hit(552, y, 528, row.height()).left(() -> {
                        following = true;
                        activeLine = -2;
                        seek.accept(Math.round(row.source().start() * 1000));
                    });
                }
                float lineY = y + 17;
                for (String value : row.original()) {
                    if (lineY >= top - 11 && lineY <= bottom + 11)
                        canvas.textC(GuiBase.BOLD, value, 563 + inset, lineY, 14 + 2.5f * emphasis,
                                R2D.withAlpha(Theme.TEXT, visibility));
                    lineY += 21;
                }
                for (String value : row.translation()) {
                    if (lineY >= top - 11 && lineY <= bottom + 11)
                        canvas.textC(GuiBase.REG, value, 563 + inset, lineY - 2, 10,
                                R2D.withAlpha(Theme.TEXT_2, visibility * .82f));
                    lineY += 15;
                }
            }
        } finally {
            gui.popHitClip();
            canvas.unclip();
        }
        gui.panelScrollBar("music.detail.lyrics.scroll", lyricScroll, 1095, top, view);
        if (!following && seek != null)
            gui.button("music.detail.follow", Lang.t("ui.music.detailFollow"), Icon.TARGET, 942, bottom - 27, 138, 26, false,
                    () -> { following = true; activeLine = -2; });
    }

    private void layoutLyrics(LrcLyrics original, LrcLyrics translated) {
        shownLyrics = original;
        shownTranslation = translated;
        typographyScale = canvas.scale;
        var rows = new java.util.ArrayList<LyricRow>();
        float offset = 0;
        if (original.synced()) {
            for (var line : original.lines()) {
                int at = translated.lineAt(line.start() + .10);
                String other = at < 0 || Math.abs(translated.lines().get(at).start() - line.start()) > .8
                        ? line.translation() : translated.lines().get(at).text();
                var text = canvas.wrap(GuiBase.BOLD, line.text(), 16.5f, 487);
                var translation = other == null || other.isBlank() ? List.<String>of() : canvas.wrap(GuiBase.REG, other, 10, 501);
                float height = 14 + 21 * text.size() + 15 * translation.size();
                rows.add(new LyricRow(line, text, translation, offset, height));
                offset += height;
            }
        } else {
            for (String line : canvas.wrap(GuiBase.BOLD, original.plainText(), 16.5f, 487)) {
                rows.add(new LyricRow(null, List.of(line), List.of(), offset, 32));
                offset += 32;
            }
        }
        lyricRows = List.copyOf(rows);
        lyricContentHeight = offset;
        activeLine = -2;
    }

    private int firstLyricRow(float scroll) {
        int low = 0, high = lyricRows.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            LyricRow row = lyricRows.get(middle);
            if (row.offset() + row.height() <= scroll) low = middle + 1;
            else high = middle;
        }
        return low;
    }

    private void drawSimilar(MusicArtworkCache artwork, float top, float bottom, java.util.function.Consumer<Track> open) {
        List<Track> results = similarRequest.getNow(null);
        if (results == null || results.isEmpty()) {
            canvas.textC(GuiBase.REG, Lang.t(results == null ? "ui.music.loading" : "ui.music.noTracks"), 561, top + 24, 13, Theme.TEXT_2);
            return;
        }
        float view = bottom - top;
        similarScroll.max = Math.max(0, results.size() * 54 - view);
        similarScroll.update(gui.dt);
        gui.hit(550, top, 540, view).scroll(similarScroll::scroll);
        canvas.clip(550, top, 540, view);
        gui.pushHitClip(550, top, 540, view);
        try {
            int first = Math.max(0, (int) (similarScroll.get() / 54));
            int last = Math.min(results.size(), first + (int) (view / 54) + 2);
            for (int index = first; index < last; index++) {
                Track item = results.get(index);
                float y = top + index * 54 - similarScroll.get();
                canvas.rrect(552, y + 1, 527, 49, 10, gui.over(552, y, 527, 50) ? 0x2bffffff : 0x12ffffff);
                var image = artwork.artwork(item.provider(), item.cover());
                if (image != null) R2D.texture(image, canvas.px(561), canvas.py(y + 7), 36 * canvas.scale, 36 * canvas.scale,
                        0, 0, 1, 1, 0xffffffff);
                else canvas.icon(Icon.MUSIC, 579, y + 25, 18, Theme.TEXT_3);
                canvas.textC(GuiBase.MED, canvas.ellipsize(GuiBase.MED, item.title(), 12, 424), 611, y + 17, 12, Theme.TEXT);
                canvas.textC(GuiBase.REG, canvas.ellipsize(GuiBase.REG, item.artist(), 9, 424), 611, y + 35, 9, Theme.TEXT_3);
                canvas.icon(Icon.CHEVRON_RIGHT, 1060, y + 25, 13, Theme.TEXT_2);
                gui.hit(552, y, 527, 50).left(() -> open.accept(item));
            }
        } finally {
            gui.popHitClip();
            canvas.unclip();
        }
        gui.panelScrollBar("music.detail.similar.scroll", similarScroll, 1095, top, view);
    }
}
