#version 330

// SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
// SPDX-License-Identifier: Apache-2.0
#moj_import <rtx-remix-renderer:weather_parameters.glsl>

in vec3 Position;
in vec2 UV0;
in vec4 Color;
in ivec2 UV2;
uniform sampler2D Sampler2;
out vec2 texCoord;
out vec4 vertexColor;
out vec2 fogDistance;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    texCoord = UV0;
    vertexColor = Color * texelFetch(Sampler2, UV2 / 16, 0);
    fogDistance = vec2(length(Position), max(length(Position.xz), abs(Position.y)));
}
