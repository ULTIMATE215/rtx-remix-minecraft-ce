/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Modified by RTX Remix for Minecraft Community Edition; see COMMUNITY.md.
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Minecraft;

/** Replaces the world color before Minecraft draws its HUD and screen overlays. */
public final class TextureOverlay {
    public void render(GpuTextureView source) {
        if (source == null) return;
        Minecraft client = Minecraft.getInstance();
        GpuTextureView destination = client.gameRenderer.mainRenderTarget().getColorTextureView();
        if (destination == null) return;
        VulkanTextureBlit.copy(RemixVulkanSurface.from(client.windowSurface()).device().createCommandEncoder(),
                source, destination);
    }
}
