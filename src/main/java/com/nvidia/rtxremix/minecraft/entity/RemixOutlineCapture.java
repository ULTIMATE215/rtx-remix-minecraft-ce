/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.entity;

import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.SubmitNodeStorage;
import net.minecraft.client.renderer.feature.phase.FeatureRenderPhase;

/** Keeps vanilla's glowing silhouette while discarding its duplicate scene geometry. */
public final class RemixOutlineCapture {
    private static final FeatureRenderPhase.Output DISCARD = (submit, ordered) -> {};
    private final SubmitNodeStorage queue = new SubmitNodeStorage();

    public SubmitNodeCollector queue() { return queue; }

    public void forwardTo(SubmitNodeCollector target) {
        for (var entry : queue.getSubmitsPerOrder().int2ObjectEntrySet()) {
            SubmitNodeCollection collection = entry.getValue();
            SubmitNodeCollection destination = (SubmitNodeCollection) target.order(entry.getIntKey());
            for (FeatureRenderPhase<?> phase : collection.allPhases()) {
                if (!phase.isEmpty()) {
                    phase.sortInto(phase == collection.outline
                            ? (submit, ordered) -> destination.outline.submit(submit) : DISCARD);
                }
            }
        }
    }

    public void discard() {
        for (SubmitNodeCollection collection : queue.getSubmitsPerOrder().values()) {
            for (FeatureRenderPhase<?> phase : collection.allPhases()) {
                if (!phase.isEmpty()) phase.sortInto(DISCARD);
            }
        }
    }
}
