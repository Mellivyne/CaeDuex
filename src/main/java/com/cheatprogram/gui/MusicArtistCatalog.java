package com.cheatprogram.gui;

import com.cheatprogram.core.Lang;
import com.cheatprogram.media.player.MusicArtworkCache;
import com.cheatprogram.media.player.MusicModels.Artist;
import com.cheatprogram.media.player.MusicModels.Recommendation;
import com.cheatprogram.media.player.MusicModels.Track;
import com.cheatprogram.media.player.MusicPlayer;
import com.cheatprogram.render.Canvas;
import com.cheatprogram.render.Icon;
import com.cheatprogram.render.R2D;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/** Catalog navigation is independent of the active library and playback queue. */
final class MusicArtistCatalog {
    private final GuiBase gui;
    private final Canvas canvas;
    private final GuiBase.Scroller scroll = new GuiBase.Scroller();
    private final List<Track> songs = new ArrayList<>();
    private final List<Recommendation> albums = new ArrayList<>();
    private Artist artist;
    private Recommendation album;
    private boolean showingAlbums;
    private int songsPage, albumsPage;
    private boolean moreSongs, moreAlbums;
    private CompletableFuture<?> pending;
    private List<Track> albumTracks;

    MusicArtistCatalog(GuiBase gui) {
        this.gui = gui;
        canvas = gui.c;
    }

    boolean open() { return artist != null; }

    void open(Artist next) {
        close();
        artist = next;
        moreSongs = true;
        moreAlbums = true;
    }

    boolean back() {
        if (album != null) {
            cancelPending();
            album = null;
            albumTracks = null;
            resetScroll();
            return true;
        }
        if (artist == null) return false;
        close();
        return true;
    }

    void close() {
        cancelPending();
        artist = null;
        album = null;
        albumTracks = null;
        songs.clear();
        albums.clear();
        songsPage = albumsPage = 0;
        showingAlbums = false;
        resetScroll();
    }

    private void cancelPending() {
        // The provider operation may finish, but it cannot populate a closed page.
        pending = null;
    }

    private void resetScroll() {
        scroll.target = 0;
        scroll.value.snap(0);
    }

    void draw(MusicArtworkCache artwork, MusicPlayer player, float bottom, Runnable enablePlayback) {
        canvas.textC(GuiBase.BOLD, canvas.ellipsize(GuiBase.BOLD, album == null ? artist.name() : album.name(), 25, 1010),
                44, 89, 25, Theme.TEXT);
        if (album == null) {
            gui.button("music.artist.songs", Lang.t("ui.music.artistSongs"), Icon.MUSIC, 42, 113, 124, 29,
                    !showingAlbums, () -> selectTab(false));
            gui.button("music.artist.albums", Lang.t("ui.music.artistAlbums"), Icon.FOLDER, 174, 113, 124, 29,
                    showingAlbums, () -> selectTab(true));
            if (pending == null && (showingAlbums ? albumsPage == 0 && moreAlbums : songsPage == 0 && moreSongs)) {
                loadPage(player);
            }
        } else {
            canvas.textC(GuiBase.MED, artist.name(), 44, 124, 11, Theme.TEXT_2);
        }
        boolean albumsTab = showingAlbums && album == null;
        List<Track> visibleTracks = album == null ? songs : albumTracks == null ? List.of() : albumTracks;
        int count = albumsTab ? albums.size() : visibleTracks.size();
        boolean more = album == null && (albumsTab ? moreAlbums : moreSongs);
        float top = 154, view = bottom - top - 12;
        if (view < 30) return;
        if (count == 0 && (pending != null || !more)) {
            canvas.textC(GuiBase.REG, Lang.t(pending != null ? "ui.music.loading" : "ui.music.artistEmpty"),
                    46, top + 25, 13, Theme.TEXT_2);
            return;
        }
        float row = 52;
        scroll.max = Math.max(0, count * row + (more ? 38 : 0) - view);
        scroll.update(gui.dt);
        gui.hit(41, top, 1038, view).scroll(scroll::scroll);
        canvas.clip(41, top, 1038, view);
        gui.pushHitClip(41, top, 1038, view);
        try {
            int first = Math.max(0, (int) (scroll.get() / row));
            int last = Math.min(count, first + (int) (view / row) + 2);
            for (int index = first; index < last; index++) {
                float y = top + index * row - scroll.get();
                if (albumsTab) {
                    Recommendation item = albums.get(index);
                    drawRow(artwork, item.provider(), item.cover(), item.name(), artist.name(), y);
                    gui.hit(44, y, 1027, 47).left(() -> loadAlbum(player, item));
                } else {
                    Track track = visibleTracks.get(index);
                    drawRow(artwork, track.provider(), track.cover(), track.title(), track.album(), y);
                    int selected = index;
                    gui.hit(44, y, 1027, 47).left(() -> {
                        enablePlayback.run();
                        player.playCollection(visibleTracks, selected);
                    });
                }
            }
            float moreY = top + count * row - scroll.get();
            if (more && moreY + 28 > top && moreY < top + view) {
                gui.button("music.artist.more", Lang.t(pending == null ? "ui.music.artistMore" : "ui.music.loading"),
                        Icon.CHEVRON_DOWN, 44, moreY, 140, 28, false, () -> {
                            if (pending == null) loadPage(player);
                        });
            }
        } finally {
            gui.popHitClip();
            canvas.unclip();
        }
        gui.panelScrollBar("music.artist.scroll", scroll, 1086, top, view);
    }

    private void drawRow(MusicArtworkCache artwork, com.cheatprogram.media.player.MusicModels.Provider provider,
                         String cover, String title, String subtitle, float y) {
        canvas.lens(44, y, 1027, 47, 12, Theme.lift(gui.over(44, y, 1027, 47) ? .12f : .045f),
                Theme.lensGlass(canvas.scale));
        var image = artwork.artwork(provider, cover);
        if (image == null) canvas.icon(Icon.MUSIC, 71, y + 24, 19, Theme.TEXT_3);
        else R2D.texture(image, canvas.px(54), canvas.py(y + 6), 35 * canvas.scale, 35 * canvas.scale,
                0, 0, 1, 1, 0xffffffff);
        canvas.textC(GuiBase.MED, canvas.ellipsize(GuiBase.MED, title, 13, 910), 102, y + 15, 13, Theme.TEXT);
        canvas.textC(GuiBase.REG, canvas.ellipsize(GuiBase.REG, subtitle, 9, 910), 102, y + 33, 9, Theme.TEXT_2);
        canvas.icon(Icon.CHEVRON_RIGHT, 1048, y + 23, 13, Theme.TEXT_2);
    }

    private void selectTab(boolean selected) {
        if (showingAlbums == selected) return;
        cancelPending();
        showingAlbums = selected;
        resetScroll();
    }

    private void loadPage(MusicPlayer player) {
        if (showingAlbums) {
            var request = player.artistAlbums(artist, albumsPage);
            pending = request;
            request.whenComplete((page, error) -> gui.mc.execute(() -> {
                if (pending != request) return;
                pending = null;
                if (page != null) albums.addAll(page.albums());
                albumsPage++;
                moreAlbums = page != null && page.hasMore() && !page.albums().isEmpty();
            }));
        } else {
            var request = player.artistSongs(artist, songsPage);
            pending = request;
            request.whenComplete((page, error) -> gui.mc.execute(() -> {
                if (pending != request) return;
                pending = null;
                if (page != null) songs.addAll(page.tracks());
                songsPage++;
                moreSongs = page != null && page.hasMore() && !page.tracks().isEmpty();
            }));
        }
    }

    private void loadAlbum(MusicPlayer player, Recommendation item) {
        album = item;
        albumTracks = null;
        resetScroll();
        var request = player.artistAlbumTracks(item);
        pending = request;
        request.whenComplete((tracks, error) -> gui.mc.execute(() -> {
            if (pending != request) return;
            pending = null;
            albumTracks = tracks == null ? List.of() : tracks;
        }));
    }
}
