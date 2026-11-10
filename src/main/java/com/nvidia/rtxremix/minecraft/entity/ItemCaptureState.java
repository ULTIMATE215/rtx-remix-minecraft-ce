/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.nvidia.rtxremix.minecraft.terrain.capture.BlockQuadEmitter;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

/** Item and holder retained with the model state until its commands are captured. */
public final class ItemCaptureState {
    private static ItemCaptureState current;
    private ItemStack stack = ItemStack.EMPTY;
    private LivingEntity holder;
    private ItemDisplayContext mode = ItemDisplayContext.NONE;
    private boolean effectsEmitted;

    public void set(ItemStack stack, ItemDisplayContext mode, ItemOwner owner) {
        this.stack = stack;
        this.mode = mode;
        this.holder = owner instanceof LivingEntity living ? living : null;
    }

    public void clear() {
        stack = ItemStack.EMPTY;
        mode = ItemDisplayContext.NONE;
        holder = null;
    }

    public ItemStack stack() { return stack; }
    public LivingEntity holder() { return holder; }
    public ItemDisplayContext mode() { return mode; }

    public int blockLayer() {
        return stack.getItem() instanceof BlockItem blockItem
                ? BlockTextureManager.layerType(BlockQuadEmitter.classifyBlockLayer(blockItem.getBlock().defaultBlockState()))
                : EntityRenderContext.NO_BLOCK_ITEM;
    }

    public static ItemCaptureState current() { return current; }

    public static ItemCaptureState enter(ItemCaptureState item) {
        ItemCaptureState previous = current;
        if (previous != item) item.effectsEmitted = false;
        current = item;
        return previous;
    }

    public static void restore(ItemCaptureState previous) { current = previous; }

    /** A layered item emits one flame/smoke set at its first layer's transformed pose. */
    public boolean claimEffects() {
        if (effectsEmitted || !HeldItemFlame.isHandMode(mode)) return false;
        effectsEmitted = true;
        return true;
    }
}
