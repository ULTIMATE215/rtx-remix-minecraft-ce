/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Modified by RTX Remix for Minecraft Community Edition; see COMMUNITY.md.
 */
package com.nvidia.rtxremix.minecraft.texture;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.Identifier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtlasAnimationForwarderTest {

    @AfterEach
    void clearForwarder() {
        AtlasAnimationForwarder.clear();
    }

    @Test
    void addPatchIgnoresSpritesOutsideTheBlockAtlasAllowList() throws Exception {
        Identifier blockSprite = Identifier.fromNamespaceAndPath("minecraft", "block/lava_still");
        Identifier guiSprite = Identifier.fromNamespaceAndPath("minecraft", "gui/sprites/icon");

        AtlasAnimationForwarder.init(Map.of(), Set.of(blockSprite));
        AtlasAnimationForwarder.beginAtlasUpload(BlockTextureManager.BLOCK_ATLAS_ID);

        try {
            AtlasAnimationForwarder.addPatch(guiSprite, 1, 2, 0, 0, 4, 4, 0, 0, 0f, 0L, 16);
            assertEquals(0, pendingPatchCount());

            AtlasAnimationForwarder.addPatch(blockSprite, 1, 2, 0, 0, 4, 4, 0, 0, 0f, 0L, 16);
            assertEquals(1, pendingPatchCount());
        } finally {
            AtlasAnimationForwarder.endAtlasUpload();
        }
    }

    @Test
    void addPatchIgnoresAllowedSpriteIdsFromOtherAtlases() throws Exception {
        Identifier blockSprite = Identifier.fromNamespaceAndPath("minecraft", "block/lava_still");
        Identifier particleAtlas = Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/particles.png");

        AtlasAnimationForwarder.init(Map.of(), Set.of(blockSprite));
        AtlasAnimationForwarder.beginAtlasUpload(particleAtlas);

        try {
            AtlasAnimationForwarder.addPatch(blockSprite, 1, 2, 0, 0, 4, 4, 0, 0, 0f, 0L, 16);
            assertEquals(0, pendingPatchCount());
        } finally {
            AtlasAnimationForwarder.endAtlasUpload();
        }
    }

    private static int pendingPatchCount() throws Exception {
        Field pendingPatches = AtlasAnimationForwarder.class.getDeclaredField("pendingPatches");
        pendingPatches.setAccessible(true);
        return ((List<?>) pendingPatches.get(null)).size();
    }

    @Test
    void multipleTicksBeforeAFrameKeepOnlyTheLatestSampling() throws Exception {
        Identifier sprite = Identifier.withDefaultNamespace("block/water_still");
        AtlasAnimationForwarder.init(Map.of(), Set.of(sprite));
        AtlasAnimationForwarder.beginAtlasUpload(BlockTextureManager.BLOCK_ATLAS_ID);
        try {
            AtlasAnimationForwarder.addPatch(sprite, 16, 32, 0, 0, 16, 16, 0, 16, 0.25f, 1L, 64);
            AtlasAnimationForwarder.addPatch(sprite, 16, 32, 0, 16, 16, 16, 0, 32, 0.5f, 2L, 64);
            assertEquals(1, pendingPatchCount());
            Field pending = AtlasAnimationForwarder.class.getDeclaredField("pendingPatches");
            pending.setAccessible(true);
            Object record = ((List<?>) pending.get(null)).getFirst();
            Field sourceY = record.getClass().getDeclaredField("srcY");
            Field pointer = record.getClass().getDeclaredField("pixelPtr");
            sourceY.setAccessible(true);
            pointer.setAccessible(true);
            assertEquals(16, sourceY.getInt(record));
            assertEquals(2L, pointer.getLong(record));
            AtlasAnimationForwarder.discard(BlockTextureManager.BLOCK_ATLAS_ID, sprite);
            assertEquals(0, pendingPatchCount());
        } finally {
            AtlasAnimationForwarder.endAtlasUpload();
        }
    }

    @Test
    void theSameSpriteInDifferentAtlasesHasIndependentPendingPixels() throws Exception {
        Identifier sprite = Identifier.withDefaultNamespace("block/water_still");
        Identifier items = Identifier.withDefaultNamespace("textures/atlas/items.png");
        AtlasAnimationForwarder.init(Map.of(), Set.of(sprite));
        try (var target = AtlasAnimationForwarder.register(items, Set.of(sprite), Map.of(), 7L, 0, 0, 0, 0)) {
            queuePatch(BlockTextureManager.BLOCK_ATLAS_ID, sprite);
            queuePatch(items, sprite);
            assertEquals(2, pendingPatchCount());
            AtlasAnimationForwarder.discard(BlockTextureManager.BLOCK_ATLAS_ID, sprite);
            assertEquals(1, pendingPatchCount());
            AtlasAnimationForwarder.discard(items, sprite);
            assertEquals(0, pendingPatchCount());
        }
    }

    @Test
    void closingOneAtlasCopyKeepsOtherCopiesSubscribedUntilTheLastClose() throws Exception {
        Identifier sprite = Identifier.withDefaultNamespace("item/clock_00");
        Identifier items = Identifier.withDefaultNamespace("textures/atlas/items.png");
        AtlasAnimationForwarder.init(Map.of(), Set.of());
        var first = AtlasAnimationForwarder.register(items, Set.of(sprite), Map.of(), 7L, 0, 0, 0, 0);
        var second = AtlasAnimationForwarder.register(items, Set.of(sprite), Map.of(), 8L, 0, 0, 0, 0);
        try {
            queuePatch(items, sprite);
            first.close();
            assertEquals(1, pendingPatchCount());
            AtlasAnimationForwarder.beginAtlasUpload(items);
            assertTrue(AtlasAnimationForwarder.accepts(sprite));
            second.close();
            assertEquals(0, pendingPatchCount());
            assertFalse(AtlasAnimationForwarder.accepts(sprite));
        } finally {
            AtlasAnimationForwarder.endAtlasUpload();
            first.close();
            second.close();
        }
    }

    @Test
    void pauseDropsPointersAndResumeResynchronizesEveryAtlas() throws Exception {
        Identifier sprite = Identifier.withDefaultNamespace("particle/flame");
        Identifier particles = Identifier.withDefaultNamespace("textures/atlas/particles.png");
        AtlasAnimationForwarder.init(Map.of(), Set.of());
        try (var target = AtlasAnimationForwarder.register(particles, Set.of(sprite), Map.of(), 7L, 0, 0, 0, 0)) {
            queuePatch(particles, sprite);
            long generation = AtlasAnimationForwarder.generation();
            AtlasAnimationForwarder.pause();
            queuePatch(particles, sprite);
            assertEquals(0, pendingPatchCount());
            AtlasAnimationForwarder.resume();
            assertTrue(AtlasAnimationForwarder.generation() > generation);
            queuePatch(particles, sprite);
            assertEquals(1, pendingPatchCount());
        }
        assertEquals(0, pendingPatchCount());
    }

    private static void queuePatch(Identifier atlas, Identifier sprite) {
        AtlasAnimationForwarder.beginAtlasUpload(atlas);
        try {
            AtlasAnimationForwarder.addPatch(sprite, 0, 0, 0, 0, 4, 4, 0, 0, 0f, 1L, 16);
        } finally {
            AtlasAnimationForwarder.endAtlasUpload();
        }
    }
}
