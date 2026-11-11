/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VulkanBootstrapTest {
    @Test
    void preservesThePlayersExplicitLayersAndSearchPaths() {
        assertEquals("remix;validation;overlay", VulkanBootstrap.prepend("remix", "validation;overlay"));
        assertEquals("validation;remix;overlay", VulkanBootstrap.prepend("remix", "validation;remix;overlay"));
        assertEquals("D:\\game\\remix-sdk;C:\\Vulkan\\layers",
                VulkanBootstrap.prepend("D:\\game\\remix-sdk", "C:\\Vulkan\\layers"));
        assertEquals("remix", VulkanBootstrap.prepend("remix", null));
        assertEquals("remix", VulkanBootstrap.prepend("remix", ""));
    }
}
