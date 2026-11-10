/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import net.minecraft.world.entity.Entity;

/** The live owner of an extracted state, used only by render-thread capture. */
public interface EntityCaptureStateAccess {
    Entity rtxremix$owner();
    float rtxremix$partialTick();
    void rtxremix$setOwner(Entity entity, float partialTick);
}
