/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft;

import com.nvidia.rtxremix.RemixAPI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Decides whether RivaTuner Statistics Server's Vulkan layer is kept out of the Remix runtime.
 *
 * <p>RTSS registers {@code VK_LAYER_RTSS} as an implicit layer with no enable switch, so the
 * Vulkan loader puts it into every Vulkan instance created while RTSS runs, including the one
 * Remix creates. With DLSS frame generation on and the game window fullscreen, presents that
 * pass through the layer fault the GPU within seconds: Aftermath records a page fault at
 * address 0, and the runtime then fails with {@code VK_ERROR_DEVICE_LOST} in the frame
 * generation presenter. Windowed play, and fullscreen without frame generation, are not
 * affected. RTSS installs and starts with MSI Afterburner, so players often run it without
 * knowing.
 *
 * <p>The layer's manifest declares {@value #DISABLE_VARIABLE} as its disable switch, and the
 * loader reads it from the process environment when Remix creates its instance, so setting it
 * before the runtime loads keeps the layer out of this process only. The RTSS overlay then no
 * longer draws in the game, while its other hooks and every other process are untouched.
 *
 * <p>{@code -D}{@value #ALLOW_PROPERTY}{@code =true} keeps the layer, for players who accept the
 * risk or do not use frame generation. A value the player already set for
 * {@value #DISABLE_VARIABLE} is always respected.
 */
public final class RtssVulkanLayerPolicy {
    private static final Logger LOGGER = LoggerFactory.getLogger(RtssVulkanLayerPolicy.class);

    /** The disable switch in RTSS's layer manifest, {@code RTSSVkLayer64.json}. */
    public static final String DISABLE_VARIABLE = "DISABLE_RTSS_LAYER";
    static final String DISABLE_VALUE = "1";
    public static final String ALLOW_PROPERTY = "rtxremix.allowRtssVulkanLayer";

    private RtssVulkanLayerPolicy() {}

    /** Applies the process-only policy before either Minecraft or Remix creates a Vulkan instance. */
    public static void applyBeforeInstanceCreation(String dllPath) {
        String existing = System.getenv(RtssVulkanLayerPolicy.DISABLE_VARIABLE);
        if (!RtssVulkanLayerPolicy.shouldDisable(
                System.getProperty(RtssVulkanLayerPolicy.ALLOW_PROPERTY), existing)) {
            LOGGER.info("RTSS Vulkan layer left to the loader ({}={}, -D{}={})",
                    RtssVulkanLayerPolicy.DISABLE_VARIABLE, existing,
                    RtssVulkanLayerPolicy.ALLOW_PROPERTY,
                    System.getProperty(RtssVulkanLayerPolicy.ALLOW_PROPERTY));
            return;
        }
        try {
            RemixAPI.setProcessEnvironmentVariable(dllPath,
                    RtssVulkanLayerPolicy.DISABLE_VARIABLE, RtssVulkanLayerPolicy.DISABLE_VALUE);
            LOGGER.info("RTSS Vulkan layer disabled for Minecraft and Remix: with DLSS frame generation"
                    + " in fullscreen it faults the GPU (-D{}=true keeps it)",
                    RtssVulkanLayerPolicy.ALLOW_PROPERTY);
        } catch (RuntimeException e) {
            LOGGER.warn("Could not disable the RTSS Vulkan layer; if RTSS runs, DLSS frame generation"
                    + " in fullscreen may lose the GPU device", e);
        }
    }



    /**
     * Whether to set {@value #DISABLE_VARIABLE} before the runtime loads.
     *
     * @param allowProperty the value of {@value #ALLOW_PROPERTY}, or null when unset
     * @param existingValue the value {@value #DISABLE_VARIABLE} already has, or null when unset
     */
    public static boolean shouldDisable(String allowProperty, String existingValue) {
        if (existingValue != null) {
            return false;
        }
        return !Boolean.parseBoolean(allowProperty);
    }
}
