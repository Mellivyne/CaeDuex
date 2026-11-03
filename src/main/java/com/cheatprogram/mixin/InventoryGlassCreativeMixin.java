package com.cheatprogram.mixin;

import com.cheatprogram.render.InventoryGlass;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.Function;

@Mixin(CreativeModeInventoryScreen.class)
public abstract class InventoryGlassCreativeMixin {
    @org.spongepowered.asm.mixin.Shadow private boolean ignoreTextInput;
    @org.spongepowered.asm.mixin.Shadow private net.minecraft.client.gui.components.EditBox searchBox;
    @org.spongepowered.asm.mixin.injection.Inject(method="charTyped",at=@At("HEAD"))
    private void cheatprogram$imeCommit(char character,int modifiers,org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir){
        // IME commits may have no matching raw key event. A previous hotbar or
        // search-tab shortcut must not leave ignoreTextInput latched for CJK.
        if(character>=128&&searchBox!=null&&searchBox.isVisible()&&searchBox.isFocused())ignoreTextInput=false;
    }

	@Redirect(method = "renderTabButton", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;blitSprite(Ljava/util/function/Function;Lnet/minecraft/resources/ResourceLocation;IIII)V"))
	private void cheatprogram$glassTab(GuiGraphics graphics, Function<ResourceLocation, RenderType> type, ResourceLocation sprite, int x, int y, int width, int height) {
		if (!InventoryGlass.control(graphics, (Screen) (Object) this, x, y, width, height, !sprite.getPath().contains("unselected"), false))
			graphics.blitSprite(type, sprite, x, y, width, height);
	}
	@Redirect(method = "renderBg", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;blitSprite(Ljava/util/function/Function;Lnet/minecraft/resources/ResourceLocation;IIII)V"))
	private void cheatprogram$glassScroller(GuiGraphics graphics, Function<ResourceLocation, RenderType> type, ResourceLocation sprite, int x, int y, int width, int height) {
		if (!InventoryGlass.control(graphics, (Screen) (Object) this, x, y, width, height, true, sprite.getPath().contains("disabled")))
			graphics.blitSprite(type, sprite, x, y, width, height);
	}
}
