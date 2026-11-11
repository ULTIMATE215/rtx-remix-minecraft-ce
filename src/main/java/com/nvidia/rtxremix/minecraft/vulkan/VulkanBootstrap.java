/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.vulkan;

import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.minecraft.NativeExtractor;
import com.nvidia.rtxremix.minecraft.RtssVulkanLayerPolicy;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Enables the bundled export-description layer before either Vulkan device exists. */
public final class VulkanBootstrap implements PreLaunchEntrypoint {
    private static final Logger LOGGER = LoggerFactory.getLogger(VulkanBootstrap.class);
    private static final String LAYER = "VK_LAYER_RTXREMIX_export_info";

    @Override
    public void onPreLaunch() {
        if (FabricLoader.getInstance().getEnvironmentType() != EnvType.CLIENT
                || !System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            return;
        }
        NativeExtractor.extractIfNeeded();
        Path directory = FabricLoader.getInstance().getGameDir().resolve("remix-sdk").toAbsolutePath();
        if (!Files.isRegularFile(directory.resolve("remix-vulkan-layer.json"))) {
            throw new IllegalStateException("Missing Vulkan export layer manifest in " + directory
                    + "; install the complete mod JAR or run setupRuntime");
        }
        String dll = directory.resolve("d3d9.dll").toString();
        RtssVulkanLayerPolicy.applyBeforeInstanceCreation(dll);
        // VK_LAYER_PATH overrides normal explicit-layer discovery. Use the
        // additive form unless the player has already selected an override.
        String override = System.getenv("VK_LAYER_PATH");
        String pathKey = override == null ? "VK_ADD_LAYER_PATH" : "VK_LAYER_PATH";
        String oldPath = override == null ? System.getenv(pathKey) : override;
        RemixAPI.setProcessEnvironmentVariable(dll, pathKey, prepend(directory.toString(), oldPath));
        String oldLayers = System.getenv("VK_INSTANCE_LAYERS");
        RemixAPI.setProcessEnvironmentVariable(dll, "VK_INSTANCE_LAYERS", prepend(LAYER, oldLayers));
        LOGGER.info("Enabled bundled Vulkan export descriptions for this game process");
    }

    static String prepend(String entry, String previous) {
        if (previous == null || previous.isBlank()) return entry;
        for (String existing : previous.split(";")) {
            if (entry.equals(existing)) return previous;
        }
        return entry + ";" + previous;
    }
}
