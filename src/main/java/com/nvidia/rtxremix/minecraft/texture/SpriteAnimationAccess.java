/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.texture;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/** Binds the atlas placement to vanilla's otherwise placement-independent animation state. */
public interface SpriteAnimationAccess {
    void rtxremix$bindSprite(TextureAtlasSprite sprite, int x, int y);
}
