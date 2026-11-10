/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Modified by RTX Remix for Minecraft Community Edition; see COMMUNITY.md.
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import net.fabricmc.fabric.api.client.renderer.v1.mesh.QuadEmitter;
import net.fabricmc.fabric.api.client.renderer.v1.model.FabricBlockStateModel;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.block.dispatch.multipart.MultiPartModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.function.Predicate;

/** Avoids an iterator per block while preserving each selected model's shared random seed. */
@Mixin(value = MultiPartModel.class, priority = 900)
public abstract class MultipartBakedModelMixin {
    @Shadow @Final private MultiPartModel.SharedBakedState shared;
    @Shadow @Final private BlockState blockState;
    @Shadow private List<BlockStateModel> models;

    private List<BlockStateModel> rtxremix$models() {
        if (models == null) models = shared.selectModels(blockState);
        return models;
    }

    @Inject(method = "collectParts", at = @At("HEAD"), cancellable = true)
    private void rtxremix$collect(RandomSource random, List<BlockStateModelPart> output, CallbackInfo ci) {
        if (!RtxRemixMod.isRtxEnabled()) return;
        List<BlockStateModel> selected = rtxremix$models();
        long seed = random.nextLong();
        for (int i = 0; i < selected.size(); i++) {
            random.setSeed(seed);
            selected.get(i).collectParts(random, output);
        }
        ci.cancel();
    }

    // Fabric adds these methods at priority 1000. Both the vanilla part path and the
    // Fabric emitter path must preserve multipart randomisation without allocating iterators.
    @Inject(method = "emitQuads", at = @At("HEAD"), cancellable = true, remap = false)
    private void rtxremix$emit(QuadEmitter emitter, BlockAndTintGetter world, BlockPos pos,
                              BlockState state, RandomSource random, Predicate<Direction> cullTest, CallbackInfo ci) {
        if (!RtxRemixMod.isRtxEnabled()) return;
        List<BlockStateModel> selected = rtxremix$models();
        long seed = random.nextLong();
        for (int i = 0; i < selected.size(); i++) {
            random.setSeed(seed);
            ((FabricBlockStateModel) selected.get(i)).emitQuads(emitter, world, pos, state, random, cullTest);
        }
        ci.cancel();
    }

    @Inject(method = "materialFlags(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;"
            + "Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/util/RandomSource;)I",
            at = @At("HEAD"), cancellable = true, remap = false)
    private void rtxremix$flags(BlockAndTintGetter world, BlockPos pos, BlockState state, RandomSource random,
                               CallbackInfoReturnable<Integer> cir) {
        if (!RtxRemixMod.isRtxEnabled()) return;
        List<BlockStateModel> selected = rtxremix$models();
        long seed = random.nextLong();
        int flags = 0;
        for (int i = 0; i < selected.size(); i++) {
            random.setSeed(seed);
            flags |= ((FabricBlockStateModel) selected.get(i)).materialFlags(world, pos, state, random);
        }
        cir.setReturnValue(flags);
    }
}
