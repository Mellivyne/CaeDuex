package com.cheatprogram.mixin;



import com.cheatprogram.render.InventoryGlass;

import net.minecraft.client.gui.GuiGraphicsExtractor;

import net.minecraft.client.gui.screens.Screen;

import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;

import net.minecraft.client.renderer.rendertype.RenderType;

import net.minecraft.resources.Identifier;

import org.spongepowered.asm.mixin.Mixin;

import org.spongepowered.asm.mixin.injection.At;

import org.spongepowered.asm.mixin.injection.Redirect;



import java.util.function.Function;



@Mixin(CreativeModeInventoryScreen.class)

public abstract class InventoryGlassCreativeMixin {
    @org.spongepowered.asm.mixin.Shadow private boolean ignoreTextInput;
    @org.spongepowered.asm.mixin.Shadow private net.minecraft.client.gui.components.EditBox searchBox;
    @org.spongepowered.asm.mixin.injection.Inject(method="charTyped",at=@At("HEAD"))
    private void cheatprogram$imeCommit(net.minecraft.client.input.CharacterEvent event,org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Boolean> cir){
        // IME commits may have no matching raw key event. A previous hotbar or
        // search-tab shortcut must not leave ignoreTextInput latched for CJK.
        if(event.codepoint()>=128&&searchBox!=null&&searchBox.isVisible()&&searchBox.isFocused())ignoreTextInput=false;
    }


	@Redirect(method = "extractTabButton", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"))

	private void cheatprogram$glassTab(GuiGraphicsExtractor graphics, com.mojang.blaze3d.pipeline.RenderPipeline type, Identifier sprite, int x, int y, int width, int height) {

		if (!InventoryGlass.control(graphics, (Screen) (Object) this, x, y, width, height, !sprite.getPath().contains("unselected"), false))

			graphics.blitSprite(type, sprite, x, y, width, height);

	}

	@Redirect(method = "extractBackground", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"))

	private void cheatprogram$glassScroller(GuiGraphicsExtractor graphics, com.mojang.blaze3d.pipeline.RenderPipeline type, Identifier sprite, int x, int y, int width, int height) {

		if (!InventoryGlass.control(graphics, (Screen) (Object) this, x, y, width, height, true, sprite.getPath().contains("disabled")))

			graphics.blitSprite(type, sprite, x, y, width, height);

	}

}

