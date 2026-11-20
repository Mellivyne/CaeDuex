package com.cheatprogram.gui;



import com.cheatprogram.core.ClientSettings;
import com.cheatprogram.core.Config;
import com.cheatprogram.core.Lang;

import com.cheatprogram.media.player.MusicArtworkCache;

import com.cheatprogram.media.player.MusicModels.*;

import com.cheatprogram.media.player.MusicPlayer;

import com.cheatprogram.media.player.MusicNetwork;

import com.cheatprogram.module.visual.FloatingSubtitles;

import com.cheatprogram.render.Backdrop;
import com.cheatprogram.render.Icon;

import com.cheatprogram.render.AlbumWallModel;

import com.cheatprogram.render.MusicCoverCarouselRenderer;

import com.cheatprogram.render.R2D;
import com.cheatprogram.util.Anim;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import net.minecraft.client.gui.screens.Screen;

import net.minecraft.resources.Identifier;

import org.lwjgl.glfw.GLFW;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import java.util.LinkedHashMap;
import java.util.Map;


/** The floating-subtitle module's main page: account library, perspective covers and playback. */

public final class MusicPlayerScreen extends GuiBase {

    private static MusicPlayerScreen suspended;
    private ClientLevel displayLevel;
    private final Screen parent;private final FloatingSubtitles module;

    private final MusicPlayer player=MusicPlayer.INSTANCE;

    private MusicArtworkCache artwork=new MusicArtworkCache();

    private final MusicCoverCarouselRenderer covers=new MusicCoverCarouselRenderer();

    private MusicCoverCarouselRenderer.Motion motion=new MusicCoverCarouselRenderer.Motion();

    private com.cheatprogram.render.AlbumSpaceModel space=new com.cheatprogram.render.AlbumSpaceModel();

    private AlbumWallModel wall=new AlbumWallModel();

    private final MusicCoverCarouselRenderer.ControlMotion controls=new MusicCoverCarouselRenderer.ControlMotion();

    private final MusicCoverCarouselRenderer.ContentFade<Track> footerFade=new MusicCoverCarouselRenderer.ContentFade<>(),titleFade=new MusicCoverCarouselRenderer.ContentFade<>();

    private Scroller songs=new Scroller(),playlists=new Scroller();
    private final Anim songListExpansion=new Anim(0);
    private boolean songListExpanded;
    private float carouselHitHeight;

    private final SeekPreview seek=new SeekPreview();
    private final MusicTrackDetails detail=new MusicTrackDetails(this);
    private String detailPlaybackKey="";
    private List<Track> detailCollection = List.of();
    private int detailCollectionIndex = -1;

    private final TextField searchField=new TextField(120);

    private record SearchReturn(MusicPlayer.LibraryPage page,List<Track> tracks,Provider provider,String activePlaylist,CommentTarget activeCollection,boolean showPlaylists,boolean recommendationDetail,String layout,MusicCoverCarouselRenderer.Motion motion,AlbumWallModel wall,com.cheatprogram.render.AlbumSpaceModel space,Scroller songs,Scroller playlists) {}
    private SearchReturn searchReturn;

    private MusicPlayer.LibraryPage playlistReturn;
    private final Scroller navigationScroll=new Scroller(),commentScroll=new Scroller();
    private CommentTarget commentTarget,activeCollection,pendingRecommendation;
    private final TextField commentComposer=new TextField(500);
    private final Map<CommentDraftKey,String> commentDrafts=new LinkedHashMap<>();
    private record CommentDraftKey(Provider provider,CommentKind kind,String id,String account,String replyTo) { }
    private Comment replyingTo;
    private CommentDraftKey commentDraftKey;
    private List<Comment> measuredComments=List.of();
    private List<CommentRow> measuredRows=List.of();
    private float commentContentHeight,commentScale;
    private static final DateTimeFormatter COMMENT_DATE=DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());
    private record CommentRow(List<String> lines,List<String> quote,float offset,float height) { }
    private final Scroller recommendationScroll=new Scroller();
    private java.util.concurrent.CompletableFuture<Boolean> recommendationOpen;
    private boolean showRecommendations,recommendationDetail;
    private String catalogAccount="";
    private Provider catalogProvider;
    private boolean catalogReady;

    private List<Track> observed=List.of();private Provider provider;private String playingKey="";

    private static double volume=.8;private String activePlaylist="",layout="";private boolean showPlaylists,coverDragging,qualityMenu;private float carouselY,carouselH;

    public MusicPlayerScreen(Screen parent,FloatingSubtitles module){super(Lang.t("ui.music.title"));this.parent=parent;this.module=module;searchField.onEnter=this::runSearch;commentComposer.onChange=this::rememberCommentDraft;}
    @Override protected void init(){displayLevel=mc.level;if(artwork==null)artwork=new MusicArtworkCache();Snapshot data=player.snapshot();Track current=data.playback()==null?null:data.playback().track();int selected=0;if(current!=null)for(int i=0;i<data.tracks().size();i++)if(current.key().equals(data.tracks().get(i).key())){selected=i;break;}artwork.preload(data.tracks(),selected,8);}

    @Override public void render(GuiGraphicsExtractor graphics,int mouseX,int mouseY,float partial){

        beginFrame();if(!preparePanelBackground(graphics,partial)){endFrame();return;}R2D.begin(graphics);

        try{

            float h=layoutPanel(1120,780);Snapshot data=player.snapshot();sync(data);
            if(detail.track()!=null&&commentTarget==null){
                drawTrackDetails(data,h);footer(data,h);qualityControl(data,h);
                return;
            }
            drawPanelSurface(graphics,1120,h,22);

            c.rrect(10,10,184,h-20,16,0x28000000);

            c.icon(Icon.MUSIC,35,36,18,0xffec5267);c.textC(BOLD,Lang.t("ui.music.title"),53,36,15,Theme.TEXT);

            c.textC(REG,Lang.t("ui.music.subtitle"),24,64,8,Theme.TEXT_3);

            sidebar(data,h);
            main(graphics,data,h);footer(data,h);qualityControl(data,h);
        }finally{R2D.end();endFrame();}
    }
    private void sidebar(Snapshot data,float h){
        final float top=82,view=h-98,likedY=91+Provider.values().length*43+23;
        float footerStart=Math.max(likedY+130,h-178);
        navigationScroll.max=Math.max(0,footerStart+143-top-view);
        navigationScroll.update(dt);
        hit(18,top,172,view).scroll(navigationScroll::scroll);
        c.clip(18,top,172,view);
        pushHitClip(18,top,172,view);
        try{
            float scroll=navigationScroll.get(),py=91-scroll;
            for(Provider value:Provider.values()){
                boolean selected=data.provider()==value;

                if(selected)c.rrect(20,py,164,35,9,0x33ef5268);

                c.icon(value==Provider.NETEASE?Icon.NETEASE:Icon.MUSIC,37,py+17,13,selected?0xfffb7185:Theme.TEXT_3);

                c.textC(selected?MED:REG,value.label,53,py+17,10.5f,selected?Theme.TEXT:Theme.TEXT_2);

                hit(20,py,164,35).left(()->selectProvider(value));py+=43;
            }

            c.rect(27,py+3,145,1,0x15ffffff);
            float first=likedY-scroll;
            button("music.liked",Lang.t("ui.music.liked"),Icon.STAR,21,first,162,32,
                    !showPlaylists&&activePlaylist.isBlank()&&!showRecommendations&&!recommendationDetail,this::openLiked);
            button("music.playlists",Lang.t("ui.music.playlists"),Icon.FOLDER,21,first+42,162,32,
                    showPlaylists||!activePlaylist.isBlank(),this::openPlaylists);
            button("music.recommend",Lang.t("ui.music.recommend"),Icon.SPARKLE,21,first+84,162,32,
                    showRecommendations||recommendationDetail,this::openRecommendations);
            float footerY=footerStart-scroll;
            button("music.world",Lang.t("ui.music.worldOpen"),Icon.MUSIC,21,footerY,162,29,false,()->{if(!module.isEnabled())module.setEnabled(true);module.worldMusic.set(true);mc.setScreen(null);com.cheatprogram.render.WorldMusicController.open();});
            button("music.preview",Lang.t("ui.lyrics.preview"),Icon.EYE,21,footerY+38,162,29,false,()->{module.preview();mc.setScreen(null);});
            button("music.captions",Lang.t("ui.music.captions"),Icon.GEAR,21,footerY+76,162,31,false,()->mc.setScreen(new FloatingSubtitlesScreen(this,module)));
            button("music.caption.enable",Lang.t(module.isEnabled()?"ui.lyrics.disable":"ui.lyrics.enable"),Icon.POWER,21,footerY+114,162,29,module.isEnabled(),module::toggle);
        }finally{popHitClip();c.unclip();}
        panelScrollBar("music.sidebar.scroll",navigationScroll,187,top,view);
    }
    private void selectProvider(Provider value){
        if(player.snapshot().provider()==value)return;
        closeTrackDetails();
        closeComments();
        focused=null;searchReturn=null;playlistReturn=null;showPlaylists=false;activePlaylist="";
        recommendationDetail=false;recommendationOpen=null;pendingRecommendation=null;activeCollection=null;
        player.selectProvider(value);
    }
    private void openLiked(){
        closeTrackDetails();
        closeComments();leaveRecommendations();focused=null;searchReturn=null;playlistReturn=null;
        showPlaylists=false;activePlaylist="";songs.target=0;
        player.refreshLiked();
    }
    private void openPlaylists(){
        closeTrackDetails();
        closeComments();leaveRecommendations();focused=null;searchReturn=null;
        boolean returned=playlistReturn!=null&&player.restoreLibraryPage(playlistReturn);
        playlistReturn=null;activePlaylist="";activeCollection=null;showPlaylists=true;
        if(!returned)player.loadPlaylists();
    }
    private void openPlaylist(Playlist list){
        playlistReturn=player.captureLibraryPage();
        showPlaylists=false;activePlaylist=list.name();activeCollection=CommentTarget.playlist(player.snapshot().provider(),list);
        songs.target=0;focused=null;
        player.openPlaylist(list);
    }

    private void sync(Snapshot data){

        if(commentTarget!=null){
            String account=data.account()==null?"":data.account().id();
            if(commentTarget.provider()!=data.provider()||commentDraftKey==null||!commentDraftKey.account().equals(account)
                    ||player.comments().target()==null)closeComments();
        }
        if(showRecommendations){
            String accountId=data.account()==null?"":data.account().id();
            boolean ready=player.recommendationAccountReady();
            if(catalogProvider!=data.provider()||!catalogAccount.equals(accountId)||ready!=catalogReady){
                catalogProvider=data.provider();catalogAccount=accountId;catalogReady=ready;
                recommendationOpen=null;pendingRecommendation=null;recommendationScroll.target=0;player.loadRecommendations(false);
            }
            if(recommendationOpen!=null&&recommendationOpen.isDone()){
                boolean applied=recommendationOpen.getNow(false);recommendationOpen=null;
                if(applied){showRecommendations=false;recommendationDetail=true;activeCollection=pendingRecommendation;songs.target=0;}
                pendingRecommendation=null;
            }
        }

        Track current=data.playback()==null?null:data.playback().track();String key=current==null?"":current.key();

        if(seek.active()&&!seek.matches(data.playback())){seek.cancel();drag=null;}

        if(provider!=data.provider()||!observed.equals(data.tracks())){

            qualityMenu=false;

            observed=data.tracks();provider=data.provider();resetCovers(Math.max(0,indexOf(current)));songs.target=0;

        }else if(!key.equals(playingKey)){int index=Math.max(0,indexOf(current));motion.follow(index,observed.size());wall.select(index);space.select(index);}

        String requestedLayout=player.layout();if(!layout.equals(requestedLayout)){int index=indexOf(current);if(index<0)index=frontIndex();layout=requestedLayout;resetCovers(index);}

        if(detail.track()!=null){
            if(detail.track().provider()!=data.provider())closeTrackDetails();
            else if(!key.equals(detailPlaybackKey)){
                detailPlaybackKey=key;
                if(current!=null){
                    detailCollection=List.of();
                    detailCollectionIndex=-1;
                    detail.open(current);
                }
            }
        }
        playingKey=key;

        // An inertial album animation must not turn playback back on after the module was disabled.

        space.update(dt);int commit=wallLayout()||spaceLayout()?-1:motion.update(dt);if(detail.track()==null&&commentTarget==null&&!showPlaylists&&!showRecommendations&&module.isEnabled()&&commit>=0&&commit<observed.size()&&!observed.get(commit).key().equals(key))playTrack(commit);
        if(detail.track()!=null||commentTarget!=null||showPlaylists||showRecommendations)return;
        if(wallLayout()){wall.update(dt);artwork.preloadIdle(data.tracks(),wall.selected());}

        else if(spaceLayout()){artwork.preloadVisible(data.tracks(),space.preloadIndices());artwork.preloadIdle(data.tracks(),space.browseIndex());}

        else {artwork.preload(data.tracks(),frontIndex(),8);artwork.preloadIdle(data.tracks(),frontIndex());}

    }

    private boolean spaceLayout(){return com.cheatprogram.render.AlbumSpaceModel.supports(layout);}

    private void nextLayout(){var layouts=MusicPlayer.LAYOUTS;player.layout(layouts.get((layouts.indexOf(layout)+1)%layouts.size()));}

    private boolean wallLayout(){return layout.equals("wall");}

    private int frontIndex(){return spaceLayout()?space.selected():wallLayout()?wall.selected():Math.max(0,Math.min(Math.max(0,observed.size()-1),Math.round(motion.position())));}

    private void resetCovers(int index){covers.clearHover();motion.reset(index,observed.size());wall.reset(index,observed.size());space.reset(layout,index,observed.size());if(coverDragging)drag=null;coverDragging=false;}

    private void moveCover(int delta){if(spaceLayout()){if(!observed.isEmpty()){int index=Math.max(0,Math.min(observed.size()-1,space.selected()+delta));space.select(index);playTrack(index);}return;}if(wallLayout()){if(!observed.isEmpty()){int index=Math.max(0,Math.min(observed.size()-1,wall.selected()+delta));wall.select(index);playTrack(index);}return;}enableMusic();motion.move(delta);}

    private int indexOf(Track track){if(track!=null)for(int i=0;i<observed.size();i++)if(observed.get(i).key().equals(track.key()))return i;return -1;}

    private void main(GuiGraphicsExtractor graphics,Snapshot data,float h){
        carouselHitHeight=0;

        c.textC(BOLD,c.ellipsize(BOLD,data.provider().label,20,255),218,34,20,Theme.TEXT);
        c.textC(REG,c.ellipsize(REG,data.account()==null?Lang.t("ui.music.account"):data.account().name(),9,460),220,59,9,Theme.TEXT_3);

        button("music.layout",c.ellipsize(REG,Lang.t("ui.music.layout")+" · "+Lang.t("mode.music_layout_"+layout),8,178),Icon.GEAR,620,23,208,30,false,this::nextLayout);

        button("music.auth",Lang.t(data.account()==null?"ui.music.login":"ui.music.logout"),Icon.USER,865,23,146,30,data.account()==null,()->{

            if(data.account()!=null)player.logout();

            else if(data.provider()==Provider.KUWO||data.provider()==Provider.SPOTIFY&&player.accountConfiguration().isBlank())mc.setScreen(new MusicAccountScreen(this,data.provider()));

            else player.login();

        });

        if(data.provider()==Provider.SPOTIFY||data.provider()==Provider.KUWO)

            iconButton("music.account.settings",Icon.GEAR,843,38,13,Theme.TEXT_2,()->mc.setScreen(new MusicAccountScreen(this,data.provider())));

        iconButton("music.reload",Icon.REFRESH,1038,38,15,Theme.TEXT_2,()->{if(commentTarget!=null){if(!player.comments().writing())player.openComments(commentTarget);}else if(showRecommendations||recommendationDetail){openRecommendations();player.loadRecommendations(true);}else if(searchReturn!=null)runSearch();else if(showPlaylists)player.loadPlaylists(true);else player.refreshLiked(true);});
        iconButton("music.close",Icon.CLOSE,1081,38,14,Theme.TEXT_2,this::onClose);

        java.net.URI loginUri=loginUri(data);if(loginUri!=null)button("music.browser",Lang.t("ui.music.browser"),Icon.LINK,865,63,205,25,false,()->net.minecraft.util.Util.getPlatform().openUri(loginUri));

        String status=data.status()==null?"":Lang.t(data.status());

        c.textC(REG,c.ellipsize(REG,status.isBlank()?Lang.t(data.busy()?"ui.music.loading":"ui.music.ready"):status,8.5f,loginUri==null?866:624),220,86,8.5f,Theme.TEXT_2);

        String requirement=player.requirement();boolean notice=requirement!=null&&!requirement.isBlank();

        if(commentTarget!=null){commentRows(104,h-100);return;}
        if(showRecommendations){recommendationCards(data,104,h-100);return;}
        MusicPlayer.Capabilities capabilities=player.capabilities();

        if(capabilities.search()){textField(searchField,Lang.t("ui.music.searchHint"),Icon.MUSIC,219,103,465,30);button("music.search",Lang.t("ui.music.search"),Icon.CHEVRON_RIGHT,694,103,107,30,false,this::runSearch);}

        else c.textC(REG,Lang.t("ui.musicPlayer.searchUnsupported"),220,118,8.5f,Theme.TEXT_4);

        if(capabilities.radio())button("music.radio",Lang.t("ui.music.radio"),Icon.MUSIC,815,103,263,30,false,()->{leaveRecommendations();searchReturn=null;playlistReturn=null;showPlaylists=false;activePlaylist="";enableMusic();player.radio();});
        else c.textRight(REG,Lang.t("ui.musicPlayer.radioUnsupported"),1078,118,8,Theme.TEXT_4);

        if(notice)c.textC(REG,c.ellipsize(REG,Lang.t(requirement),8,866),220,146,8,Theme.TEXT_3);

        if(searchReturn!=null){String source=searchReturn.showPlaylists()?Lang.t("ui.music.playlists"):searchReturn.activePlaylist().isBlank()?Lang.t("ui.music.liked"):searchReturn.activePlaylist();button("music.search.back",c.ellipsize(REG,Lang.t("ui.music.backToLibrary")+" · "+source,8.5f,420),Icon.CHEVRON_LEFT,219,notice?162:142,465,26,false,this::returnFromSearch);}

        float topOfContent=(notice?164:144);
        if(searchReturn==null&&(recommendationDetail||playlistReturn!=null)){
            boolean fromRecommendation=recommendationDetail;
            button("music.collection.back",Lang.t(fromRecommendation?"ui.music.recommendBack":"ui.music.backToPlaylists"),
                    Icon.CHEVRON_LEFT,219,topOfContent,184,26,false,fromRecommendation?this::openRecommendations:this::openPlaylists);
            collectionCommentButton(activeCollection,414,topOfContent,164,26,false);
        }
        if(showPlaylists){playlistRows(data,topOfContent+22,h-99);return;}
        carouselY=topOfContent+(searchReturn!=null||recommendationDetail||playlistReturn!=null?34:0);carouselH=wallLayout()||spaceLayout()?Math.min(420,Math.max(160,h-carouselY-256)):Math.min(260,Math.max(148,(h-330)*.58f));
        if(data.tracks().isEmpty()){
            titleFade.clear();
            empty(data,carouselY,carouselH+60);
            return;
        }

        float bottom=h-99,expandedTop=carouselY+21;
        if(bottom-expandedTop<38){
            drawCoverBrowser(graphics,data,carouselH+65,1);
            return;
        }
        float collapsedTop=Math.min(carouselY+carouselH+82,bottom-38);
        float expansion=songListExpansion.to(songListExpanded?1:0,dt,17);
        float listTop=Anim.lerp(collapsedTop,expandedTop,expansion);
        float coverHeight=listTop-carouselY-17;
        if(expansion<1&&coverHeight*c.scale>=1)drawCoverBrowser(graphics,data,coverHeight,1-expansion*expansion);
        trackRows(data,listTop,bottom);
    }
    private void drawCoverBrowser(GuiGraphicsExtractor graphics,Snapshot data,float visibleHeight,float alpha){
        carouselHitHeight=Math.min(carouselH,visibleHeight);
        boolean hovered=!coverDragging&&over(214,carouselY,884,carouselHitHeight);
        float hoverX=hovered?c.px(mx):Float.NaN,hoverY=hovered?c.py(my):Float.NaN;
        c.clip(214,carouselY,884,visibleHeight);
        R2D.pushAlpha(alpha);
        try{
            if(wallLayout())albumWall(data);
            else if(spaceLayout())covers.drawSpace(graphics,c.px(214),c.py(carouselY),884*c.scale,carouselH*c.scale,data.tracks(),artwork,space,hoverX,hoverY,dt);
            else covers.draw(graphics,c.px(214),c.py(carouselY),884*c.scale,carouselH*c.scale,data.tracks(),motion.position(),artwork,hoverX,hoverY,dt);
            if(!wallLayout()&&covers.failed())c.textMid(REG,Lang.t("ui.music.coverUnavailable"),656,carouselY+carouselH/2,10,Theme.TEXT_3);
            int selected=frontIndex();Track preview=data.tracks().get(selected);
            titleFade.update(preview,dt);for(var layer:titleFade.layers()){R2D.pushAlpha(layer.alpha());try{float offset=(1-layer.alpha())*9;
                c.textMid(BOLD,c.ellipsize(BOLD,layer.value().title(),16,700),656,carouselY+carouselH+15+offset,16,Theme.TEXT);
                c.textMid(REG,c.ellipsize(REG,layer.value().artist()+" · "+layer.value().album(),9,700),656,carouselY+carouselH+39+offset,9,Theme.TEXT_3);
            }finally{R2D.popAlpha();}}
            c.textMid(REG,Lang.t(spaceLayout()?"ui.music.spaceSwipe."+layout:wallLayout()?"ui.music.wallSwipe":artwork.unavailable(preview.provider(),preview.cover())?"ui.music.coverUnavailable":"ui.music.swipe"),656,carouselY+carouselH+59,8,Theme.TEXT_4);
            if(spaceLayout())hit(214,carouselY,884,carouselHitHeight).left(()->{coverDragging=true;space.begin(mx,my);drag=(x,y)->space.drag(x,y);}).scroll(space::scroll);
            else if(wallLayout())hit(214,carouselY,884,carouselHitHeight).left(()->{coverDragging=true;wall.begin(mx-214,my-carouselY);drag=(x,y)->wall.drag(x-214,y-carouselY);}).scroll(amount->wall.zoomAt(mx-214,my-carouselY,amount));
            else hit(214,carouselY,884,carouselHitHeight).left(()->{enableMusic();coverDragging=true;motion.begin(mx);drag=(x,y)->motion.drag(x);}).scroll(amount->moveCover(amount>0?-1:1));
        }finally{R2D.popAlpha();c.unclip();}
    }
    private void toggleSongList(){
        songListExpanded=!songListExpanded;
        motion.cancelInteraction();wall.cancel();space.cancel();
        if(coverDragging){coverDragging=false;drag=null;}
    }
    private void openRecommendations(){
        closeTrackDetails();
        closeComments();
        if(showRecommendations)return;
        searchReturn=null;playlistReturn=null;showPlaylists=false;activePlaylist="";focused=null;
        motion.cancelInteraction();wall.cancel();space.cancel();
        coverDragging=false;drag=null;
        recommendationDetail=false;showRecommendations=true;
        Snapshot current=player.snapshot();
        catalogProvider=current.provider();catalogAccount=current.account()==null?"":current.account().id();
        catalogReady=player.recommendationAccountReady();
        player.loadRecommendations(false);
    }
    private void leaveRecommendations(){
        if(recommendationOpen!=null)player.cancelRecommendationDetail();
        recommendationDetail=false;showRecommendations=false;recommendationOpen=null;pendingRecommendation=null;activeCollection=null;
    }
    private void recommendationCards(Snapshot data,float top,float bottom){
        if(bottom-top<45)return;
        if(data.login()!=null&&!data.login().complete()){empty(data,top,bottom-top);return;}
        var catalog=player.recommendations();
        if(catalog.items().isEmpty()){
            c.icon(Icon.MUSIC,656,top+(bottom-top)*.34f,37,Theme.TEXT_4);
            c.textMid(MED,c.ellipsize(MED,Lang.t(catalog.status()),11,790),656,top+(bottom-top)*.53f,11,Theme.TEXT_2);
            return;
        }
        float width=866,row=154,gap=13,card=(width-5*gap)/6;
        float y=top;
        if(recommendationOpen!=null)c.textC(MED,Lang.t("ui.musicPlayer.recommendationsLoading"),220,y+12,9,Theme.TEXT_2);
        else if(catalog.loading()||!catalog.status().endsWith("recommendationsReady"))
            c.textC(MED,Lang.t(catalog.status()),220,y+12,9,Theme.TEXT_2);
        y+=27;
        float content=27;
        for(RecommendationKind kind:RecommendationKind.values()){
            long count=catalog.items().stream().filter(item->item.kind()==kind).count();
            if(count>0)content+=31+((count+5)/6)*row;
        }
        float view=bottom-top;
        recommendationScroll.max=Math.max(0,content-view);
        recommendationScroll.update(dt);
        hit(216,top,874,view).scroll(recommendationScroll::scroll);
        c.clip(216,top,874,view);pushHitClip(216,top,874,view);
        try{
            y-=recommendationScroll.get();
            List<String> coversToLoad=new ArrayList<>();
            float groupY=y;
            for(RecommendationKind kind:RecommendationKind.values()){
                List<Recommendation> items=catalog.items().stream().filter(item->item.kind()==kind).toList();
                if(items.isEmpty())continue;
                groupY+=29;
                for(int i=0;i<items.size()&&coversToLoad.size()<24;i++){
                    float cy=groupY+(i/6)*row;
                    if(cy+row>=top&&cy<bottom+row)coversToLoad.add(items.get(i).cover());
                }
                groupY+=((items.size()+5)/6)*row+2;
            }
            artwork.preloadCovers(data.provider(),coversToLoad);
            for(RecommendationKind kind:RecommendationKind.values()){
                List<Recommendation> items=catalog.items().stream().filter(item->item.kind()==kind).toList();
                if(items.isEmpty())continue;
                c.textC(BOLD,Lang.t(kind==RecommendationKind.PLAYLIST?"ui.music.recommendPlaylists":"ui.music.recommendAlbums"),221,y+12,12,Theme.TEXT);
                y+=29;
                for(int i=0;i<items.size();i++){
                    Recommendation item=items.get(i);float x=220+(i%6)*(card+gap),cy=y+(i/6)*row;
                    if(cy+row<top||cy>bottom)continue;
                    float coverSize=card-18;
                    boolean hover=over(x,cy,card,row-8);
                    c.rrect(x,cy,card,row-8,9,hover?0x30ffffff:0x18ffffff);
                    var texture=artwork.artwork(item.provider(),item.cover());
                    if(texture!=null)R2D.texture(texture,c.px(x+9),c.py(cy+6),coverSize*c.scale,coverSize*c.scale,0,0,1,1,0xffffffff);
                    else c.icon(kind==RecommendationKind.PLAYLIST?Icon.FOLDER:Icon.MUSIC,x+card/2,cy+6+coverSize/2,24,Theme.TEXT_3);
                    c.textC(MED,c.ellipsize(MED,item.name(),9.5f,card-18),x+9,cy+coverSize+20,9.5f,Theme.TEXT);
                    hit(x,cy,card,row-8).left(()->{
                        if(recommendationOpen==null){
                            pendingRecommendation=CommentTarget.recommendation(item);
                            recommendationOpen=player.openRecommendation(item);
                        }
                    });
                    collectionCommentButton(CommentTarget.recommendation(item),x+card-57,cy+10,43,21,true);
                }
                y+=((items.size()+5)/6)*row+2;
            }
        }finally{popHitClip();c.unclip();}
        panelScrollBar("music.recommend.scroll",recommendationScroll,1096,top,view);
    }
    private void openTrackDetails(Track track){
        openTrackDetails(track, List.of(), -1);
    }

    private void openTrackDetails(Track track,List<Track> collection,int index){
        if(track==null||track.provider()!=player.snapshot().provider())return;
        closeComments();
        qualityMenu=false;
        focused=null;
        drag=null;
        seek.cancel();
        coverDragging=false;
        carouselHitHeight=0;
        motion.cancelInteraction();
        wall.cancel();
        space.cancel();
        var playback=player.snapshot().playback();
        detailPlaybackKey=playback==null||playback.track()==null?"":playback.track().key();
        detail.open(track);
        detailCollection=collection;
        detailCollectionIndex=index;
    }

    private void closeTrackDetails(){
        detail.close();
        detailCollection=List.of();
        detailCollectionIndex=-1;
        detailPlaybackKey="";
        qualityMenu=false;
    }

    private void drawTrackDetails(Snapshot data,float height){
        Track track=detail.track();
        Playback playback=data.playback();
        boolean current=playback!=null&&playback.track()!=null&&track.key().equals(playback.track().key());
        var song=CommentTarget.song(track);
        var album=CommentTarget.album(track);
        var platform=com.cheatprogram.media.player.MusicPlatformLinks.page(song);
        var lyrics=current?new MusicPlayer.TrackLyrics(data.lyrics(),player.translatedLyrics()):null;
        detail.draw(artwork,player,lyrics,height,current?playback.position():0,
                current?playback.durationMs():track.durationMs(),current&&playback.playing(),this::closeTrackDetails,
                commentEntryAvailable(song)?()->openComments(song):null,
                commentEntryAvailable(album)?()->openComments(album):null,
                platform==null?null:()->net.minecraft.util.Util.getPlatform().openUri(platform),
                current?player::seek:null,this::openTrackDetails,this::enableMusic);
    }

    private void openComments(Track track){openComments(CommentTarget.song(track));}

    private void openComments(CommentTarget target){
        if(target==null||!commentEntryAvailable(target))return;
        var state=player.snapshot();
        commentDraftKey=new CommentDraftKey(target.provider(),target.kind(),target.id(),state.account()==null?"":state.account().id(),"");
        replyingTo=null;
        commentComposer.setText(commentDrafts.getOrDefault(commentDraftKey,""));
        focused=null;
        qualityMenu=false;
        coverDragging=false;
        seek.cancel();
        drag=null;
        motion.cancelInteraction();
        wall.cancel();
        space.cancel();
        commentTarget=target;
        commentScroll.target=0;
        commentScroll.value.snap(0);
        measuredComments=List.of();
        measuredRows=List.of();
        commentContentHeight=0;
        player.openComments(target);
    }

    private void closeComments(){
        if(commentTarget==null)return;
        if(focused==commentComposer)focused=null;
        commentTarget=null;
        commentDraftKey=null;
        replyingTo=null;
        commentComposer.setText("");
        measuredComments=List.of();
        measuredRows=List.of();
        commentContentHeight=0;
        player.closeComments();
    }

    private void rememberCommentDraft(String value){
        if(commentDraftKey==null)return;
        if(value.isBlank())commentDrafts.remove(commentDraftKey);
        else commentDrafts.put(commentDraftKey,value);
        while(commentDrafts.size()>16)commentDrafts.remove(commentDrafts.keySet().iterator().next());
    }

    private void composeReply(Comment comment){
        if(commentDraftKey==null||player.comments().writing()||comment!=null&&!player.canReplyToComment(comment))return;
        replyingTo=comment;
        commentDraftKey=new CommentDraftKey(commentDraftKey.provider(),commentDraftKey.kind(),commentDraftKey.id(),
                commentDraftKey.account(),comment==null?"":comment.id());
        commentComposer.setText(commentDrafts.getOrDefault(commentDraftKey,""));
        focused=commentComposer;
    }

    private void submitComment(){
        String original=commentComposer.text;
        var target=commentDraftKey;
        var state=player.comments();
        if(target==null||original.isBlank()||state.writing()||!state.postingEnabled())return;
        Comment parent=replyingTo;
        var result=parent==null?player.postComment(original.strip()):player.replyToComment(parent,original.strip());
        result.thenAccept(success->{
            if(!success)return;
            mc.execute(()->{
                if(original.equals(commentDrafts.get(target)))commentDrafts.remove(target);
                if(target.equals(commentDraftKey)&&original.equals(commentComposer.text)){
                    commentComposer.setText("");
                    if(parent!=null)composeReply(null);
                    commentScroll.target=0;
                }
            });
        });
    }

    @Override protected void onTypographyChanged(){commentScale=0;detail.resetTypography();}

    private void measureComments(List<Comment> comments){
        if(commentScale==c.scale&&measuredComments==comments)return;
        boolean sameScale=commentScale==c.scale;
        List<CommentRow> rows=new ArrayList<>(comments.size());
        float offset=0;
        for(int index=0;index<comments.size();index++){
            Comment comment=comments.get(index);
            List<String> lines;
            if(sameScale&&index<measuredRows.size()&&comment.body().equals(measuredComments.get(index).body())){
                lines=measuredRows.get(index).lines();
            }else{
                lines=c.wrap(REG,comment.body().replace("\r\n","\n").replace('\r','\n'),10,789);
            }
            List<String> quote=comment.replyToBody().isBlank()?List.of():c.wrap(REG,
                    (comment.replyToAuthor().isBlank()?"":comment.replyToAuthor()+" · ")+comment.replyToBody(),9,763);
            float height=44+13*lines.size()+(quote.isEmpty()?0:12+13*quote.size());
            rows.add(new CommentRow(lines,quote,offset,height));
            offset+=height+6;
        }
        commentScale=c.scale;
        measuredComments=comments;
        measuredRows=List.copyOf(rows);
        commentContentHeight=offset;
    }

    private int firstVisibleComment(float scroll){
        int low=0,high=measuredRows.size();
        while(low<high){
            int middle=(low+high)>>>1;
            CommentRow row=measuredRows.get(middle);
            if(row.offset()+row.height()<=scroll)low=middle+1;
            else high=middle;
        }
        return low;
    }

    private void scrollComments(double amount){
        commentScroll.scroll(amount);
        var comments=player.comments();
        if(amount<0&&commentScroll.target>=commentScroll.max-72&&comments.hasMore()&&!comments.loading()
                &&!comments.writing()&&!comments.status().endsWith("commentsFailed"))player.moreComments();
    }

    private void commentRows(float top,float bottom){
        if(bottom-top<44)return;
        var comments=player.comments();
        button("music.comments.back",Lang.t("ui.back"),Icon.CHEVRON_LEFT,220,top,102,28,false,this::closeComments);
        c.textC(BOLD,commentLabel(commentTarget),339,top+14,14,Theme.TEXT);
        if(!comments.items().isEmpty()){
            c.textRight(REG,Lang.t("ui.music.commentCountLoaded",comments.items().size()),1085,top+14,9,Theme.TEXT_3);
        }
        var platformPage=com.cheatprogram.media.player.MusicPlatformLinks.page(commentTarget);
        c.textC(REG,c.ellipsize(REG,commentTarget.title()+" · "+commentTarget.provider().label,9,platformPage==null?855:672),223,top+48,9,Theme.TEXT_3);
        if(platformPage!=null)button("music.comments.platform",Lang.t("ui.music.commentOpenPlatform"),Icon.LINK,915,top+34,170,27,false,
                ()->net.minecraft.util.Util.getPlatform().openUri(platformPage));
        float listTop=top+65,footerHeight=comments.postingEnabled()?(replyingTo==null?75:102):38,view=bottom-listTop-footerHeight;
        if(view<18)return;
        hit(216,listTop,874,view).scroll(this::scrollComments);
        if(comments.items().isEmpty()){
            c.textMid(REG,Lang.t(comments.status()),656,listTop+view*.5f,10,Theme.TEXT_2);
        }else{
            drawCommentList(comments,listTop,view);
        }
        drawCommentActions(comments,bottom);
    }

    private void drawCommentList(MusicPlayer.Comments comments,float listTop,float view){
        measureComments(comments.items());
        boolean replies=player.canReplyToComments();
        commentScroll.max=Math.max(0,commentContentHeight-view);
        commentScroll.update(dt);
        float scroll=commentScroll.get();
        c.clip(216,listTop,874,view);
        pushHitClip(216,listTop,874,view);
        try{
            for(int index=firstVisibleComment(scroll);index<measuredRows.size();index++){
                CommentRow row=measuredRows.get(index);
                float y=listTop+row.offset()-scroll;
                if(y>=listTop+view)break;
                Comment comment=comments.items().get(index);
                c.rrect(220,y,865,row.height(),8,0x16ffffff);
                var avatar=artwork.artwork(commentTarget.provider(),comment.avatar());
                if(avatar!=null)R2D.texture(avatar,c.px(230),c.py(y+10),28*c.scale,28*c.scale,0,0,1,1,0xffffffff);
                else {c.circle(244,y+24,14,0x15ffffff);c.icon(Icon.USER,244,y+24,14,Theme.TEXT_3);}
                String author=comment.author().isBlank()?Lang.t("ui.music.commentAnonymous"):comment.author();
                c.textC(MED,c.ellipsize(MED,author,10,490),268,y+17,10,Theme.TEXT);
                if(comment.createdAt()>0)c.textRight(REG,COMMENT_DATE.format(Instant.ofEpochMilli(comment.createdAt())),860,y+17,8,Theme.TEXT_3);
                if(replies){
                    boolean hovered=over(868,y+5,91,24);
                    if(hovered)c.rrect(868,y+5,91,24,12,0x24ffffff);
                    c.textMid(MED,Lang.t("ui.music.commentReply"),913.5f,y+17,8.5f,hovered?Theme.TEXT:Theme.TEXT_2);
                    hit(868,y+5,91,24).left(()->composeReply(comment));
                }
                if(comments.likesEnabled())drawCommentLike(comment,y,comments.writing());
                else if(comment.likes()>0)c.textRight(REG,Lang.t("ui.music.commentLikes",comment.likes()),1074,y+17,8,Theme.TEXT_3);
                for(int line=0;line<row.lines().size();line++){
                    float lineY=y+37+line*13;
                    if(lineY<listTop-8)continue;
                    if(lineY>listTop+view+8)break;
                    c.textC(REG,row.lines().get(line),268,lineY,10,Theme.TEXT_2);
                }
                if(!row.quote().isEmpty()){
                    float quoteY=y+37+13*row.lines().size()-3;
                    c.rrect(268,quoteY,796,13*row.quote().size()+9,6,0x12000000);
                    c.rrect(268,quoteY+3,2,13*row.quote().size()+3,1,0x65ffffff);
                    for(int line=0;line<row.quote().size();line++){
                        float lineY=quoteY+10+line*13;
                        if(lineY>=listTop-8&&lineY<listTop+view+8)c.textC(REG,row.quote().get(line),280,lineY,9,Theme.TEXT_3);
                    }
                }
            }
        }finally{popHitClip();c.unclip();}
        panelScrollBar("music.comments.scroll",commentScroll,1096,listTop,view);
    }

    private void drawCommentLike(Comment comment,float rowY,boolean writing){
        float x=967,y=rowY+5,width=109,height=24;
        boolean hovered=over(x,y,width,height);
        if(comment.liked()||hovered)c.rrect(x,y,width,height,12,comment.liked()?0x33ef5268:0x24ffffff);
        String key=comment.liked()?"ui.music.commentUnlike":"ui.music.commentLike";
        int color=comment.liked()?0xfffb7185:Theme.TEXT_2;
        String label=Lang.t(key,comment.likes());
        c.textMid(MED,c.ellipsize(MED,label,8.5f,width-12),x+width/2,y+height/2,8.5f,color);
        if(!writing)hit(x,y,width,height).left(()->player.toggleCommentLike(comment));
    }

    private void drawCommentActions(MusicPlayer.Comments comments,float bottom){
        float pagerY=bottom-(comments.postingEnabled()?(replyingTo==null?68:95):29);
        if(comments.loading()||comments.writing()||comments.status().endsWith("commentsFailed")
                ||(!comments.items().isEmpty()&&!comments.status().endsWith("commentsReady"))){
            c.textC(REG,c.ellipsize(REG,Lang.t(comments.status()),9,683),223,pagerY+13,9,Theme.TEXT_3);
        }else if(!comments.items().isEmpty()&&!comments.postingEnabled()){
            c.textC(REG,Lang.t("ui.music.commentReadOnly"),223,pagerY+13,9,Theme.TEXT_3);
        }
        if(!comments.loading()&&!comments.writing()){
            if(comments.status().endsWith("commentsFailed")){
                button("music.comments.retry",Lang.t("ui.music.commentRetry"),Icon.REFRESH,899,pagerY,185,26,false,player::retryComments);
            }else if(comments.hasMore()){
                button("music.comments.more",Lang.t("ui.music.commentMore"),Icon.CHEVRON_DOWN,899,pagerY,185,26,false,player::moreComments);
            }
        }
        if(!comments.postingEnabled())return;
        float composerY=bottom-32;
        if(replyingTo!=null){
            String author=replyingTo.author().isBlank()?Lang.t("ui.music.commentAnonymous"):replyingTo.author();
            c.textC(MED,c.ellipsize(MED,Lang.t("ui.music.commentReplying",author),9,784),224,composerY-14,9,Theme.TEXT_2);
            if(!comments.writing())iconButton("music.comments.reply.cancel",Icon.CLOSE,1071,composerY-14,11,Theme.TEXT_2,()->composeReply(null));
        }
        textField(commentComposer,Lang.t(replyingTo==null?"ui.music.commentDraft":"ui.music.commentReplyDraft"),null,220,composerY,735,29);
        boolean canPost=!comments.writing()&&!commentComposer.text.isBlank();
        if(canPost)button("music.comments.publish",Lang.t(replyingTo==null?"ui.music.commentPublish":"ui.music.commentReply"),null,964,composerY,120,29,true,this::submitComment);
    }


    private boolean commentEntryAvailable(CommentTarget target){
        return player.supportsComments(target)||com.cheatprogram.media.player.MusicPlatformLinks.page(target)!=null;
    }

    private static String commentLabel(CommentTarget target){
        return Lang.t(switch(target.kind()){
            case SONG -> "ui.music.commentSong";
            case ALBUM -> "ui.music.commentAlbum";
            case PLAYLIST -> "ui.music.commentPlaylist";
        });
    }

    private void collectionCommentButton(CommentTarget target,float x,float y,float width,float height,boolean shortLabel){
        if(target==null||!commentEntryAvailable(target))return;
        String label=Lang.t("ui.music.comments");
        if(!shortLabel)label=commentLabel(target);
        if(shortLabel){
            boolean hovered=over(x,y,width,height);
            c.rrect(x,y,width,height,height/2,hovered?0xe0222228:0xb0272730);
            c.textMid(MED,c.ellipsize(MED,label,8,width-8),x+width/2,y+height/2,8,Theme.TEXT);
            hit(x,y,width,height).left(()->openComments(target));
        }else{
            String fitted=c.ellipsize(MED,label,10.5f,width-12);
            button("music.comments.collection",fitted,null,x,y,width,height,false,()->openComments(target));
        }
    }

    private void albumWall(Snapshot data){

        wall.viewport(884,carouselH);boolean hovered=over(214,carouselY,884,carouselHitHeight);wall.hoverAt(hovered?mx-214:Float.NaN,hovered?my-carouselY:Float.NaN);List<AlbumWallModel.Card> visible=wall.visible(0);

        artwork.preloadVisible(data.tracks(),wall.preloadIndices());

        c.rrect(214,carouselY,884,carouselH,10,0x25000000);

        covers.drawWall(c.px(214),c.py(carouselY),884*c.scale,carouselH*c.scale,c.scale,wall.zoom(),data.tracks(),visible,artwork);

        c.clip(214,carouselY,884,carouselH);try{for(AlbumWallModel.Card card:visible){

            Track track=data.tracks().get(card.index());float x=214+card.x(),y=carouselY+card.y(),size=card.size(),font=Math.min(12,Math.max(7.5f,size*.068f));

            if(size<46)continue;

            c.textC(MED,c.ellipsize(MED,track.title(),font,size-14),x+7,y+size-23,font,Theme.TEXT);

            if(size>=74)c.textC(REG,c.ellipsize(REG,track.artist(),Math.max(6.5f,font-2),size-14),x+7,y+size-10,Math.max(6.5f,font-2),Theme.TEXT_2);

        }}finally{c.unclip();}

    }

    private void empty(Snapshot data,float y,float height){

        Identifier qr=artwork.login(data.login());

        if(data.login()!=null&&!data.login().complete()&&qr!=null){

            c.rrect(581,y+8,150,150,12,0xffffffff);R2D.texture(qr,c.px(590),c.py(y+17),132*c.scale,132*c.scale,0,0,1,1,0xffffffff);

            c.textMid(REG,Lang.t("ui.music.qr"),656,y+177,10,Theme.TEXT_2);

            c.textMid(REG,c.ellipsize(REG,Lang.t(data.login().status()),9,660),656,y+199,9,Theme.TEXT_3);

        }else{

            c.icon(Icon.MUSIC,656,y+height*.31f,39,Theme.TEXT_4);

            c.textMid(MED,Lang.t(data.busy()?"ui.music.loading":data.account()==null?"ui.music.empty":"ui.music.noTracks"),656,y+height*.56f,11,Theme.TEXT_2);

            c.textMid(REG,Lang.t("ui.music.privacy"),656,y+height*.56f+27,8.5f,Theme.TEXT_4);

            if(data.login()!=null)c.textMid(REG,c.ellipsize(REG,Lang.t(data.login().status()),9,660),656,y+height*.56f+50,9,Theme.TEXT_3);

        }

    }

    private void trackRows(Snapshot data,float top,float bottom){

        float available=bottom-top;MusicPlayer.Discovery discovery=player.discovery();String heading=discovery.kind().equals("search")?Lang.t("ui.music.search")+" · "+discovery.query():discovery.kind().equals("radio")?Lang.t("ui.music.radio"):discovery.kind().equals("recommendation")?discovery.query():activePlaylist.isBlank()?Lang.t("ui.music.liked"):activePlaylist;c.textC(MED,c.ellipsize(MED,heading+" · "+data.tracks().size(),10.5f,650),221,top-10,10.5f,Theme.TEXT_2);
        if(discovery.kind().equals("search")&&discovery.hasMore())button("music.more",Lang.t("ui.music.moreResults"),Icon.CHEVRON_RIGHT,900,top-23,150,25,false,player::moreSearch);
        iconButton("music.songs.expand",songListExpanded?Icon.CHEVRON_DOWN:Icon.CHEVRON_UP,1073,top-10,14,Theme.TEXT_2,this::toggleSongList);
        if(over(1059,top-24,28,28)){
            String label=Lang.t(songListExpanded?"ui.music.collapseSongs":"ui.music.expandSongs");
            float width=c.textWidth(REG,label,8.5f)+20,x=1087-width,y=top-55;
            c.glass(x,y,width,25,10,Anim.black(.28f),Anim.black(.32f),Theme.chipGlass(c.scale));
            c.textMid(REG,label,x+width/2,y+12.5f,8.5f,Theme.TEXT);
        }

        c.textC(REG,Lang.t("ui.music.track"),253,top+16,8,Theme.TEXT_4);c.textC(REG,Lang.t("ui.music.artist"),611,top+16,8,Theme.TEXT_4);

        c.textC(REG,Lang.t("ui.music.album"),819,top+16,8,Theme.TEXT_4);c.textRight(REG,Lang.t("ui.music.duration"),1019,top+16,8,Theme.TEXT_4);
        float y0=top+33,viewport=Math.max(1,available-33);songs.max=Math.max(0,data.tracks().size()*39-viewport);songs.update(dt);

        hit(216,y0,874,viewport).scroll(songs::scroll);c.clip(216,y0,874,viewport);pushHitClip(216,y0,874,viewport);

        float scroll=Math.min(songs.get(),songs.max);
        try{int first=Math.max(0,(int)(scroll/39)),last=Math.min(data.tracks().size(),first+(int)(viewport/39)+2);

            for(int i=first;i<last;i++){

                Track track=data.tracks().get(i);int index=i;float y=y0+i*39-scroll;boolean active=data.playback()!=null&&data.playback().track()!=null&&track.key().equals(data.playback().track().key());

                var rowCover=artwork.artwork(track.provider(),track.cover());

                if(active||over(217,y,871,36))c.rrect(217,y,871,36,6,active?0x22ef5268:0x0fffffff);

                c.textC(REG,active?"♫":String.valueOf(i+1),228,y+18,9,active?0xfffb7185:Theme.TEXT_4);

                if(rowCover!=null)R2D.texture(rowCover,c.px(251),c.py(y+4),28*c.scale,28*c.scale,0,0,1,1,0xffffffff);

                else {c.rrect(251,y+4,28,28,4,0x18ffffff);c.icon(Icon.MUSIC,265,y+18,13,Theme.TEXT_3);}

                c.textC(active?MED:REG,c.ellipsize(REG,track.title(),10,306),286,y+18,10,active?0xfffb7185:Theme.TEXT);

                c.textC(REG,c.ellipsize(REG,track.artist(),9,190),611,y+18,9,Theme.TEXT_3);

                var albumTarget=CommentTarget.album(track);
                boolean albumComments=commentEntryAvailable(albumTarget);
                boolean albumHovered=albumComments&&over(814,y+6,151,24);
                if(albumHovered)c.rrect(814,y+6,151,24,6,0x24ffffff);
                c.textC(REG,c.ellipsize(REG,track.album(),9,albumComments?131:150),819,y+18,9,albumHovered?Theme.TEXT:Theme.TEXT_3);
                c.textRight(REG,time(track.durationMs()),1019,y+18,9,Theme.TEXT_4);
                hit(217,y,812,36).left(()->playTrack(index));
                if(over(247,y,36,36))c.rrect(247,y,36,36,6,0x24ffffff);
                hit(247,y,36,36).left(()->openTrackDetails(track,data.tracks(),index));
                if(albumComments){
                    c.icon(Icon.CHEVRON_RIGHT,955,y+18,9,albumHovered?Theme.TEXT:Theme.TEXT_4);
                    hit(814,y+6,151,24).left(()->openComments(albumTarget));
                }
                var target=CommentTarget.song(track);
                if(commentEntryAvailable(target)){
                    boolean hovered=over(1032,y+6,54,24);
                    if(hovered)c.rrect(1032,y+6,54,24,6,0x24ffffff);
                    c.textMid(REG,c.ellipsize(REG,Lang.t("ui.music.comments"),8,48),1059,y+18,8,hovered?Theme.TEXT:Theme.TEXT_3);
                    hit(1032,y+6,54,24).left(()->openComments(target));
                }
            }

        }finally{popHitClip();c.unclip();}panelScrollBar("music.songs.scroll",songs,1096,y0,viewport);

    }

    private void playlistRows(Snapshot data,float top,float bottom){

        if(bottom-top<20)return;
        c.textC(MED,Lang.t("ui.music.playlists")+" · "+data.playlists().size(),221,top-10,10.5f,Theme.TEXT_2);

        if(data.playlists().isEmpty()){empty(data,top,bottom-top);return;}
        float viewport=bottom-top;playlists.max=Math.max(0,data.playlists().size()*53-viewport);playlists.update(dt);

        hit(216,top,874,viewport).scroll(playlists::scroll);c.clip(216,top,874,viewport);pushHitClip(216,top,874,viewport);

        try{int first=Math.max(0,(int)(playlists.get()/53)),last=Math.min(data.playlists().size(),first+(int)(viewport/53)+2);

            List<String> coversToLoad=data.playlists().subList(first,Math.min(data.playlists().size(),last+8)).stream().map(Playlist::cover).toList();
            artwork.preloadCovers(data.provider(),coversToLoad);
            for(int i=first;i<last;i++){Playlist list=data.playlists().get(i);float y=top+i*53-playlists.get();

                if(over(217,y,871,49))c.rrect(217,y,871,49,7,0x0fffffff);

                Identifier image=artwork.artwork(data.provider(),list.cover());

                if(image!=null)R2D.texture(image,c.px(227),c.py(y+6),36*c.scale,36*c.scale,0,0,1,1,0xffffffff);else c.icon(Icon.FOLDER,245,y+24,18,Theme.TEXT_4);

                c.textC(MED,c.ellipsize(MED,list.name(),10.5f,650),276,y+18,10.5f,Theme.TEXT);c.textC(REG,String.valueOf(list.count()),277,y+36,8.5f,Theme.TEXT_4);

                c.icon(Icon.CHEVRON_RIGHT,1075,y+24,13,Theme.TEXT_3);hit(217,y,871,49).left(()->openPlaylist(list));
                collectionCommentButton(CommentTarget.playlist(data.provider(),list),990,y+13,65,23,true);
            }

        }finally{popHitClip();c.unclip();}panelScrollBar("music.playlists.scroll",playlists,1096,top,viewport);

    }

    private void footer(Snapshot data,float h){

        float y=h-88;
        boolean full=detail.track()!=null&&commentTarget==null;
        float left=full?28:220;
        if(!full)c.rect(207,y,892,1,0x18ffffff);
        Playback playback=data.playback();
        Track current=full?detail.track():playback==null?null:playback.track();
        if(full&&(playback==null||playback.track()==null||!current.key().equals(playback.track().key())))playback=null;

        footerFade.update(current,dt);

        if(current!=null){for(var layer:footerFade.layers()){Track track=layer.value();R2D.pushAlpha(layer.alpha());try{Identifier image=artwork.artwork(track.provider(),track.cover());float inset=(1-layer.alpha())*3;if(image!=null)R2D.texture(image,c.px(left+inset),c.py(y+16+inset),(46-2*inset)*c.scale,(46-2*inset)*c.scale,0,0,1,1,0xffffffff);

                c.textC(MED,c.ellipsize(MED,track.title(),10,164),left+58,y+29+(1-layer.alpha())*6,10,Theme.TEXT);c.textC(REG,c.ellipsize(REG,track.artist(),8.5f,164),left+58,y+49+(1-layer.alpha())*6,8.5f,Theme.TEXT_3);
            }finally{R2D.popAlpha();}}}

        else c.textC(REG,Lang.t("ui.music.noTracks"),225,y+37,9,Theme.TEXT_4);

        if(current!=null){
            if(over(left,y+16,46,46))c.rrect(left,y+16,46,46,7,0x24ffffff);
            if(artwork.cachedArtwork(current.provider(),current.cover())==null)c.icon(Icon.MUSIC,left+23,y+39,19,Theme.TEXT_3);
            hit(left,y+16,46,46).left(()->openTrackDetails(current));
            var target=CommentTarget.song(current);
            if(commentEntryAvailable(target))button("music.comments.playing",c.ellipsize(MED,Lang.t("ui.music.comments"),10.5f,81),null,
                    449,y+17,91,24,target.equals(commentTarget),()->openComments(target));
        }
        boolean playing=playback!=null&&playback.playing();controls.update(playing,over(591,y+8,42,42),dt);

        playbackModeControl(y);
        skipControl(-1,564,y+29);skipControl(1,660,y+29);

        float size=controls.scale();
        c.circle(612,y+29,20*size,0xffeb5267);
        playIcon(612,y+29,size,controls.playing());

        hit(591,y+8,42,42).left(this::toggleDetailPlayback);

        long duration=playback==null?0:playback.durationMs(),position=seek.active()?seek.position():playback==null?0:playback.position();

        float seekX=549,seekW=311,seekY=y+65;c.rrect(seekX,seekY-2,seekW,3,1.5f,0x23ffffff);

        float fraction=duration>0?Math.max(0,Math.min(1,position/(float)duration)):0;c.rrect(seekX,seekY-2,seekW*fraction,3,1.5f,full?detail.playbackAccent():0xffed6175);

        c.textRight(REG,time(position),seekX-9,seekY,7.5f,Theme.TEXT_4);c.textC(REG,time(duration),seekX+seekW+9,seekY,7.5f,Theme.TEXT_4);

        if(duration>0)hit(seekX-3,seekY-8,seekW+6,16).left(()->{seek.begin(player.snapshot().playback());if(!seek.active())return;drag=(x,my)->seek.drag((x-seekX)/seekW);drag.drag(mx,my);});

        c.textC(REG,Lang.t("ui.music.volume"),923,y+29,8.5f,Theme.TEXT_3);c.rrect(923,y+47,151,3,1.5f,0x23ffffff);c.rrect(923,y+47,(float)volume*151,3,1.5f,Theme.TEXT_2);

        hit(919,y+39,159,19).left(()->{drag=(x,my)->{volume=Math.max(0,Math.min(1,(x-923)/151));player.volume(volume);};drag.drag(mx,my);});

        c.textC(REG,Lang.t("ui.music.privacy"),925,y+69,6.8f,Theme.TEXT_4);

    }

    private void enableMusic(){if(!module.isEnabled())module.setEnabled(true);player.setModuleEnabled(true);}

    private void playTrack(int index){enableMusic();player.playIndex(index);}

    private void runSearch(){

        if(searchField.text.isBlank())return;

        boolean wasRecommendation=recommendationDetail;
        var savedCollection=activeCollection;
        leaveRecommendations();
        if(searchReturn==null){MusicPlayer.LibraryPage page=player.captureLibraryPage();if(page!=null){motion.cancelInteraction();wall.cancel();space.cancel();int selected=frontIndex();searchReturn=new SearchReturn(page,observed,provider,activePlaylist,savedCollection,showPlaylists,wasRecommendation,layout,motion,wall,space,songs,playlists);motion=new MusicCoverCarouselRenderer.Motion();wall=new AlbumWallModel();space=new com.cheatprogram.render.AlbumSpaceModel();songs=new Scroller();playlists=new Scroller();resetCovers(selected);}}
        showPlaylists=false;activePlaylist="";focused=null;songs.target=0;player.search(searchField.text);

    }

    private void returnFromSearch(){

        SearchReturn saved=searchReturn;if(saved==null)return;

        if(!player.restoreLibraryPage(saved.page())){searchReturn=null;return;}

        searchReturn=null;observed=saved.tracks();provider=saved.provider();activePlaylist=saved.activePlaylist();activeCollection=saved.activeCollection();showPlaylists=saved.showPlaylists();recommendationDetail=saved.recommendationDetail();layout=saved.layout();player.layout(layout);
        motion=saved.motion();wall=saved.wall();space=saved.space();songs=saved.songs();playlists=saved.playlists();focused=null;qualityMenu=false;coverDragging=false;drag=null;seek.cancel();titleFade.clear();searchField.text="";

        Playback playback=player.snapshot().playback();playingKey=playback==null||playback.track()==null?"":playback.track().key();if(spaceLayout())artwork.preloadVisible(observed,space.preloadIndices());else if(!wallLayout())artwork.preload(observed,frontIndex(),8);

    }

    private void playDetailSelection(Track track){
        if(detailCollectionIndex>=0)player.playCollection(detailCollection,detailCollectionIndex);
        else player.playTrack(track);
    }

    private void toggleDetailPlayback(){
        Track track=commentTarget==null?detail.track():null;
        var playback=player.snapshot().playback();
        if(track!=null&&(playback==null||playback.track()==null||!track.key().equals(playback.track().key()))){
            controls.click(0);
            enableMusic();
            playDetailSelection(track);
        }else togglePlayback();
    }

    private void togglePlayback(){controls.click(0);enableMusic();player.togglePlaying();}

    private void qualityControl(Snapshot data,float height){

        MusicPlayer.QualitySettings quality=player.qualitySettings();

        String requested=quality.requested().isEmpty()?Lang.t("ui.music.platformDefault"):quality.offered().stream().filter(option->option.id().equals(quality.requested())).map(Quality::label).findFirst().orElse(quality.requested());

        boolean managed=quality.offered().isEmpty()&&quality.requested().isEmpty();

        if(managed){qualityMenu=false;c.textC(REG,c.ellipsize(REG,Lang.t("ui.music.qualityPlatformManaged"),8,182),711,height-61,8,Theme.TEXT_3);}

        else button("music.quality",c.ellipsize(REG,Lang.t("ui.music.quality")+" · "+requested,8,143),Icon.GEAR,708,height-75,183,27,qualityMenu,()->qualityMenu=!qualityMenu);

        String returned=quality.offered().stream().filter(option->option.id().equals(quality.applied())).map(Quality::label).findFirst().orElse(quality.applied());

        String applied=quality.applied().isBlank()?Lang.t(managed?"ui.music.qualityPlatformManaged":"ui.music.qualityUnknown"):Lang.t("ui.music.qualityApplied")+" · "+returned;

        c.textC(REG,c.ellipsize(REG,applied,7,183),711,height-40,7,Theme.TEXT_4);

        if(!qualityMenu||managed)return;

        java.util.ArrayList<Quality> options=new java.util.ArrayList<>();options.add(new Quality("",Lang.t("ui.music.platformDefault")));options.addAll(quality.offered());

        int columns=options.size()>8?2:1,rows=(options.size()+columns-1)/columns;float width=columns*190,left=896-width,top=height-86-rows*27;

        hit(0,0,1120,height).left(()->qualityMenu=false);

        boolean full=detail.track()!=null&&commentTarget==null;
        Backdrop.capture(full?.25f:Theme.overlayFrost());
        R2D.applyState();
        float menuX=left-5,menuY=top-5,menuW=width+10,menuH=rows*27+10;
        c.shadow(menuX,menuY+5,menuW,menuH,13,18,Anim.black(.22f));
        if(full)detail.glass(menuX,menuY,menuW,menuH,13,.48f);
        else c.glass(menuX,menuY,menuW,menuH,13,Anim.black(.28f),Anim.black(.32f),Theme.chipGlass(c.scale));

        for(int index=0;index<options.size();index++){Quality option=options.get(index);float x=left+(index/rows)*190,y=top+(index%rows)*27;boolean selected=option.id().equals(quality.requested());

            if(selected||over(x,y,190,27))c.rrect(x,y,190,27,6,selected?0x33ef5268:0x18ffffff);

            if(selected)c.circle(x+12,y+13.5f,2.5f,0xfffb7185);

            c.textC(selected?MED:REG,c.ellipsize(REG,option.label(),8.5f,163),x+23,y+13.5f,8.5f,selected?Theme.TEXT:Theme.TEXT_2);

            hit(x,y,190,27).left(()->{player.quality(option.id());qualityMenu=false;});

        }

    }

    private void playbackModeControl(float y){
        var modes=player.playbackModes();
        if(modes.size()<2)return;
        var mode=player.playbackMode();
        String label=Lang.t("ui.music.playbackMode."+mode.id);
        button("music.playback.mode",c.ellipsize(MED,label,9,78),Icon.REFRESH,445,y+45,100,24,false,
                ()->player.playbackMode(modes.get((modes.indexOf(mode)+1)%modes.size())));
    }
    private void skipControl(int direction,float x,float y){float pulse=controls.skip(direction),offset=direction*pulse*5;

        c.circle(x,y,17+pulse*3,Math.round(pulse*28)<<24|0xfb7185);

        c.icon(direction<0?Icon.CHEVRON_LEFT:Icon.CHEVRON_RIGHT,x+offset,y,18+pulse*2,Theme.TEXT_2);

        hit(x-20,y-20,40,40).left(()->{controls.click(direction);enableMusic();player.next(direction);});

    }

    /** Two connected triangle halves continuously become the two pause bars. */

    private void playIcon(float x,float y,float scale,float pause){for(int row=0;row<16;row++){float dy=-7+row*.875f,width=12*(1-Math.abs(dy+.4375f)/7),middle=-4+width*.5f;

        float left=-4+(-5+4)*pause,leftEnd=middle+(-1.5f-middle)*pause,right=middle+(1.5f-middle)*pause,rightEnd=-4+width+(5+4-width)*pause;

        c.rrect(x+left*scale,y+dy*scale,Math.max(.01f,(leftEnd-left)*scale),.95f*scale,.2f,0xffffffff);

        c.rrect(x+right*scale,y+dy*scale,Math.max(.01f,(rightEnd-right)*scale),.95f*scale,.2f,0xffffffff);

    }}

    /** Scrubbing changes only this preview. The release returns one guarded decoder seek. */

    public static final class SeekPreview {

        private boolean active;private String key="";private long revision,duration,position;

        public boolean active(){return active;}public long position(){return position;}

        public void begin(Playback playback){cancel();if(playback==null||playback.track()==null||playback.durationMs()<=0)return;active=true;key=playback.track().key();revision=playback.revision();duration=playback.durationMs();position=playback.position();}

        public void drag(float fraction){if(active&&Float.isFinite(fraction))position=(long)(Math.max(0,Math.min(1,fraction))*duration);}

        public boolean matches(Playback playback){return active&&playback!=null&&playback.track()!=null&&key.equals(playback.track().key())&&revision==playback.revision();}

        public long release(Playback playback){long target=matches(playback)?position:-1;cancel();return target;}

        public void cancel(){active=false;key="";}

    }

    private static java.net.URI loginUri(Snapshot data){if(data.login()==null||data.login().complete())return null;try{java.net.URI uri=java.net.URI.create(data.login().url());return MusicNetwork.allowed(data.provider(),uri)?uri:null;}catch(RuntimeException invalid){return null;}}

    private static String time(long ms){long seconds=Math.max(0,ms/1000);return seconds/60+":"+String.format(java.util.Locale.ROOT,"%02d",seconds%60);}

    @Override public boolean mouseClicked(double x,double y,int button){if(!dispatchClick(x,y,button))focused=null;return true;}

    @Override public boolean mouseReleased(double x,double y,int button){if(coverDragging&&button==0){mouseFromEvent(x,y);if(spaceLayout()){if(space.release(mx,my)){int index=covers.hitSpace(space,884,carouselH,mx-214,my-carouselY);if(index>=0){space.select(index);playTrack(index);}}}else if(wallLayout()){int index=wall.release(mx-214,my-carouselY);if(index>=0){wall.select(index);playTrack(index);}}else motion.release();coverDragging=false;}if(button==0&&seek.active()){mouseFromEvent(x,y);if(drag!=null)drag.drag(mx,my);long target=seek.release(player.snapshot().playback());if(target>=0)player.seek(target);}return super.mouseReleased(x,y,button);}

    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical){
        if(qualityMenu)return true;
        if(carouselHitHeight<=0||commentTarget!=null||showPlaylists||showRecommendations)return dispatchScroll(x,y,vertical);
        mouseFromEvent(x,y);if(wallLayout()&&!observed.isEmpty()&&over(214,carouselY,884,carouselHitHeight)){if(Math.abs(horizontal)>.01)wall.pan((float)-horizontal*40,0);if(Math.abs(vertical)>.001)wall.zoomAt(mx-214,my-carouselY,vertical);return true;}if(!observed.isEmpty()&&over(214,carouselY,884,carouselHitHeight)&&Math.abs(horizontal)>.01){moveCover(horizontal>0?-1:1);return true;}return dispatchScroll(x,y,vertical);}
    /** Handle the panel shortcut before world overlay or game input routing. */
    public static boolean panelKey(int key, int action) {
        if (action != GLFW.GLFW_PRESS || key != ClientSettings.INSTANCE.guiKey || key < 0) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof MusicPlayerScreen music) {
            music.focused = null;
            music.qualityMenu = false;
            suspended = music;
            mc.setScreen(null);
            return true;
        }
        return resumeFromPanelKey();
    }

    public static boolean resumeFromPanelKey() {
        if (suspended == null) return false;
        clearResumeOutsideWorld();
        if (suspended == null) return false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || mc.getOverlay() != null || Config.restartRequired()) return false;
        MusicPlayerScreen music = suspended;
        suspended = null;
        mc.setScreen(music);
        return true;
    }

    public static void clearResumeOutsideWorld() {
        if (suspended == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.level == suspended.displayLevel && !Config.restartRequired()) return;
        MusicPlayerScreen previous = suspended;
        suspended = null;
        previous.closeTrackDetails();
        previous.closeComments();
    }

    @Override public boolean keyPressed(int key,int scan,int mods){
        if(key==GLFW.GLFW_KEY_ESCAPE){
            if(qualityMenu)qualityMenu=false;
            else if(replyingTo!=null&&!player.comments().writing()){composeReply(null);focused=null;}
            else if(commentTarget!=null)closeComments();
            else if(detail.track()!=null){if(!detail.back())closeTrackDetails();}
            else if(searchReturn!=null)returnFromSearch();
            else if(recommendationDetail)openRecommendations();
            else if(playlistReturn!=null||!activePlaylist.isBlank())openPlaylists();
            else if(showPlaylists||showRecommendations)openLiked();
            else if(focused!=null)focused=null;
            else onClose();
            return true;
        }
        if(focused!=null)return focused.keyPressed(key,mods);
        if(key==GLFW.GLFW_KEY_SPACE){toggleDetailPlayback();return true;}
        if(commentTarget==null&&!showPlaylists&&!showRecommendations&&(key==GLFW.GLFW_KEY_LEFT||key==GLFW.GLFW_KEY_RIGHT)){
            int direction=key==GLFW.GLFW_KEY_LEFT?-1:1;
            if(detail.track()==null)moveCover(direction);
            else {enableMusic();player.next(direction);}
            return true;
        }
        return false;
    }
    @Override public boolean charTyped(char character,int mods){return focused!=null&&focused.charTyped(character);}

    @Override public void removed(){

        super.removed();if (suspended != this) { closeTrackDetails(); closeComments(); }
        covers.close();if(artwork!=null){artwork.close();artwork=null;}coverDragging=false;seek.cancel();drag=null;titleFade.clear();footerFade.clear();resetCovers(frontIndex());}
    @Override public void onClose(){mc.setScreen(parent);}

    @Override public boolean isPauseScreen(){return false;}

}
