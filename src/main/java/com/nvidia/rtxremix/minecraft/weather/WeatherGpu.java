/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.weather;

import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.BlendFactor;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.nvidia.rtxremix.minecraft.mixin.RenderPipelinesAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.util.Optional;

/** Vulkan weather passes; fragment depth is compared with Remix without modifying vanilla's depth. */
final class WeatherGpu {
    private static final Identifier RAIN = Identifier.withDefaultNamespace("textures/environment/rain.png");
    private static final Identifier SNOW = Identifier.withDefaultNamespace("textures/environment/snow.png");
    private static final int PARAMETERS_BYTES = (2 * 16 + 3 * 4) * Float.BYTES;
    private static final BindGroupLayout BINDINGS = BindGroupLayout.builder()
            .withUniform("WeatherParameters", UniformType.UNIFORM_BUFFER)
            .withSampler("Sampler0").withSampler("Sampler1").withSampler("Sampler2")
            .withSampler("RemixDepth").build();
    private static final RenderPipeline RAIN_PIPELINE = pipeline("rain_refraction");
    private static final RenderPipeline SNOW_PIPELINE = pipeline("snow_overlay");

    private static RenderPipeline pipeline(String name) {
        Identifier shader = Identifier.fromNamespaceAndPath("rtx-remix-renderer", "core/" + name);
        return RenderPipeline.builder()
                .withLocation(Identifier.fromNamespaceAndPath("rtx-remix-renderer", "pipeline/" + name))
                .withVertexShader(shader).withFragmentShader(shader).withBindGroupLayout(BINDINGS)
                .withVertexBinding(0, DefaultVertexFormat.PARTICLE).withPrimitiveTopology(PrimitiveTopology.QUADS)
                .withColorTargetState(new ColorTargetState(new BlendFunction(
                        BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA, BlendFactor.ONE, BlendFactor.ZERO)))
                .withDepthStencilState(Optional.empty()).withCull(false).build();
    }

    static void registerShaders() {
        RenderPipelinesAccessor.rtxremix$register(RAIN_PIPELINE);
        RenderPipelinesAccessor.rtxremix$register(SNOW_PIPELINE);
    }

    static void render(RenderTarget target, ByteBuffer vertices, int rainQuads, int snowQuads,
                       GpuTextureView lightmap, Matrix4f projection, Matrix4f modelView, FogData fog,
                       GpuTextureView colour, GpuTextureView remixDepth) {
        CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
        GpuBufferSlice parameters;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            parameters = encoder.transientMemory().uploadGpu(Std140Builder.onStack(stack, PARAMETERS_BYTES)
                            .putMat4f(modelView).putMat4f(projection)
                            .putVec4(fog.color.x, fog.color.y, fog.color.z, fog.color.w)
                            .putVec4(fog.environmentalStart, fog.environmentalEnd, fog.renderDistanceStart, fog.renderDistanceEnd)
                            .putVec4(target.width, target.height, 0, 0).get(),
                    RenderSystem.getDevice().getDeviceInfo().limits().minUniformOffsetAlignment(), GpuBuffer.USAGE_UNIFORM);
        }
        GpuBufferSlice upload = encoder.transientMemory().uploadGpu(vertices, 4, GpuBuffer.USAGE_VERTEX);
        var sequential = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
        GpuBuffer indices = sequential.getBuffer((rainQuads + snowQuads) * 6);
        // Rain reads the unmodified Remix color snapshot; snow is painted over the rain.
        draw(encoder, target, upload, indices, sequential.type(), parameters, lightmap,
                colour, remixDepth, RAIN_PIPELINE, RAIN, 0, rainQuads);
        draw(encoder, target, upload, indices, sequential.type(), parameters, lightmap,
                colour, remixDepth, SNOW_PIPELINE, SNOW, rainQuads * 6, snowQuads);
    }

    private static void draw(CommandEncoder encoder, RenderTarget target, GpuBufferSlice vertices,
                             GpuBuffer indices, com.mojang.blaze3d.IndexType indexType,
                             GpuBufferSlice parameters, GpuTextureView lightmap, GpuTextureView colour,
                             GpuTextureView depth, RenderPipeline pipeline, Identifier textureId,
                             int firstIndex, int quads) {
        if (quads == 0) return;
        var texture = Minecraft.getInstance().getTextureManager().getTexture(textureId);
        try (RenderPass pass = encoder.createRenderPass(() -> "Remix weather", target.getColorTextureView(), Optional.empty())) {
            pass.setPipeline(pipeline);
            pass.setUniform("WeatherParameters", parameters);
            pass.bindTexture("Sampler0", texture.getTextureView(), texture.getSampler());
            pass.bindTexture("Sampler1", colour, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            pass.bindTexture("Sampler2", lightmap, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            pass.bindTexture("RemixDepth", depth, RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST));
            pass.setVertexBuffer(0, vertices);
            pass.setIndexBuffer(indices, indexType);
            pass.drawIndexed(quads * 6, 1, firstIndex, 0, 0);
        }
    }
}
