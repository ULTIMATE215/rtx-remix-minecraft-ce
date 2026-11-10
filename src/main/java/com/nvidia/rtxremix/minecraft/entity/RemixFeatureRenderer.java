/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import java.util.List;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.submit.SubmitNode;

/** CPU geometry generation exposed by the render-type feature renderer mixin. */
public interface RemixFeatureRenderer {
    void rtxremix$buildGroup(FeatureFrameContext context, List<SubmitNode> submits);
}
