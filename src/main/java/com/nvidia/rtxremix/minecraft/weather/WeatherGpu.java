/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.weather;

import com.mojang.blaze3d.opengl.GlSampler;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL33;
import org.lwjgl.opengl.GL40;
import org.lwjgl.opengl.GL45;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/** Direct GL weather pass over an interop image, with private depth and complete state restoration. */
final class WeatherGpu implements AutoCloseable {
    private static final Identifier RAIN = Identifier.withDefaultNamespace("textures/environment/rain.png");
    private static final Identifier SNOW = Identifier.withDefaultNamespace("textures/environment/snow.png");
    private static final String SEED_VERTEX = """
            #version 150
            void main() {
                vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;
    private static final String SEED_FRAGMENT = """
            #version 150
            uniform sampler2D RemixDepth;
            uniform vec2 FramebufferSize;
            void main() {
                vec2 uv = gl_FragCoord.xy / FramebufferSize;
                gl_FragDepth = texture(RemixDepth, vec2(uv.x, 1.0 - uv.y)).r;
            }
            """;
    private final State state = new State();
    private final float[] matrix = new float[16];
    private Program rain;
    private Program snow;
    private int seedProgram, seedDepth, seedSize;
    private int framebuffer, depth, width, height;
    private int vao, vertexBuffer, indexBuffer, vertexCapacity, quadCapacity;
    private int linearSampler, nearestSampler;

    WeatherGpu() {
        try {
            rain = new Program("rain_refraction");
            snow = new Program("snow_overlay");
            seedProgram = link(SEED_VERTEX, SEED_FRAGMENT);
            seedDepth = GL20.glGetUniformLocation(seedProgram, "RemixDepth");
            seedSize = GL20.glGetUniformLocation(seedProgram, "FramebufferSize");
            framebuffer = GL45.glCreateFramebuffers();
            vao = GL45.glCreateVertexArrays();
            vertexBuffer = GL45.glCreateBuffers();
            indexBuffer = GL45.glCreateBuffers();
            VertexFormat format = DefaultVertexFormat.PARTICLE;
            GL45.glVertexArrayVertexBuffer(vao, 0, vertexBuffer, 0, format.getVertexSize());
            attribute(0, 3, GL11.GL_FLOAT, false, format.getElement("Position").offset());
            attribute(1, 2, GL11.GL_FLOAT, false, format.getElement("UV0").offset());
            attribute(2, 4, GL11.GL_UNSIGNED_BYTE, true, format.getElement("Color").offset());
            GL45.glVertexArrayAttribIFormat(vao, 3, 2, GL11.GL_SHORT, format.getElement("UV2").offset());
            GL45.glVertexArrayAttribBinding(vao, 3, 0);
            GL45.glEnableVertexArrayAttrib(vao, 3);
            GL45.glVertexArrayElementBuffer(vao, indexBuffer);
            linearSampler = sampler(GL11.GL_LINEAR);
            nearestSampler = sampler(GL11.GL_NEAREST);
        } catch (RuntimeException | Error e) {
            close();
            throw e;
        }
    }

    private void attribute(int attribute, int count, int type, boolean normalized, int offset) {
        GL45.glVertexArrayAttribFormat(vao, attribute, count, type, normalized, offset);
        GL45.glVertexArrayAttribBinding(vao, attribute, 0);
        GL45.glEnableVertexArrayAttrib(vao, attribute);
    }

    private static int sampler(int filter) {
        int sampler = GL33.glGenSamplers();
        GL33.glSamplerParameteri(sampler, GL11.GL_TEXTURE_MIN_FILTER, filter);
        GL33.glSamplerParameteri(sampler, GL11.GL_TEXTURE_MAG_FILTER, filter);
        GL33.glSamplerParameteri(sampler, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL33.glSamplerParameteri(sampler, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        return sampler;
    }

    void render(RenderTarget target, ByteBuffer vertices, int rainQuads, int snowQuads,
                GpuTextureView lightmap, Matrix4f projection, Matrix4f modelView, FogData fog, int colour, int remixDepth) {
        prepareTarget(target);
        upload(vertices, rainQuads + snowQuads);
        // Fetching a newly requested texture may upload it; snapshot GL state after that upload.
        AbstractTexture rainTexture = Minecraft.getInstance().getTextureManager().getTexture(RAIN);
        AbstractTexture snowTexture = Minecraft.getInstance().getTextureManager().getTexture(SNOW);
        state.save();
        try {
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer);
            GL11.glViewport(0, 0, width, height);
            GL45.glClipControl(GL45.GL_LOWER_LEFT, GL45.GL_NEGATIVE_ONE_TO_ONE);
            GL11.glDepthRange(0.0, 1.0);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, GL11.GL_FILL);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(GL11.GL_ALWAYS);
            GL11.glDepthMask(true);
            GL30.glColorMaski(0, false, false, false, false);
            GL30.glDisablei(GL11.GL_BLEND, 0);
            GL30.glBindVertexArray(vao);
            GL20.glUseProgram(seedProgram);
            GL20.glUniform1i(seedDepth, 0);
            GL20.glUniform2f(seedSize, width, height);
            bindTexture(0, remixDepth, nearestSampler);
            GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);

            GL11.glDepthFunc(GL11.GL_LEQUAL);
            GL11.glDepthMask(false);
            GL30.glColorMaski(0, true, true, true, true);
            GL30.glEnablei(GL11.GL_BLEND, 0);
            GL40.glBlendFuncSeparatei(0, GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ZERO);
            GL40.glBlendEquationSeparatei(0, GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD);
            bindTexture(1, colour, linearSampler);
            bindTexture(2, ((GlTexture) lightmap.texture()).glId(), linearSampler);
            draw(rain, rainTexture, projection, modelView, fog, 0, rainQuads);
            draw(snow, snowTexture, projection, modelView, fog, rainQuads, snowQuads);
        } finally {
            state.restore();
        }
    }

    private void prepareTarget(RenderTarget target) {
        if (depth == 0 || width != target.width || height != target.height) {
            if (depth != 0) GL11.glDeleteTextures(depth);
            width = target.width;
            height = target.height;
            depth = GL45.glCreateTextures(GL11.GL_TEXTURE_2D);
            GL45.glTextureStorage2D(depth, 1, GL30.GL_DEPTH_COMPONENT32F, width, height);
            GL45.glNamedFramebufferTexture(framebuffer, GL30.GL_DEPTH_ATTACHMENT, depth, 0);
        }
        GL45.glNamedFramebufferTexture(framebuffer, GL30.GL_COLOR_ATTACHMENT0,
                ((GlTexture) target.getColorTexture()).glId(), 0);
        if (GL45.glCheckNamedFramebufferStatus(framebuffer, GL30.GL_FRAMEBUFFER) != GL30.GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("Weather framebuffer is incomplete");
        }
    }

    private void upload(ByteBuffer vertices, int quads) {
        if (vertices.remaining() > vertexCapacity) {
            vertexCapacity = vertices.remaining();
            GL45.glNamedBufferData(vertexBuffer, vertexCapacity, GL15.GL_DYNAMIC_DRAW);
        }
        GL45.glNamedBufferSubData(vertexBuffer, 0, vertices);
        if (quads <= quadCapacity) return;
        quadCapacity = quads;
        IntBuffer indices = MemoryUtil.memAllocInt(Math.multiplyExact(quads, 6));
        try {
            for (int q = 0; q < quads; q++) {
                int v = q * 4;
                indices.put(v).put(v + 1).put(v + 2).put(v + 2).put(v + 3).put(v);
            }
            indices.flip();
            GL45.glNamedBufferData(indexBuffer, indices, GL15.GL_STATIC_DRAW);
        } finally {
            MemoryUtil.memFree(indices);
        }
    }

    private void draw(Program program, AbstractTexture texture, Matrix4f projection, Matrix4f view, FogData fog, int first, int count) {
        if (count == 0) return;
        GL20.glUseProgram(program.id);
        GL20.glUniformMatrix4fv(program.projection, false, projection.get(matrix));
        GL20.glUniformMatrix4fv(program.view, false, view.get(matrix));
        GL20.glUniform4f(program.colour, 1, 1, 1, 1);
        GL20.glUniform4f(program.fogColour, fog.color.x, fog.color.y, fog.color.z, fog.color.w);
        GL20.glUniform4f(program.fogRange, fog.environmentalStart, fog.environmentalEnd,
                fog.renderDistanceStart, fog.renderDistanceEnd);
        GL20.glUniform1i(program.sampler0, 0);
        GL20.glUniform1i(program.sampler1, 1);
        GL20.glUniform1i(program.sampler2, 2);
        bindTexture(0, ((GlTexture) texture.getTexture()).glId(), ((GlSampler) texture.getSampler()).getId());
        GL11.glDrawElements(GL11.GL_TRIANGLES, count * 6, GL11.GL_UNSIGNED_INT, (long) first * 6 * Integer.BYTES);
    }

    private static void bindTexture(int unit, int texture, int sampler) {
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
        GL33.glBindSampler(unit, sampler);
    }

    @Override
    public void close() {
        if (rain != null) GL20.glDeleteProgram(rain.id);
        if (snow != null) GL20.glDeleteProgram(snow.id);
        GL20.glDeleteProgram(seedProgram);
        GL30.glDeleteFramebuffers(framebuffer);
        GL11.glDeleteTextures(depth);
        GL30.glDeleteVertexArrays(vao);
        GL15.glDeleteBuffers(vertexBuffer);
        GL15.glDeleteBuffers(indexBuffer);
        GL33.glDeleteSamplers(linearSampler);
        GL33.glDeleteSamplers(nearestSampler);
    }

    private static final class Program {
        final int id, projection, view, colour, fogColour, fogRange, sampler0, sampler1, sampler2;
        Program(String name) {
            id = link(shader(name + ".vsh"), shader(name + ".fsh"));
            projection = GL20.glGetUniformLocation(id, "ProjMat");
            view = GL20.glGetUniformLocation(id, "ModelViewMat");
            colour = GL20.glGetUniformLocation(id, "ColorModulator");
            fogColour = GL20.glGetUniformLocation(id, "FogColor");
            fogRange = GL20.glGetUniformLocation(id, "FogRange");
            sampler0 = GL20.glGetUniformLocation(id, "Sampler0");
            sampler1 = GL20.glGetUniformLocation(id, "Sampler1");
            sampler2 = GL20.glGetUniformLocation(id, "Sampler2");
        }
    }

    private static String shader(String name) {
        Identifier path = Identifier.fromNamespaceAndPath("rtx-remix-renderer", "shaders/core/" + name);
        try (InputStream stream = Minecraft.getInstance().getResourceManager().getResourceOrThrow(path).open()) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load weather shader " + path, e);
        }
    }

    private static int link(String vertex, String fragment) {
        int vs = compile(GL20.GL_VERTEX_SHADER, vertex);
        int fs = 0, program = 0;
        try {
            fs = compile(GL20.GL_FRAGMENT_SHADER, fragment);
            program = GL20.glCreateProgram();
            GL20.glAttachShader(program, vs);
            GL20.glAttachShader(program, fs);
            GL20.glBindAttribLocation(program, 0, "Position");
            GL20.glBindAttribLocation(program, 1, "UV0");
            GL20.glBindAttribLocation(program, 2, "Color");
            GL20.glBindAttribLocation(program, 3, "UV2");
            GL20.glLinkProgram(program);
            if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
                throw new IllegalStateException("Cannot link weather shader: " + GL20.glGetProgramInfoLog(program));
            }
            return program;
        } catch (RuntimeException | Error e) {
            GL20.glDeleteProgram(program);
            throw e;
        } finally {
            GL20.glDeleteShader(vs);
            if (fs != 0) GL20.glDeleteShader(fs);
        }
    }

    private static int compile(int kind, String source) {
        int shader = GL20.glCreateShader(kind);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shader);
            GL20.glDeleteShader(shader);
            throw new IllegalStateException("Cannot compile weather shader: " + log);
        }
        return shader;
    }

    /** Saved values are reused and restored directly, keeping vanilla's state cache valid. */
    private static final class State {
        final int[] viewport = new int[4], polygon = new int[2];
        final int[] textures = new int[3], samplers = new int[3];
        final boolean[] colour = new boolean[4];
        final double[] depthRange = new double[2];
        int framebuffer, vao, program, activeTexture, depthFunc, clipOrigin, clipDepth;
        int srcRgb, dstRgb, srcAlpha, dstAlpha, equationRgb, equationAlpha;
        boolean depthTest, depthMask, blend, cull, scissor;

        void save() {
            framebuffer = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
            vao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
            program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
            GL11.glGetIntegerv(GL11.GL_POLYGON_MODE, polygon);
            GL11.glGetDoublev(GL11.GL_DEPTH_RANGE, depthRange);
            clipOrigin = GL11.glGetInteger(GL45.GL_CLIP_ORIGIN);
            clipDepth = GL11.glGetInteger(GL45.GL_CLIP_DEPTH_MODE);
            depthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
            depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
            depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
            blend = GL30.glIsEnabledi(GL11.GL_BLEND, 0);
            cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
            scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
            srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
            dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
            srcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
            dstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
            equationRgb = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_RGB);
            equationAlpha = GL11.glGetInteger(GL20.GL_BLEND_EQUATION_ALPHA);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                ByteBuffer mask = stack.malloc(4);
                GL11.glGetBooleanv(GL11.GL_COLOR_WRITEMASK, mask);
                for (int i = 0; i < 4; i++) colour[i] = mask.get(i) != 0;
            }
            for (int unit = 0; unit < 3; unit++) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
                textures[unit] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                samplers[unit] = GL30.glGetIntegeri(GL33.GL_SAMPLER_BINDING, unit);
            }
        }

        void restore() {
            for (int unit = 0; unit < 3; unit++) bindTexture(unit, textures[unit], samplers[unit]);
            GL13.glActiveTexture(activeTexture);
            GL20.glUseProgram(program);
            GL30.glBindVertexArray(vao);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, framebuffer);
            GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            GL45.glClipControl(clipOrigin, clipDepth);
            GL11.glDepthRange(depthRange[0], depthRange[1]);
            GL11.glDepthFunc(depthFunc);
            GL11.glDepthMask(depthMask);
            GL11.glPolygonMode(GL11.GL_FRONT_AND_BACK, polygon[0]);
            GL30.glColorMaski(0, colour[0], colour[1], colour[2], colour[3]);
            GL40.glBlendFuncSeparatei(0, srcRgb, dstRgb, srcAlpha, dstAlpha);
            GL40.glBlendEquationSeparatei(0, equationRgb, equationAlpha);
            if (blend) GL30.glEnablei(GL11.GL_BLEND, 0); else GL30.glDisablei(GL11.GL_BLEND, 0);
            capability(GL11.GL_DEPTH_TEST, depthTest);
            capability(GL11.GL_CULL_FACE, cull);
            capability(GL11.GL_SCISSOR_TEST, scissor);
        }

        private static void capability(int capability, boolean enabled) {
            if (enabled) GL11.glEnable(capability); else GL11.glDisable(capability);
        }
    }
}
