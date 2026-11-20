package com.cheatprogram.gui;

import com.cheatprogram.ai.BuildingDesignJob;
import com.cheatprogram.core.Lang;
import com.cheatprogram.core.Notifications;
import com.cheatprogram.core.setting.NumberSetting;
import com.cheatprogram.module.world.BuildingBlueprints;
import com.cheatprogram.render.Backdrop;
import com.cheatprogram.render.BuildingModelPreview;
import com.cheatprogram.render.Icon;
import com.cheatprogram.render.R2D;
import com.cheatprogram.util.Anim;
import com.cheatprogram.util.BuildingFiles;
import com.cheatprogram.util.BuildingFiles.Blueprint;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.Util;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local building library with a full-proportion model viewer and visible world placement. */
public final class BuildingBlueprintScreen extends GuiBase {
	private static final float W = 720, H = 452;
	private static final ExecutorService FILE_CHOOSER = Executors.newSingleThreadExecutor(task -> {
		Thread thread = new Thread(task, "CaeDuex building file chooser"); thread.setDaemon(true); return thread;
	});
	private final Screen parent;
	private final BuildingBlueprints module;
	private final BuildingModelPreview preview = new BuildingModelPreview();
    private final BuildingTutorial tutorial = new BuildingTutorial(this);
	private final TextField name = new TextField(100), xOffset = new TextField(12), yOffset = new TextField(12), zOffset = new TextField(12);
	private final Scroller filesScroll = new Scroller(), placementScroll = new Scroller();
	private int page;
	private float panelHeight = H;
	private List<String> files = List.of();
	private Blueprint displayed;
    private com.cheatprogram.util.BuildingInspection inspection;
    private int floor=-1;
    private List<com.cheatprogram.util.BuildingInspection.Material> materialRows=List.of();
    private volatile long inspectionRequest;
    private boolean inspecting;
    private final AiConnectionPicker aiModels=new AiConnectionPicker(com.cheatprogram.ai.AiBuildingDesigner.CONNECTION);
    private final AiImagePicker aiImages=new AiImagePicker();
    private final AiBuildingWorkshopPanel workshop=new AiBuildingWorkshopPanel();
    private long exchangeRequest,nextExchangePoll;
    private boolean exchangePolling;
    private final TextField floorField=new TextField(12), aiEndpoint=new TextField(2048), aiKey=new TextField(4096), aiPrompt=new TextField();
    private final Scroller materialsScroll=new Scroller();
    private final java.util.Map<String,BuildingModelPreview> materialModels=new java.util.HashMap<>();
    private final BuildingDesignJob designJob=BuildingDesignJob.INSTANCE;
    private BuildingDesignJob.State observedDesign;
    private boolean tourLooking,tourBaseline;
    private double tourMouseX,tourMouseY;
    private int tourPreviousCursor;
    private String designStatus="";

	private volatile boolean closed;
	private boolean exportingFile;
	private long filesRequest, lastViewerClick, libraryRevision, exportRequest;
	private String fileError = "", exportStatus = "";
	public BuildingBlueprintScreen(Screen parent, BuildingBlueprints module) {
		super(Lang.t("ui.building.title")); this.parent = parent; this.module = module;
		module.setEnabled(true); name.setText(module.loaded() == null ? "" : module.loaded().name());
		libraryRevision = module.libraryRevision();
        aiEndpoint.setText(com.cheatprogram.ai.AiBuildingDesigner.CONNECTION.endpoint());
        aiKey.masked=true;
        var active=designJob.state();
        if(active.id()!=0){page=3;aiPrompt.setText(active.requirement());}
        if(designJob.exchange()!=null){page=3;aiPrompt.setText(designJob.exchange().requirement());}
        aiEndpoint.onChange=value->{aiKey.setText("");aiModels.invalidate();};aiKey.onChange=value->aiModels.invalidate();
        floorField.setText("1");floorField.onEnter=()->{try{setFloor(Integer.parseInt(floorField.text)-1);}catch(NumberFormatException ignored){floorField.setText(Integer.toString(Math.max(0,floor)+1));}};

		bindOffset(xOffset, module.offsetX); bindOffset(yOffset, module.offsetY); bindOffset(zOffset, module.offsetZ);
		name.onEnter = () -> { if (!busy()) { exportStatus = ""; module.save(name.text); } }; refreshFiles();
	}
    /** World switches dispose the original screen's rendering and input resources. */
    public BuildingBlueprintScreen reopen() {
        BuildingBlueprintScreen reopened = new BuildingBlueprintScreen(parent, module);
        reopened.page = page;
        reopened.name.setText(name.text);
        reopened.aiPrompt.setText(aiPrompt.text);
        return reopened;
    }

	private void bindOffset(TextField field, NumberSetting setting) {
		field.setText(Integer.toString(setting.intVal()));
		field.onChange = value -> { if (busy()) return; try { setting.set((double) Integer.parseInt(value)); } catch (NumberFormatException ignored) { } };
		field.onEnter = () -> field.setText(Integer.toString(setting.intVal()));
	}
	@Override protected void init(){closed=false;displayed=null;refreshFiles();}
	private void refreshFiles() {
		long request = ++filesRequest;
		BuildingFiles.IO.execute(() -> {
			List<String> found; String error = "";
			try { found = BuildingFiles.list(); } catch (Exception failure) { found = List.of(); error = Lang.t("ui.building.fileError"); }
			List<String> result = found; String detail = error;
			mc.execute(() -> { if (!closed && filesRequest == request) { files = result; fileError = detail; } });
		});
	}
	private boolean busy() { return module.busy() || exportingFile || designJob.state().running(); }
	private void exportFile() {
		Blueprint building = module.loaded();
		if (busy() || building == null || closed) return;
		exportingFile = true; exportStatus = Lang.t("ui.building.exportingFile"); long token = ++exportRequest;
		FILE_CHOOSER.execute(() -> {
			String chosen = null; boolean unavailable = false;
			try (MemoryStack stack = MemoryStack.stackPush()) {
				var filters = stack.mallocPointer(1); filters.put(stack.UTF8("*.cpbuilding")).flip();
				chosen = TinyFileDialogs.tinyfd_saveFileDialog(Lang.t("ui.building.exportFile"),
						BuildingFiles.cleanName(building.name()) + ".cpbuilding", filters, com.cheatprogram.CheatProgram.DISPLAY_NAME + " building (*.cpbuilding)");
			} catch (Throwable missingChooser) { unavailable = true; }
			String selection = chosen; boolean failed = unavailable;
			mc.execute(() -> {
				if (closed || exportRequest != token) return;
				if (failed) {
					exportingFile = false;
					exportStatus = Lang.t("ui.building.fileDialogUnavailable") + " · " + BuildingFiles.directory();
					try { Util.getPlatform().openPath(BuildingFiles.directory()); } catch (RuntimeException ignored) { }
					return;
				}
				if (selection == null) { exportingFile = false; exportStatus = Lang.t("ui.building.exportFileCancelled"); return; }
				BuildingFiles.IO.execute(() -> {
					Path destination = null;
					try { destination = BuildingFiles.export(building, Path.of(selection)); }
					catch (Exception invalidFile) { /* Keep the library entry; report failure on the client thread. */ }
					Path result = destination;
					mc.execute(() -> {
						String detail = Lang.t(result == null ? "ui.building.exportFileFailed" : "ui.building.exportedFile");
						if (result != null) detail += " · " + result;
						if (!closed && exportRequest == token) { exportingFile = false; exportStatus = detail; }
						Notifications.push(Lang.t("ui.building.exportFile"), detail, result != null);
					});
				});
			});
		});
	}
	@Override public void render(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		if (libraryRevision != module.libraryRevision()) { libraryRevision = module.libraryRevision(); refreshFiles(); }
		syncDesignJob();
        pollDesignExchange();
        beginFrame();
        if (tutorial.immersive()) {
            R2D.begin(graphics);
            try {
                float width = R2D.width(), height = R2D.height();
                float scale = Math.min(width / 720, height / 460);
                c.set(scale, 0, 0);
                updateMouse();
                tutorial.draw(width / scale, height / scale,
                        (model, x, y, w, h) -> model.draw(graphics, x, y, w, h));
            } finally { R2D.end(); endFrame(); }
            return;
        }
        if(preview.touring){R2D.begin(graphics);try{c.set(1,0,0);updateMouse();tourView(graphics);}finally{R2D.end();endFrame();}return;}
		if (!preparePanelBackground(graphics, partialTick)) { endFrame(); return; }
		R2D.begin(graphics);
		try {
			panelHeight = layoutPanel(W, H);
            if(page==3&&workshop.overlayOpen()){
                Backdrop.capture(Theme.frost());
                R2D.applyState();
                workshop.overlay(this,W,panelHeight);
                return;
            }
            drawPanelSurface(graphics, W, panelHeight, 26);
            if (tutorial.active()) {
                tutorial.draw(W, panelHeight, (model, x, y, width, height) -> model.draw(graphics, x, y, width, height));
                return;
            }
			c.textC(BOLD, Lang.t("ui.building.title"), 24, 28, 16, Theme.TEXT);
			button("building.share",Lang.t("ui.scopedShare.open"),Icon.LINK,492,14,170,28,false,()->{if(!busy())mc.gui.setScreen(new ScopedShareScreen(this,com.cheatprogram.core.ScopedPresetCodec.Scope.BUILDING));});
			iconButton("building.close", Icon.CLOSE, W - 28, 28, 16, Theme.TEXT_2, this::onClose);
            String[] tabs={"ui.building.tabLibrary","ui.building.tabPlacement","ui.building.materials","ui.building.ai.title"};
            for(int i=0;i<tabs.length;i++){final int tab=i;button("building.page."+i,Lang.t(tabs[i]),null,24+i*172,53,164,29,page==i,()->{page=tab;focused=null;});}
            float top=98,bottom=panelHeight-76;
            if(page==0){libraryPane(top,bottom);viewer(graphics,280,top,422,bottom-top);}
            else if(page==1){viewer(graphics,18,top,420,bottom-top);placementPane(top,bottom);}
            else if(page==2){viewer(graphics,18,top,420,bottom-top);materialsPane(graphics,top,bottom);}
            else {aiPane(top,bottom);viewer(graphics,280,top,422,bottom-top);}
			String status = !exportStatus.isBlank() ? exportStatus : module.status().isBlank() ? fileError : module.status();
			c.textC(REG, c.ellipsize(REG, status, 9.5f, W - 48), 24, panelHeight - 61, 9.5f, Theme.TEXT_2);
			float footer = panelHeight - 42;
			button("building.back", Lang.t("ui.back"), Icon.BACK, 24, footer, 82, 28, false, this::onClose);
			button("building.world", Lang.t("ui.building.returnWorld"), null, 114, footer, 126, 28, false, () -> mc.gui.setScreen(null));
			if (module.busy()) button("building.cancel", Lang.t("ui.building.cancel"), null, 248, footer, 112, 28, false, () -> { exportStatus = ""; module.cancel(); });
            else if (page == 0 && module.loaded() != null) {
                button("building.library.tutorial", Lang.t("ui.building.tutorial"), Icon.CUBE, 248, footer, 188, 28, true,
                        this::openTutorial);
            }
			boolean undoAvailable = !busy() && module.canUndo();
			R2D.pushAlpha(undoAvailable ? 1 : .4f);
			try { button("building.undo", Lang.t("ui.building.undo"), null, 444, footer, 116, 28, false, () -> { if (undoAvailable) { exportStatus = ""; module.undo(); } }); }
			finally { R2D.popAlpha(); }
			action("building.export", "ui.building.export", 568, footer, 128, 28, true, module::paste);
            aiModels.overlay(this,W,panelHeight);
		} finally { R2D.end(); endFrame(); }
	}

    private void openTutorial() {
        Blueprint loaded = module.loaded();
        if (loaded == null) return;
        focused = null;
        drag = null;
        tutorial.open(loaded);
    }
	private void libraryPane(float top, float bottom) {
		float x = 28, width = 228;
		c.lens(18, top, 248, bottom - top, 14, Theme.lift(.025f), Theme.lensGlass(c.scale));
		hit(18, top, 248, bottom - top).scroll(filesScroll::scroll);
		c.clip(20, top, 244, bottom - top); pushHitClip(20, top, 244, bottom - top);
		float y = top + 16 - filesScroll.get(), start = y;
		c.textC(MED, Lang.t("ui.building.selection"), x, y, 11.5f, Theme.TEXT); y += 26;
		y = selectionLabel("ui.building.first", module.firstLabel(), x, y, width);
		y = selectionLabel("ui.building.second", module.secondLabel(), x, y, width);
		y = selectionLabel("ui.building.dimensions", module.dimensions(), x, y, width);
		action("building.first", "ui.building.markFirst", x, y, 110, 28, false, () -> { module.markFirst(); mc.gui.setScreen(null); });
		action("building.second", "ui.building.markSecond", x + 118, y, 110, 28, false, () -> { module.markSecond(); mc.gui.setScreen(null); }); y += 38;
		action("building.clear", "ui.building.clear", x, y, width, 28, false, module::clearSelection); y += 40;
		selectBox("building.selectionMode", "", module.selectionMode.display(), x, y, width, 28, false, () -> {
			if (!busy()) module.selectionMode.set(module.selectionMode.is("building_raycast") ? "building_distance" : "building_raycast");
		}); y += 47;
		c.textC(REG, Lang.t("setting.buildingblueprints.selectionDistance") + "  " + module.selectionDistance.format(), x, y, 10.5f, Theme.TEXT_2);
		lockedSlider(module.selectionDistance, x + 5, y + 23, width - 10); y += 45;
		for (String line : c.wrap(REG, Lang.t("ui.building.airHint"), 8.5f, width)) { c.textC(REG, line, x, y, 8.5f, Theme.TEXT_3); y += 13; }
		y += 18; c.textC(MED, Lang.t("ui.building.saveName"), x, y, 11, Theme.TEXT_2); y += 15;
		textField(name, Lang.t("ui.building.nameHint"), Icon.FOLDER, x, y, width, 29); y += 39;
		action("building.save", "ui.building.saveCopy", x, y, width, 29, true, () -> module.save(name.text)); y += 49;
		c.textC(MED, Lang.t("ui.building.library"), x, y + 12, 11, Theme.TEXT_2);
		button("building.refresh", Lang.t("ui.building.refresh"), null, x + width - 68, y, 68, 25, false, this::refreshFiles); y += 36;
		if (files.isEmpty()) { c.textC(REG, Lang.t("ui.building.noFiles"), x, y + 12, 9.5f, Theme.TEXT_3); y += 34; }
		for (String file : files) {
			if (y + 29 >= top && y < bottom) {
				boolean selected = module.loaded() != null && module.loaded().name().equals(file);
				if (selected || over(x, y, width - 62, 29)) c.rrect(x, y, width - 62, 29, 9, Anim.white(selected ? .16f : .07f));
				c.textC(REG, c.ellipsize(REG, file, 10.5f, width - 80), x + 9, y + 14, 10.5f, Theme.TEXT_2);
				hit(x, y, width - 62, 29).left(() -> { if (!busy()) { exportStatus = ""; module.load(file); } });
				action("building.delete." + file, "ui.building.delete", x + width - 56, y + 1, 56, 27, false, () -> module.delete(file));
			}
			y += 34;
		}
		y += 10; R2D.pushAlpha(module.loaded() != null && !busy() ? 1 : .4f);
		try { button("building.exportFile", Lang.t("ui.building.exportFile"), Icon.FOLDER, x, y, width, 28, false, this::exportFile); }
		finally { R2D.popAlpha(); } y += 42;
		popHitClip(); c.unclip();
		filesScroll.max = Math.max(0, y - start - (bottom - top) + 26); filesScroll.update(dt);
		panelScrollBar("building.library.scroll", filesScroll, 260, top + 5, bottom - top - 10);
	}
	private float selectionLabel(String key, String value, float x, float y, float width) {
		c.textC(REG, Lang.t(key), x, y, 10.5f, Theme.TEXT_3);
		c.textRight(REG, c.ellipsize(REG, value, 10.5f, width - 55), x + width, y, 10.5f, Theme.TEXT_2); return y + 28;
	}
	private void viewer(GuiGraphicsExtractor graphics, float x, float y, float width, float height) {
		Blueprint loaded = module.loaded();
		if(displayed!=loaded){displayed=loaded;floor=-1;inspection=null;materialRows=List.of();preview.set(loaded);materialModels.values().forEach(BuildingModelPreview::close);materialModels.clear();inspectionRequest++;if(loaded!=null){name.setText(loaded.name());requestInspection(-1,false);}}
		c.lens(x, y, width, height, 14, Theme.lift(.025f), Theme.lensGlass(c.scale));
        String caption=Lang.t("ui.building.preview");
        float titleWidth=Math.min(width-24,c.textWidth(MED,caption,14)+36);
        c.lens(x+11,y+9,titleWidth,25,12.5f,Theme.lift(.06f),Theme.lensGlass(c.scale));
        c.icon(Icon.CUBE,x+25,y+21.5f,12,Theme.TEXT_2);
        c.textC(MED,caption,x+37,y+21.5f,14,Theme.TEXT);
        if(loaded!=null){
            String dimensions=loaded.dimensions();
            float dimensionsWidth=c.textWidth(MED,dimensions,12)+20;
            c.lens(x+width-11-dimensionsWidth,y+9,dimensionsWidth,25,12.5f,Theme.lift(.06f),Theme.lensGlass(c.scale));
            c.textMid(MED,dimensions,x+width-11-dimensionsWidth/2,y+21.5f,12,Theme.TEXT_2);
            c.textC(REG,c.ellipsize(REG,loaded.name(),12.5f,width-26),x+13,y+41,12.5f,Theme.TEXT_3);
        }
        if(loaded!=null){
            button("building.analyze",Lang.t("ui.building.analyze"),Icon.LAYOUT,x+10,y+50,76,24,floor>=0,()->setFloor(floor<0?0:-1));
            if(floor>=0){button("building.floor.prev","−",null,x+88,y+49,24,24,false,()->setFloor(Math.max(0,floor-1)));textField(floorField,"1",null,x+116,y+49,46,24);button("building.floor.next","+",null,x+166,y+49,24,24,false,()->setFloor(floor+1));c.textC(REG,"/ "+loaded.height(),x+196,y+61,11,Theme.TEXT_2);}
            button("building.tour", Lang.t("ui.building.tour"), Icon.NAVIGATION,
                    x + width - 100, y + 50, 90, 24, false, () -> {
                        focused = null;
                        if (BuildingWorldPreview.openTour(this, loaded)) return;
                        inspectionRequest++;
                        inspecting = false;
                        preview.set(loaded);
                        preview.tour(true);
                    });
        }
        boolean sceneControls = loaded != null || page == 3;
        if (sceneControls) BuildingPreviewControls.draw(this, x + 12, y + 80, width - 24);
        float viewOffset = sceneControls ? 87 + BuildingPreviewControls.HEIGHT : 80;
        float viewX=x+8,viewY=y+viewOffset,viewWidth=width-16,
                viewHeight=Math.max(48,height-viewOffset-42);
		c.clip(viewX, viewY, viewWidth, viewHeight);
		try { preview.draw(graphics, c.px(viewX), c.py(viewY), viewWidth * c.scale, viewHeight * c.scale); }
		finally { c.unclip(); }
		if (loaded == null || preview.failed()) c.textMid(REG, c.ellipsize(REG, Lang.t(preview.failed() ? "ui.building.previewError" : "ui.building.previewEmpty"), 10.5f, viewWidth - 12), viewX + viewWidth / 2, viewY + viewHeight / 2, 10.5f, Theme.TEXT_3);
		if (loaded != null && preview.renderedBlocks() < (floor<0?loaded.count():loaded.width()*loaded.depth())) c.textRight(REG, preview.renderedBlocks() + " / " + (floor<0?loaded.count():loaded.width()*loaded.depth()), x + width - 12, y + height - 47, 11.5f, Theme.TEXT_2);
		float hintY = y + height - 29;
		for (String line : c.wrap(REG, Lang.t("ui.building.previewControls"), 11.5f, width - 26)) { c.textC(REG, line, x + 13, hintY, 11.5f, Theme.TEXT_2); hintY += 16; }
		hit(viewX, viewY, viewWidth, viewHeight).click(button -> {
			focused = null; long now = System.currentTimeMillis();
			if (button == 0 && now - lastViewerClick < 300) { preview.reset(); lastViewerClick = 0; return; }
			lastViewerClick = button == 0 ? now : 0;
			float startX = mx, startY = my, yaw = preview.yaw, pitch = preview.pitch, px = preview.panX, py = preview.panY;
			if (button == 0) drag = (mouseX, mouseY) -> { preview.yaw = yaw + (mouseX - startX) * .5f; preview.pitch = pitch + (mouseY - startY) * .5f; };
			else if (button == 1) drag = (mouseX, mouseY) -> { preview.panX = px + (mouseX - startX) / viewHeight * 2; preview.panY = py + (mouseY - startY) / viewHeight * 2; };
		}).scroll(amount -> preview.zoom = Math.max(.05f, Math.min(25, preview.zoom * (float) Math.pow(1.12, amount))));
	}

    private void requestInspection(int layer,boolean updatePreview){
        Blueprint snapshot=displayed;if(snapshot==null)return;long request=++inspectionRequest;inspecting=true;var existing=inspection;
        BuildingFiles.IO.execute(()->{
            try{
                if(closed||inspectionRequest!=request)return;var model=existing==null?new com.cheatprogram.util.BuildingInspection(snapshot,()->!closed&&inspectionRequest==request):existing;
                var rows=model.materials(layer);var slice=updatePreview?(layer<0?snapshot:model.slice(layer)):null;
                mc.execute(()->{if(closed||displayed!=snapshot||inspectionRequest!=request)return;inspection=model;materialRows=rows;inspecting=false;if(slice!=null)preview.set(slice);});
            }catch(RuntimeException error){mc.execute(()->{if(!closed&&inspectionRequest==request){inspecting=false;designStatus=Lang.t("ui.building.previewError");}});}
        });
    }
    private void setFloor(int value){
        if(displayed==null)return;floor=value<0?-1:Math.min(displayed.height()-1,value);
        floorField.setText(Integer.toString(Math.max(0,floor)+1));materialsScroll.target=0;
        requestInspection(floor,true);
    }
    private void exitTour(){stopTourLook();preview.tour(false);preview.reset();if(displayed!=null)requestInspection(floor,true);}
    private void materialsPane(GuiGraphicsExtractor graphics,float top,float bottom){
        float x=464,width=226;
        c.lens(452,top,250,bottom-top,14,Theme.lift(.025f),Theme.lensGlass(c.scale));
        c.textC(MED,Lang.t("ui.building.materials"),x,top+18,12,Theme.TEXT);
        if(displayed==null)return;
        c.textC(REG,Lang.t("ui.building.maxDimensions",displayed.width(),displayed.depth(),displayed.height()),x,top+39,9,Theme.TEXT_2);
        c.textC(REG,Lang.t(floor<0?"ui.building.allFloors":"ui.building.floor",floor+1),x,top+59,10,Theme.TEXT_2);
        float y=top+80-materialsScroll.get(),start=y,visibleTop=top+73;
        hit(454,visibleTop,240,bottom-visibleTop).scroll(materialsScroll::scroll);
        c.clip(454,visibleTop,240,bottom-visibleTop);pushHitClip(454,visibleTop,240,bottom-visibleTop);
        if(inspecting)c.textC(REG,Lang.t("ui.building.counting"),x,y+12,10,Theme.TEXT_2);
        for(var material:inspecting?List.<com.cheatprogram.util.BuildingInspection.Material>of():materialRows){
            if(y+42>=visibleTop&&y<bottom){
                var model=materialModels.computeIfAbsent(material.id(),id->{var m=new BuildingModelPreview();m.materialIcon();m.set(new Blueprint(id,1,1,1,List.of(material.state()),new int[]{0},java.util.Map.of()));return m;});
                model.draw(graphics,c.px(x),c.py(y),38*c.scale,38*c.scale);
                String[] id=material.id().split(":",2);String label=net.minecraft.network.chat.Component.translatable("block."+id[0]+"."+id[1].replace('/','.')).getString();
                c.textC(REG,c.ellipsize(REG,label,9.5f,width-45),x+43,y+12,9.5f,Theme.TEXT);
                c.textC(REG,"× "+material.count(),x+43,y+29,9.5f,Theme.TEXT_2);
            }y+=46;
        }
        popHitClip();c.unclip();materialsScroll.max=Math.max(0,y-start-(bottom-visibleTop));materialsScroll.update(dt);
        panelScrollBar("building.material.scroll",materialsScroll,696,visibleTop,bottom-visibleTop);
    }
    private void aiPane(float top,float bottom){
        float x=28,width=228,y=top+15;
        c.lens(18,top,248,bottom-top,14,Theme.lift(.025f),Theme.lensGlass(c.scale));
        hit(18,top,248,bottom-top).scroll(filesScroll::scroll);c.clip(20,top,244,bottom-top);pushHitClip(20,top,244,bottom-top);
        y-=filesScroll.get();float start=y;
        var active=designJob.state();
        if(active.workshop()){
            workshop.progressButton(this,x,y,width);y+=35;
        }
        if(active.running()){
            button("building.ai.cancel",Lang.t("ui.building.ai.cancel"),null,x,y,width,27,false,this::cancelDesign);y+=35;
        }
        if(!designStatus.isBlank()){
            for(String line:c.wrap(REG,designStatus,9,width)){c.textC(REG,line,x,y+5,9,Theme.TEXT_2);y+=14;}
            y+=10;
        }
        aiModels.providers(this,x,y,width,aiEndpoint,aiKey,()->{});y+=34;
        for(Object[] row:new Object[][]{{"ui.building.ai.endpoint",aiEndpoint},{"ui.building.ai.key",aiKey},{"ui.building.ai.prompt",aiPrompt}}){
            c.textC(REG,Lang.t((String)row[0]),x,y,10,Theme.TEXT_2);y+=13;textField((TextField)row[1],Lang.t((String)row[0]),null,x,y,width,28);y+=43;
        }
        aiModels.draw(this,x,y,width,this::saveAiConnection);y+=60;
        y+=workshop.drawSettings(this,x,y,width,active.running());
        aiImages.draw(this,x,y,width);y+=66;
        button("building.ai.generate",Lang.t(active.design()==null?"ui.building.ai.generate":"ui.building.ai.revise"),null,x,y,width,28,true,this::generateBuilding);y+=36;
        if(designJob.continuation()!=null){
            button("building.ai.handoff",Lang.t("ui.building.ai.handoff"),Icon.NAVIGATION,x,y,width,27,false,
                    ()->copyDesignMcp(true));y+=35;
        }
        button("building.ai.mcp",Lang.t("ui.ai.exchange.copyPrompt"),Icon.LINK,x,y,width,27,false,()->copyDesignMcp(false));y+=35;
        button("building.ai.paste",Lang.t("ui.ai.exchange.importResult"),Icon.PLUS,x,y,width,27,false,this::pasteDesignResult);y+=38;
        button("building.ai.save",Lang.t("ui.building.ai.save"),null,x,y,width,28,false,()->{if(displayed!=null&&!busy()){Blueprint snapshot=displayed;BuildingFiles.IO.execute(()->{try{var saved=BuildingFiles.save(snapshot);mc.execute(()->{if(!closed){module.previewBuilding(saved);refreshFiles();}});}catch(Exception e){mc.execute(()->designStatus=Lang.t("ui.building.fileError"));}});}});y+=40;
        for(String line:c.wrap(REG,Lang.t("ui.building.ai.hint"),9,width)){c.textC(REG,line,x,y,9,Theme.TEXT_2);y+=14;}
        popHitClip();c.unclip();filesScroll.max=Math.max(0,y-start-(bottom-top)+28);filesScroll.update(dt);
    }
    private void saveAiConnection(){
        var config=com.cheatprogram.ai.AiBuildingDesigner.CONNECTION;config.endpoint(aiEndpoint.text);if(!aiKey.text.isBlank()){config.apiKey(aiKey.text);aiKey.setText("");}config.save();
    }
    private void syncDesignJob(){
        var active=designJob.state();
        if(active!=observedDesign){
            if(active.workshop()){
                workshop.observeRun(active.options());
                workshop.snapshot(active.progress());
            }
            if(observedDesign==null||!active.status().equals(observedDesign.status()))
                designStatus=active.status().isBlank()?"":Lang.t(active.status());
            observedDesign=active;
        }
        if(!module.busy()){
            var result=designJob.takePreview();
            if(result!=null)module.previewBuilding(result.building());
        }
    }
    private void generateBuilding(){
        if(busy()||aiPrompt.text.isBlank())return;
        saveAiConnection();
        focused=null;
        closeDesignExchange();
        if(designJob.generate(aiPrompt.text,aiImages.images(),workshop.options())){
            workshop.begin();
            filesScroll.target=0;
            syncDesignJob();
        }
    }
    private void cancelDesign(){
        focused=null;
        designJob.cancel();
        syncDesignJob();
    }
    private void closeDesignExchange(){
        exchangeRequest++;exchangePolling=false;
        var ticket=designJob.exchange();designJob.exchange(null);
        if(ticket!=null)BuildingFiles.IO.execute(ticket::close);
    }
    private void copyDesignMcp(boolean resume){
        if(busy())return;
        var handoff=resume?designJob.continuation():null;
        String requirement=handoff==null?aiPrompt.text:handoff.requirement();
        if(requirement.isBlank()){designStatus=Lang.t("ui.ai.exchange.needPrompt");return;}
        closeDesignExchange();
        long token=exchangeRequest;
        String language=Lang.language();
        var options=handoff==null?workshop.options():handoff.options();
        String rules=com.cheatprogram.ai.AiDesignPrompts.building(language,
                com.cheatprogram.ai.AiBuildingDesigner.minecraftVersion(),options);
        var previous=designJob.state().design();
        var plan=handoff!=null?handoff.plan():previous==null?null:previous.plan();
        int imageCount=handoff==null?aiImages.images().size():handoff.imageCount();
        designStatus=Lang.t("ui.ai.exchange.preparing");
        BuildingFiles.IO.execute(()->{
            try{
                String instructions=handoff==null?rules:rules+"\n\n"+com.cheatprogram.ai.AiDesignPrompts.resume(language);
                var ticket=com.cheatprogram.ai.AiDesignExchange.open("building",requirement,instructions,
                        plan==null?"":plan.toString(),imageCount,language,handoff==null?null:handoff.progressJson());
                mc.execute(()->{
                    if(closed||exchangeRequest!=token){BuildingFiles.IO.execute(ticket::close);return;}
                    designJob.exchange(ticket);nextExchangePoll=0;
                    mc.keyboardHandler.setClipboard(ticket.prompt());
                    designStatus=Lang.t(resume?
                            "ui.building.ai.handoffCopied":"ui.ai.exchange.promptCopied");
                });
            }catch(Exception invalid){mc.execute(()->{
                if(!closed&&exchangeRequest==token)designStatus=Lang.t("ui.ai.exchange.failed");
            });}
        });
    }
    private void pollDesignExchange(){
        var ticket=designJob.exchange();long now=System.nanoTime();
        if(closed||ticket==null||exchangePolling||designJob.state().running()||module.busy()||exportingFile||now<nextExchangePoll)return;
        exchangePolling=true;nextExchangePoll=now+1_000_000_000L;long token=exchangeRequest;
        BuildingFiles.IO.execute(()->{
            try{
                var response=ticket.poll();
                mc.execute(()->{if(closed||exchangeRequest!=token)return;exchangePolling=false;if(response.isPresent())importDesignPlan(ticket.requirement(),()->response.get());});
            }catch(Exception invalid){mc.execute(()->{if(!closed&&exchangeRequest==token){exchangePolling=false;designStatus=Lang.t("ui.ai.exchange.invalid");closeDesignExchange();}});}
        });
    }
    private void pasteDesignResult(){
        if(busy())return;
        try{String clipboard=mc.keyboardHandler.getClipboard();if(clipboard.length()>com.cheatprogram.ai.AiDesignExchange.MAX_BYTES)throw new IllegalArgumentException();importDesignPlan(()->{try{return com.cheatprogram.ai.AiDesignExchange.parseResult(clipboard);}catch(Exception invalid){throw new java.util.concurrent.CompletionException(invalid);}});}
        catch(Exception invalid){designStatus=Lang.t("ui.ai.exchange.invalid");}
    }
    private void importDesignPlan(java.util.function.Supplier<com.google.gson.JsonObject> source){
        var ticket=designJob.exchange();
        importDesignPlan(ticket==null?aiPrompt.text:ticket.requirement(),source);
    }
    private void importDesignPlan(String requirement,java.util.function.Supplier<com.google.gson.JsonObject> source){
        if(busy()||closed)return;
        closeDesignExchange();
        if(designJob.importPlan(requirement,source))aiPrompt.setText(requirement);
        syncDesignJob();
    }
    private void tourView(GuiGraphicsExtractor graphics){
        if (mc.isWindowActive()) {
            long window = mc.getWindow().handle();
            java.util.function.IntPredicate down = key -> GLFW.glfwGetKey(window, key) == GLFW.GLFW_PRESS;
            float forward = (down.test(GLFW.GLFW_KEY_W) ? 1 : 0) - (down.test(GLFW.GLFW_KEY_S) ? 1 : 0);
            float sideways = (down.test(GLFW.GLFW_KEY_D) ? 1 : 0) - (down.test(GLFW.GLFW_KEY_A) ? 1 : 0);
            float vertical = (down.test(GLFW.GLFW_KEY_SPACE) ? 1 : 0) - (down.test(GLFW.GLFW_KEY_LEFT_SHIFT) ? 1 : 0);
            float boost = down.test(GLFW.GLFW_KEY_LEFT_CONTROL) || down.test(GLFW.GLFW_KEY_RIGHT_CONTROL) ? 2 : 1;
            preview.travel(forward, sideways, vertical, dt, boost);
        } else stopTourLook();
        float w=R2D.width(),h=R2D.height();
        c.rrect(0,0,w,h,0,0xff10151e);
        preview.draw(graphics,0,0,w,h);
        Backdrop.capture(.15f);
        R2D.applyState();
        float sceneWidth = Math.min(365, w * .49f);
        float controlsTop = h - BuildingPreviewControls.HEIGHT - 14;
        BuildingPreviewControls.draw(this, w - sceneWidth - 20, controlsTop, sceneWidth);
        String controls = c.ellipsize(REG, Lang.t("ui.building.tourControls"), 13, w - sceneWidth - 50);
        c.textC(REG,controls,20,h-24,13,Theme.TEXT);
        c.textC(BOLD,Lang.t("ui.building.tour"),20,28,18,Theme.TEXT);
        button("building.tour.exit",Lang.t("ui.back"),Icon.BACK,w-140,12,120,34,false,this::exitTour);
        hit(0,52,w,Math.max(0,controlsTop-60)).left(()->{float sx=mx,sy=my,originalYaw=preview.yaw,originalPitch=preview.pitch;drag=(xx,yy)->{preview.yaw=originalYaw+(xx-sx)*.3f;preview.pitch=Math.max(-89,Math.min(89,originalPitch+(yy-sy)*.3f));};});
    }

	private void placementPane(float top, float bottom) {
		float x = 470, width = 220;
		c.lens(452, top, 250, bottom - top, 14, Theme.lift(.025f), Theme.lensGlass(c.scale));
		hit(452, top, 250, bottom - top).scroll(placementScroll::scroll);
		c.clip(454, top, 246, bottom - top); pushHitClip(454, top, 246, bottom - top);
		float y = top + 18 - placementScroll.get(), start = y;
		for (String line : c.wrap(REG, module.placementHint(), 9.5f, width)) { c.textC(REG, line, x, y, 9.5f, Theme.TEXT_2); y += 14; }
		y += 18;
		if (module.remoteWorld()) {
			c.textC(REG, Lang.t("setting.buildingblueprints.remoteDelay") + "  " + module.remoteDelay.format(), x, y, 10.5f, Theme.TEXT_2);
			lockedSlider(module.remoteDelay, x + 5, y + 23, width - 10); y += 48;
			c.textC(REG, Lang.t("setting.buildingblueprints.remoteTimeout") + "  " + module.remoteTimeout.format(), x, y, 10.5f, Theme.TEXT_2);
			lockedSlider(module.remoteTimeout, x + 5, y + 23, width - 10); y += 48;
		}
		c.textC(MED, Lang.t("ui.building.offset"), x, y, 11, Theme.TEXT_2); y += 22;
		y = offsetField(xOffset, "X", x, y, width); y = offsetField(yOffset, "Y", x, y, width); y = offsetField(zOffset, "Z", x, y, width);
		c.textC(REG, Lang.t("setting.buildingblueprints.replaceAir"), x, y + 14, 10.5f, Theme.TEXT_2);
		toggle("building.air", x + width - 36, y + 7, module.replaceAir.on(), busy() ? .4f : 1);
		hit(x, y, width, 30).left(() -> { if (!busy()) module.replaceAir.toggle(); }); y += 44;
		action("building.origin", "ui.building.markOrigin", x, y, width, 28, false, () -> { module.markOrigin(); mc.gui.setScreen(null); }); y += 38;
		action("building.playerOrigin", "ui.building.playerOrigin", x, y, width, 28, false, module::usePlayerOrigin); y += 45;
		for (String line : c.wrap(REG, Lang.t("ui.building.origin") + "  " + module.originLabel(), 9.5f, width)) { c.textC(REG, line, x, y, 9.5f, Theme.TEXT_3); y += 14; } y += 16;
		c.textC(REG, Lang.t("setting.buildingblueprints.worldPreview"), x, y + 14, 10.5f, Theme.TEXT_2);
		toggle("building.worldPreview", x + width - 36, y + 7, module.worldPreview.on(), 1);
		hit(x, y, width, 30).left(module.worldPreview::toggle); y += 49;
		c.textC(REG, Lang.t("setting.buildingblueprints.previewOpacity") + "  " + module.previewOpacity.format(), x, y, 10.5f, Theme.TEXT_2);
		slider(module.previewOpacity, x + 5, y + 23, width - 10); y += 48;
		for (String line : c.wrap(REG, Lang.t("ui.building.lastExport") + "  " + module.undoLabel(), 9.5f, width)) { c.textC(REG, line, x, y, 9.5f, Theme.TEXT_3); y += 14; } y += 18;
		popHitClip(); c.unclip(); placementScroll.max = Math.max(0, y - start - (bottom - top) + 24); placementScroll.update(dt);
		panelScrollBar("building.placement.scroll", placementScroll, 696, top + 5, bottom - top - 10);
	}
	private float offsetField(TextField field, String axis, float x, float y, float width) {
		c.textC(MED, axis, x, y + 14, 11, Theme.TEXT_2); textField(field, "0", null, x + 22, y, width - 22, 29); return y + 40;
	}
	private void lockedSlider(NumberSetting setting, float x, float y, float width) {
		int previousHits = pendingHits.size(); R2D.pushAlpha(busy() ? .5f : 1);
		try { slider(setting, x, y, width); } finally { R2D.popAlpha(); }
		if (busy()) pendingHits.subList(previousHits, pendingHits.size()).clear();
	}
	private void action(String id, String key, float x, float y, float width, float height, boolean primary, Runnable action) {
		R2D.pushAlpha(busy() ? 0.5f : 1);
		try { button(id, Lang.t(key), null, x, y, width, height, primary, () -> { if (!busy()) { exportStatus = ""; action.run(); } }); }
		finally { R2D.popAlpha(); }
	}
    public boolean tourLooking(){return preview.touring&&tourLooking;}
    public void tourMouseMoved(double x,double y,double dx,double dy){if(!tourLooking())return;
        double sensitivity = mc.options.sensitivity().get() * .6 + .2;
        float scale = (float) (sensitivity * sensitivity * sensitivity * 1.2);
        preview.yaw += (float) dx * scale;
        preview.pitch = Math.clamp(preview.pitch + (float) dy * scale, -89, 89);
    }
    private void stopTourLook(){if(!tourLooking)return;tourLooking=false;tourBaseline=false;GLFW.glfwSetInputMode(mc.getWindow().handle(),GLFW.GLFW_CURSOR,tourPreviousCursor);mc.mouseHandler.setIgnoreFirstMove();}
    @Override public boolean mouseClicked(double x,double y,int button){
        if(preview.touring&&button==1){if(tourLooking)stopTourLook();else{long handle=mc.getWindow().handle();tourPreviousCursor=GLFW.glfwGetInputMode(handle,GLFW.GLFW_CURSOR);tourLooking=true;tourBaseline=false;GLFW.glfwSetInputMode(handle,GLFW.GLFW_CURSOR,GLFW.GLFW_CURSOR_DISABLED);mc.mouseHandler.setIgnoreFirstMove();}return true;}
        if(tourLooking)return true;dispatchClick(x,y,button);return true;
    }
	@Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) { dispatchScroll(x, y, vertical); return true; }
	@Override public boolean keyPressed(int key, int scan, int modifiers) {
		if(key==GLFW.GLFW_KEY_ESCAPE){if(tutorial.active()&&tutorial.back())return true;if(workshop.dismiss(this))return true;if(aiModels.dismiss(this))return true;if(preview.touring){if(tourLooking)stopTourLook();else exitTour();}else onClose();return true;}
		if (focused != null) { if (!busy()) focused.keyPressed(key, modifiers); return true; }
		return false;
	}
	@Override public boolean charTyped(char character, int modifiers) { return focused != null && (busy() || focused.charTyped(character)); }
	@Override public void onClose() { mc.gui.setScreen(parent); }
	@Override public void removed() { tutorial.close(); closed = true; exchangeRequest++; stopTourLook(); inspectionRequest++; aiImages.close();aiModels.close(); materialModels.values().forEach(BuildingModelPreview::close);materialModels.clear(); filesRequest++; exportRequest++; drag = null; preview.close(); super.removed(); }
}

