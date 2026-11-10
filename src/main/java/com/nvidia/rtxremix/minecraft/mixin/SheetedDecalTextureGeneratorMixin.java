/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.mixin;

import com.mojang.blaze3d.vertex.SheetedDecalTextureGenerator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.nvidia.rtxremix.minecraft.entity.RemixFeatureCapture;
import net.minecraft.core.Direction;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionfc;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Capture closes vertices at their normal, so projected UVs must arrive first. */
@Mixin(SheetedDecalTextureGenerator.class)
public abstract class SheetedDecalTextureGeneratorMixin {
    @Shadow @Final private VertexConsumer delegate;
    @Shadow @Final private Matrix4f cameraInversePose;
    @Shadow @Final private Matrix3f normalInversePose;
    @Shadow @Final private float textureScale;
    @Shadow @Final private Vector3f worldPos;
    @Shadow @Final private Vector3f normal;
    @Shadow private float x;
    @Shadow private float y;
    @Shadow private float z;
    @Unique private static final Quaternionfc[] rtxremix$faceRotations = new Quaternionfc[6];

    static {
        for (Direction direction : Direction.values()) {
            rtxremix$faceRotations[direction.ordinal()] = direction.getRotation();
        }
    }

    @Inject(method = "setNormal", at = @At("HEAD"), cancellable = true)
    private void rtxremix$projectBeforeNormal(float nx, float ny, float nz,
                                            CallbackInfoReturnable<VertexConsumer> cir) {
        if (!RemixFeatureCapture.isCapturing()) return;
        normalInversePose.transform(nx, ny, nz, normal);
        Direction face = Direction.getApproximateNearest(normal.x, normal.y, normal.z);
        cameraInversePose.transformPosition(x, y, z, worldPos)
                .rotateY((float) Math.PI).rotateX((float) (-Math.PI / 2))
                .rotate(rtxremix$faceRotations[face.ordinal()]);
        delegate.setUv(-worldPos.x * textureScale, -worldPos.y * textureScale);
        delegate.setNormal(nx, ny, nz);
        cir.setReturnValue((VertexConsumer) this);
    }
}
