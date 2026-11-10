/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.nvidia.rtxremix.minecraft.RtxRemixMod;
import com.nvidia.rtxremix.minecraft.terrain.visibility.ChunkStorageTorus;
import net.minecraft.client.RotatingSectionStorage;
import net.minecraft.core.SectionPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Repositions only entering planes, for both render slots and their dirty-state slots. */
@Mixin(RotatingSectionStorage.class)
public abstract class RotatingSectionStorageMixin<T extends RotatingSectionStorage.Value>
        implements ChunkStorageTorus.StorageAccess {
    @Shadow @Final private RotatingSectionStorage.Node<T>[] nodes;
    @Shadow @Final private int sectionGridSizeXZ;
    @Shadow @Final private int sectionGridSizeY;
    @Shadow @Final private int minY;
    @Shadow private SectionPos centerSectionPos;

    @Unique private final ChunkStorageTorus.Workspace rtxremix$torus = new ChunkStorageTorus.Workspace();

    @Inject(method = "repositionCenter", at = @At("HEAD"), cancellable = true)
    private void rtxremix$reposition(SectionPos center, CallbackInfoReturnable<Boolean> cir) {
        if (!RtxRemixMod.isRtxEnabled()) return;
        if (center.equals(centerSectionPos)) {
            cir.setReturnValue(false);
            return;
        }
        ChunkStorageTorus.reposition(this, rtxremix$torus, sectionGridSizeXZ, sectionGridSizeY,
                sectionGridSizeXZ, minY, center.x(), center.z());
        centerSectionPos = center;
        cir.setReturnValue(true);
    }

    @Override
    public int rtxremix$getOriginX(int slotIndex) {
        return SectionPos.x(nodes[slotIndex].value().getSectionNode());
    }

    @Override
    public int rtxremix$getOriginZ(int slotIndex) {
        return SectionPos.z(nodes[slotIndex].value().getSectionNode());
    }

    @Override
    public void rtxremix$setOrigin(int slotIndex, int x, int y, int z) {
        nodes[slotIndex].value().setSectionNode(SectionPos.asLong(x, y, z));
    }
}
