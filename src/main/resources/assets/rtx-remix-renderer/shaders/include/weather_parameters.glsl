// SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
// SPDX-License-Identifier: Apache-2.0
layout(std140) uniform WeatherParameters {
    mat4 ModelViewMat;
    mat4 ProjMat;
    vec4 FogColor;
    vec4 FogRange;
    vec4 WeatherViewport;
};
