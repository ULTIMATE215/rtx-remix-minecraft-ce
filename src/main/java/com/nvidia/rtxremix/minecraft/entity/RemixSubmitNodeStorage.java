/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.SubmitNodeStorage;

/** Capture queue whose item boundaries can flush while hand and holder context is still live. */
public final class RemixSubmitNodeStorage extends SubmitNodeStorage {
    private final RemixCommandCapture capture;

    RemixSubmitNodeStorage(RemixCommandCapture capture) {
        this.capture = capture;
    }

    public void flush() {
        capture.flush();
    }

    public void emitHeldEffects(ItemCaptureState item, PoseStack matrices, float displayScale) {
        capture.flush();
        capture.emitHeldEffects(item, matrices, displayScale);
    }
}
