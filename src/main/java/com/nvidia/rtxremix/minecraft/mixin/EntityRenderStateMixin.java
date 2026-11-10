/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.entity.EntityCaptureStateAccess;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(EntityRenderState.class)
public abstract class EntityRenderStateMixin implements EntityCaptureStateAccess {
    @Unique private Entity rtxremix$owner;
    @Unique private float rtxremix$partialTick;

    @Override public Entity rtxremix$owner() { return rtxremix$owner; }
    @Override public float rtxremix$partialTick() { return rtxremix$partialTick; }

    @Override
    public void rtxremix$setOwner(Entity entity, float partialTick) {
        rtxremix$owner = entity;
        rtxremix$partialTick = partialTick;
    }
}
