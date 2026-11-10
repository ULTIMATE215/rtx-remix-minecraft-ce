/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.nvidia.rtxremix.minecraft.entity.EntityRenderContext;
import com.nvidia.rtxremix.minecraft.entity.ItemCaptureState;
import com.nvidia.rtxremix.minecraft.entity.ItemCaptureStateAccess;
import com.nvidia.rtxremix.minecraft.entity.RemixSubmitNodeStorage;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemStackRenderState.class)
public abstract class ItemStackRenderStateMixin implements ItemCaptureStateAccess {
    @Unique private final ItemCaptureState rtxremix$item = new ItemCaptureState();

    @Override
    public ItemCaptureState rtxremix$itemCaptureState() { return rtxremix$item; }

    @Inject(method = "clear", at = @At("HEAD"))
    private void rtxremix$clearItem(CallbackInfo ci) {
        rtxremix$item.clear();
    }

    @WrapMethod(method = "submit")
    private void rtxremix$captureItem(PoseStack matrices, SubmitNodeCollector queue,
                                    int light, int overlay, int outline, Operation<Void> original) {
        if (!(queue instanceof RemixSubmitNodeStorage capture)) {
            original.call(matrices, queue, light, overlay, outline);
            return;
        }
        capture.flush();
        EntityRenderContext context = EntityRenderContext.get();
        LivingEntity previousHolder = context.getHeldItemHolder();
        InteractionHand previousHand = context.getActiveHand();
        int previousLayer = context.getBlockItemLayer();
        ItemCaptureState previousItem = ItemCaptureState.enter(rtxremix$item);
        context.setHeldItemHolder(rtxremix$item.holder());
        context.setBlockItemLayer(rtxremix$item.blockLayer());
        if (rtxremix$item.mode().firstPerson() && rtxremix$item.holder() != null) {
            boolean mainHandIsLeft = rtxremix$item.holder().getMainArm() == HumanoidArm.LEFT;
            context.setActiveHand(mainHandIsLeft == rtxremix$item.mode().leftHand()
                    ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND);
        }
        try {
            original.call(matrices, queue, light, overlay, outline);
            capture.flush();
        } finally {
            ItemCaptureState.restore(previousItem);
            context.setHeldItemHolder(previousHolder);
            context.setActiveHand(previousHand);
            context.setBlockItemLayer(previousLayer);
        }
    }
}
