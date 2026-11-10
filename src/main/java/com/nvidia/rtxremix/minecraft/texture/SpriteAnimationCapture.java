/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import com.mojang.blaze3d.platform.NativeImage;
import com.nvidia.rtxremix.minecraft.mixin.SpriteContentsAccessor;
import com.nvidia.rtxremix.minecraft.util.DirectBufferPool;
import java.nio.ByteBuffer;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryUtil;

/** Mirrors one vanilla animation state without reading its GPU texture back. */
public final class SpriteAnimationCapture implements AutoCloseable {
    private final Identifier id;
    private final Identifier atlasId;
    private final SpriteContents.AnimatedTexture animation;
    private final ByteBuffer source;
    private final int imageWidth, width, height, x, y;
    private ByteBuffer interpolated;
    private long submittedGeneration = Long.MIN_VALUE;

    public SpriteAnimationCapture(TextureAtlasSprite sprite, int x, int y) {
        SpriteContents contents = sprite.contents();
        NativeImage image = ((SpriteContentsAccessor) contents).getImage();
        id = contents.name();
        atlasId = sprite.atlasLocation();
        animation = ((SpriteContentsAccessor) contents).getAnimation();
        source = image.getPixelBytes();
        imageWidth = image.getWidth();
        width = contents.width();
        height = contents.height();
        this.x = x;
        this.y = y;
    }

    /** Called after vanilla advances its frame, on the render thread. */
    public void queue(int sequence, int subFrame, boolean dirty) {
        if (!AtlasAnimationForwarder.accepts(id)) return;
        long generation = AtlasAnimationForwarder.generation();
        if (!dirty && submittedGeneration == generation) return;
        SpriteContents.FrameInfo current = animation.frames.get(sequence);
        SpriteContents.FrameInfo next = animation.frames.get((sequence + 1) % animation.frames.size());
        int columns = imageWidth / width;
        int srcX = current.index() % columns * width;
        int srcY = current.index() / columns * height;
        int nextX = next.index() % columns * width;
        int nextY = next.index() / columns * height;
        float blend = animation.interpolateFrames ? SpriteFramePixels.progress(subFrame, current.time()) : 0f;
        long pixels = MemoryUtil.memAddress(source) + ((long) srcY * imageWidth + srcX) * 4;
        int rowPitch = imageWidth * 4;
        if (blend != 0f && current.index() != next.index()) {
            if (interpolated == null) interpolated = DirectBufferPool.acquire(width * height * 4);
            SpriteFramePixels.blend(source, imageWidth, width, height, srcX, srcY,
                    nextX, nextY, blend, interpolated);
            pixels = MemoryUtil.memAddress(interpolated);
            rowPitch = width * 4;
        }
        AtlasAnimationForwarder.addPatch(id, x, y, srcX, srcY, width, height,
                nextX, nextY, blend, pixels, rowPitch);
        submittedGeneration = generation;
    }

    @Override
    public void close() {
        AtlasAnimationForwarder.discard(atlasId, id);
        if (interpolated != null) {
            DirectBufferPool.release(interpolated);
            interpolated = null;
        }
    }
}
