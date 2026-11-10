// SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
// SPDX-License-Identifier: Apache-2.0
#version 150

uniform sampler2D Sampler0;
uniform vec4 FogColor;
uniform vec4 FogRange;
in vec2 texCoord;
in vec4 vertexColor;
in vec2 fogDistance;
out vec4 fragColor;

float fogValue(float distance, float start, float end) {
    if (distance <= start) return 0.0;
    if (distance >= end) return 1.0;
    return (distance - start) / (end - start);
}

void main() {
    vec4 color = texture(Sampler0, texCoord) * vertexColor;
    if (color.a < 0.1) discard;
    float fog = max(fogValue(fogDistance.x, FogRange.x, FogRange.y),
                    fogValue(fogDistance.y, FogRange.z, FogRange.w));
    fragColor = vec4(mix(color.rgb, FogColor.rgb, fog * FogColor.a), color.a);
}
