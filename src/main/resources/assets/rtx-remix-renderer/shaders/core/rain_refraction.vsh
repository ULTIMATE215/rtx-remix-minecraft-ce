#version 330

// SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
// SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
// SPDX-License-Identifier: Apache-2.0
//
// Modified by RTX Remix for Minecraft Community Edition; see COMMUNITY.md.

#moj_import <rtx-remix-renderer:weather_parameters.glsl>

in vec3 Position;
in vec2 UV0;
in vec4 Color;
in ivec2 UV2;


out vec2 texCoord;
out vec4 vertexColor;
out vec3 viewPosition;

void main() {
    viewPosition = (ModelViewMat * vec4(Position, 1.0)).xyz;
    gl_Position = ProjMat * vec4(viewPosition, 1.0);
    texCoord = UV0;
    vertexColor = Color;
}
