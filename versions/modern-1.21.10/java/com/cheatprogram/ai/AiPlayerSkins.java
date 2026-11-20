package com.cheatprogram.ai;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/** Per-character skin copies. */
final class AiPlayerSkins {
    record Skin(ResourceLocation texture, boolean slim) {}

    private static final Minecraft MC = Minecraft.getInstance();
    private static final ExecutorService FILES = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "AI player skins");
        thread.setDaemon(true);
        return thread;
    });
    private static final Map<UUID, AtomicLong> GENERATIONS = new ConcurrentHashMap<>();

    private AiPlayerSkins() {}

    static CompletableFuture<Skin> importImage(UUID id, BufferedImage original, Path target) {
        if (original == null) return CompletableFuture.failedFuture(new IllegalArgumentException("请选择皮肤 PNG 图片。"));
        if (!AiPlayerSkinPixels.supported(original)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("皮肤需为 64×64 或 64×32 的整数倍，最大 4096×4096。"));
        }
        long generation = nextGeneration(id);
        BufferedImage owned = new BufferedImage(original.getWidth(), original.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D copy = owned.createGraphics();
        try {
            copy.setComposite(AlphaComposite.Src);
            copy.drawImage(original, 0, 0, null);
        } finally {
            copy.dispose();
        }

        CompletableFuture<Skin> result = new CompletableFuture<>();
        FILES.execute(() -> prepare(id, generation, owned, target, result));
        return result;
    }

    static CompletableFuture<Skin> load(UUID id, Path target) {
        long generation = nextGeneration(id);
        CompletableFuture<Skin> result = new CompletableFuture<>();
        FILES.execute(() -> {
            BufferedImage image;
            try {
                image = Files.isRegularFile(target) && Files.size(target) <= 32L * 1024 * 1024
                        ? ImageIO.read(target.toFile()) : null;
            } catch (Exception failure) {
                result.complete(null);
                return;
            }
            if (image == null || !AiPlayerSkinPixels.supported(image)) {
                if (image != null) image.flush();
                result.complete(null);
                return;
            }
            prepare(id, generation, image, null, result);
        });
        return result;
    }

    private static void prepare(UUID id, long generation, BufferedImage image, Path target, CompletableFuture<Skin> result) {
        NativeImage nativeImage = null;
        BufferedImage square = null;
        try {
            if (!current(id, generation)) throw cancelled();
            boolean slim = AiPlayerSkinPixels.slim(image);
            square = AiPlayerSkinPixels.square(image);
            int resolution = square.getWidth();
            nativeImage = new NativeImage(resolution, resolution, false);
            for (int y = 0; y < resolution; y++) {
                if (!current(id, generation)) throw cancelled();
                for (int x = 0; x < resolution; x++) nativeImage.setPixel(x, y, square.getRGB(x, y));
            }
            if (target != null) save(id, generation, image, target);

            NativeImage prepared = nativeImage;
            MC.execute(() -> {
                if (!current(id, generation)) {
                    prepared.close();
                    result.completeExceptionally(cancelled());
                    return;
                }
                ResourceLocation texture = texture(id);
                try {
                    DynamicTexture dynamic = new DynamicTexture(() -> "CaeDuex texture", prepared);
                    dynamic.setFilter(resolution > 64, false);
                    MC.getTextureManager().register(texture, dynamic);
                    result.complete(new Skin(texture, slim));
                } catch (RuntimeException failure) {
                    prepared.close();
                    result.completeExceptionally(new IllegalStateException("皮肤纹理上传失败。", failure));
                }
            });
            nativeImage = null;
        } catch (Exception failure) {
            result.completeExceptionally(new IllegalStateException("皮肤导入失败，请检查图片尺寸与文件权限。", failure));
        } finally {
            if (nativeImage != null) nativeImage.close();
            if (square != null && square != image) square.flush();
            image.flush();
        }
    }

    private static void save(UUID id, long generation, BufferedImage image, Path target) throws java.io.IOException {
        synchronized (com.cheatprogram.core.Config.persistenceLock()) {
            if (!com.cheatprogram.core.Config.persistenceAllowed()) throw new IllegalStateException("Restart required after initialization");
            Files.createDirectories(target.getParent());
            Path pending = target.resolveSibling(target.getFileName() + ".tmp");
            try {
                if (!ImageIO.write(image, "PNG", pending.toFile())) throw new java.io.IOException();
                if (!current(id, generation)) throw cancelled();
                Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(pending);
            }
        }
    }

    static void remove(UUID id, Path target) {
        nextGeneration(id);
        MC.getTextureManager().release(texture(id));
        FILES.execute(() -> {
            synchronized (com.cheatprogram.core.Config.persistenceLock()) {
                if (!com.cheatprogram.core.Config.persistenceAllowed()) return;
                try {
                    Files.deleteIfExists(target);
                } catch (java.io.IOException ignored) { }
            }
        });
    }

    private static ResourceLocation texture(UUID id) {
        return ResourceLocation.fromNamespaceAndPath("cheatprogram", "dynamic/ai_skin/" + id);
    }

    private static long nextGeneration(UUID id) {
        return GENERATIONS.computeIfAbsent(id, ignored -> new AtomicLong()).incrementAndGet();
    }

    private static boolean current(UUID id, long generation) {
        return GENERATIONS.get(id).get() == generation;
    }

    private static IllegalStateException cancelled() {
        return new IllegalStateException("皮肤导入已取消。");
    }
}
