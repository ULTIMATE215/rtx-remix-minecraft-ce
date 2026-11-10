/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.terrain.capture;

import net.fabricmc.fabric.api.client.renderer.v1.Renderer;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadView;
import net.fabricmc.fabric.api.client.renderer.v1.model.FabricBlockStateModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SingleThreadedRandomSource;

import java.util.IdentityHashMap;

/** Render-thread cache of default block-item layers, invalidated by a model-set reload. */
final class BlockLayerClassifier {
    private static final BlockLayerClassifier INSTANCE = new BlockLayerClassifier();
    private final IdentityHashMap<BlockState, String> layers = new IdentityHashMap<>();
    private final SingleThreadedRandomSource random = new SingleThreadedRandomSource(42);
    private BlockStateModelSet modelSet;
    private QuadEmitter emitter;
    private ChunkSectionLayer layer;

    static String classify(BlockState state) {
        return INSTANCE.layerOf(state);
    }

    private String layerOf(BlockState state) {
        var models = Minecraft.getInstance().getModelManager().getBlockStateModelSet();
        if (modelSet != models) {
            modelSet = models;
            layers.clear();
            emitter = null;
        }
        String cached = layers.get(state);
        if (cached != null) return cached;
        layer = ChunkSectionLayer.SOLID;
        if (emitter == null) emitter = Renderer.get().quadEmitter(this::accept);
        emitter.clear();
        random.setSeed(42);
        try {
            ((FabricBlockStateModel) models.get(state)).emitQuads(emitter, BlockAndTintGetter.EMPTY,
                    BlockPos.ZERO, state, random, face -> false);
        } catch (RuntimeException | Error e) {
            emitter = null;
            throw e;
        }
        String name = switch (layer) {
            case SOLID -> "solid";
            case CUTOUT -> "cutout";
            case TRANSLUCENT -> "translucent";
        };
        layers.put(state, name);
        return name;
    }

    private void accept(QuadView quad) {
        if (quad.chunkLayer() == ChunkSectionLayer.TRANSLUCENT
                || quad.chunkLayer() == ChunkSectionLayer.CUTOUT && layer == ChunkSectionLayer.SOLID) {
            layer = quad.chunkLayer();
        }
    }
}
