package com.cheatprogram.render;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;

/** Shares only the current color attachment; album occlusion uses its own disposable depth buffer. */
public final class WorldAlbumDepthLayer implements AutoCloseable {
    private int framebuffer, depth, width, height, samples, depthFormat;

    /** The caller restores its complete render state after drawing the layer. */
    public boolean begin() {
        return begin(false);
    }

    /** With inheritance, world occlusion is copied into the private depth buffer, never written back. */
    public boolean begin(boolean inheritWorldDepth) {
        return begin(inheritWorldDepth, false);
    }

    public boolean begin(boolean inheritWorldDepth, boolean reversedDepth) {
        int previousDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        int previousRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        if (previousDraw == 0) return false;
        int type = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
        int color = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
        int nextWidth, nextHeight, nextSamples = GL11.glGetInteger(GL13.GL_SAMPLES), level = 0;
        int textureTarget = nextSamples > 0 ? GL32.GL_TEXTURE_2D_MULTISAMPLE : GL11.GL_TEXTURE_2D;
        if (color == 0) return false;
        if (type == GL11.GL_TEXTURE) {
            level = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LEVEL);
            if (GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL32.GL_FRAMEBUFFER_ATTACHMENT_LAYERED) != 0) return false;
            int previousTexture = GL11.glGetInteger(nextSamples > 0 ? GL32.GL_TEXTURE_BINDING_2D_MULTISAMPLE : GL11.GL_TEXTURE_BINDING_2D);
            GL11.glBindTexture(textureTarget, color);
            nextWidth = GL11.glGetTexLevelParameteri(textureTarget, level, GL11.GL_TEXTURE_WIDTH);
            nextHeight = GL11.glGetTexLevelParameteri(textureTarget, level, GL11.GL_TEXTURE_HEIGHT);
            GL11.glBindTexture(textureTarget, previousTexture);
        } else if (type == GL30.GL_RENDERBUFFER) {
            int previousBuffer = GL11.glGetInteger(GL30.GL_RENDERBUFFER_BINDING);
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, color);
            nextWidth = GL30.glGetRenderbufferParameteri(GL30.GL_RENDERBUFFER, GL30.GL_RENDERBUFFER_WIDTH);
            nextHeight = GL30.glGetRenderbufferParameteri(GL30.GL_RENDERBUFFER, GL30.GL_RENDERBUFFER_HEIGHT);
            nextSamples = GL30.glGetRenderbufferParameteri(GL30.GL_RENDERBUFFER, GL30.GL_RENDERBUFFER_SAMPLES);
            GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, previousBuffer);
        } else return false;
        if (nextWidth <= 0 || nextHeight <= 0) return false;
        int nextDepthFormat = GL30.GL_DEPTH_COMPONENT24;
        if (inheritWorldDepth) {
            int sourceType = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
            int sourceDepth = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
            if (sourceDepth == 0) return false;
            if (sourceType == GL11.GL_TEXTURE) {
                int previousTexture = GL11.glGetInteger(nextSamples > 0 ? GL32.GL_TEXTURE_BINDING_2D_MULTISAMPLE : GL11.GL_TEXTURE_BINDING_2D);
                int sourceLevel = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_FRAMEBUFFER_ATTACHMENT_TEXTURE_LEVEL);
                GL11.glBindTexture(textureTarget, sourceDepth);
                nextDepthFormat = GL11.glGetTexLevelParameteri(textureTarget, sourceLevel, GL11.GL_TEXTURE_INTERNAL_FORMAT);
                GL11.glBindTexture(textureTarget, previousTexture);
            } else if (sourceType == GL30.GL_RENDERBUFFER) {
                int previousBuffer = GL11.glGetInteger(GL30.GL_RENDERBUFFER_BINDING);
                GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, sourceDepth);
                nextDepthFormat = GL30.glGetRenderbufferParameteri(GL30.GL_RENDERBUFFER, GL30.GL_RENDERBUFFER_INTERNAL_FORMAT);
                GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, previousBuffer);
            } else return false;
        }
        if (framebuffer == 0) framebuffer = GL30.glGenFramebuffers();
        if (depth == 0) depth = GL30.glGenRenderbuffers();
        int previousBuffer = GL11.glGetInteger(GL30.GL_RENDERBUFFER_BINDING);
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, depth);
        if (width != nextWidth || height != nextHeight || samples != nextSamples || depthFormat != nextDepthFormat) {
            width = nextWidth; height = nextHeight; samples = nextSamples; depthFormat = nextDepthFormat;
            if (samples > 0) GL30.glRenderbufferStorageMultisample(GL30.GL_RENDERBUFFER, samples, depthFormat, width, height);
            else GL30.glRenderbufferStorage(GL30.GL_RENDERBUFFER, depthFormat, width, height);
        }
        GL30.glBindRenderbuffer(GL30.GL_RENDERBUFFER, previousBuffer);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, framebuffer);
        if (type == GL11.GL_TEXTURE) GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, textureTarget, color, level);
        else GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL30.GL_RENDERBUFFER, color);
        GL30.glFramebufferRenderbuffer(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT, GL30.GL_RENDERBUFFER, depth);
        GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
        GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
        if (GL30.glCheckFramebufferStatus(GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDraw);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousRead);
            return false;
        }
        // This FBO does not contain the game's depth attachment. Clearing it cannot erase world occlusion.
        boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        double clearDepth = GL11.glGetDouble(GL11.GL_DEPTH_CLEAR_VALUE);
        GL11.glDepthMask(true); GL11.glClearDepth(reversedDepth ? 0 : 1); GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);
        GL11.glClearDepth(clearDepth); GL11.glDepthMask(depthMask);
        if (inheritWorldDepth) {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousDraw);
            GL30.glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL11.GL_DEPTH_BUFFER_BIT, GL11.GL_NEAREST);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, framebuffer);
        }
        return true;
    }

    @Override public void close() {
        if (framebuffer != 0) GL30.glDeleteFramebuffers(framebuffer);
        if (depth != 0) GL30.glDeleteRenderbuffers(depth);
        framebuffer = depth = width = height = samples = depthFormat = 0;
    }
}
