/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.weather;

import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.vulkan.glsl.GlslCompiler;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class WeatherShaderTest {
    @ParameterizedTest
    @CsvSource({"rain_refraction,vsh,VERTEX", "rain_refraction,fsh,FRAGMENT",
            "snow_overlay,vsh,VERTEX", "snow_overlay,fsh,FRAGMENT"})
    void minecraftCompilesWeatherToSpirvWithTheSharedUniformBlock(String name, String extension, ShaderType type)
            throws Exception {
        String source = resource("core/" + name + "." + extension);
        GlslPreprocessor preprocessor = new GlslPreprocessor() {
            @Override
            public String applyImport(boolean inline, String path) {
                assertEquals("rtx-remix-renderer:weather_parameters.glsl", path);
                return resource("include/weather_parameters.glsl");
            }
        };
        try (GlslCompiler compiler = new GlslCompiler();
             var module = compiler.createIntermediary(name, String.join("", preprocessor.process(source)), type)) {
            assertTrue(module.spirv().remaining() > 20);
            assertEquals(1, module.uniformBuffers().size());
        }
    }

    private static String resource(String path) {
        try (var stream = WeatherShaderTest.class.getResourceAsStream("/assets/rtx-remix-renderer/shaders/" + path)) {
            if (stream == null) throw new IllegalArgumentException("Missing weather shader " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }
}
