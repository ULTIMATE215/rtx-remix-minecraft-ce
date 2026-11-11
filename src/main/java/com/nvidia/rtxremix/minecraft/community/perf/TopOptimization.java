/*
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package com.nvidia.rtxremix.minecraft.community.perf;

import java.util.Locale;
import java.util.Optional;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Top Optimization (experimental): the single owner of which frame-time
 * optimizations run. Every {@link Technique} changes how work is scheduled or
 * drops work that cannot reach the image, never what is drawn, and each one
 * switches live. The master switch defaults off.
 *
 * <p>State changes run on the render thread: the settings menu, the
 * {@code /rtx topopt} command, the in-game A/B run and vetoes. {@link #active}
 * is one volatile read and a bit test, so per-frame and per-page code asks it
 * freely, and {@link #generation} lets a caller notice any change without a
 * listener.
 *
 * <p>A technique that misbehaves at runtime is <em>vetoed</em>: latched off
 * for the rest of the session with a logged reason, whatever the settings say.
 *
 * <p>JVM properties, read once at class load:
 * {@code -Drtxremix.topopt=true|false} forces the master switch,
 * {@code -Drtxremix.topopt.<id>=true|false} forces one technique, and
 * {@code -Drtxremix.topopt.log=true} logs every present-mode transition.
 */
public final class TopOptimization {

    private static final Logger LOGGER = LoggerFactory.getLogger(TopOptimization.class);

    /** The JVM property prefix; see the class comment. */
    public static final String PROPERTY = "rtxremix.topopt";
    /** The master switch's settings id; each technique appends {@code .<id>}. */
    public static final String SETTING_ID = "community.topOptimization";

    /** One optimization the master switch governs. */
    public enum Technique {
        /**
         * Present waits for Minecraft's Vulkan composite on the GPU instead of blocking the
         * render thread, so the CPU builds frame N+1 while the GPU traces frame N.
         */
        PIPELINED_PRESENT("pipelinedPresent", true),
        /** Puddle decals leave the scene while the ground is dry and their mask is empty. */
        DRY_PUDDLE_CULL("dryPuddleCull", true),
        /** Submit returns before the runtime's queue submission; self-checked every frame. */
        SUBMIT_OVERLAP("submitOverlap", false);

        private final String id;
        private final boolean defaultWanted;

        Technique(String id, boolean defaultWanted) {
            this.id = id;
            this.defaultWanted = defaultWanted;
        }

        public String id() {
            return id;
        }

        /** Whether the technique runs under the master switch until the player turns it off. */
        public boolean defaultWanted() {
            return defaultWanted;
        }

        public String settingId() {
            return SETTING_ID + "." + id;
        }

        int bit() {
            return 1 << ordinal();
        }

        public static Optional<Technique> byId(String id) {
            for (Technique technique : values()) {
                if (technique.id.equalsIgnoreCase(id)) {
                    return Optional.of(technique);
                }
            }
            return Optional.empty();
        }
    }

    private static final TopOptimization INSTANCE = new TopOptimization(System.getProperties());

    /** The process-wide instance, configured from the JVM properties. */
    public static TopOptimization get() {
        return INSTANCE;
    }

    private final Boolean forcedMaster;
    private final int forcedOnMask;
    private final int forcedOffMask;
    private final boolean logTransitions;

    private boolean enabled;
    private int wantedMask;
    private Boolean sessionOverride;
    private int vetoMask;
    private final String[] vetoReasons = new String[Technique.values().length];

    private volatile int activeMask;
    private volatile int generation;

    /**
     * An instance configured from {@code properties}. The mod uses {@link #get};
     * tests build their own so each starts from defaults.
     */
    public TopOptimization(Properties properties) {
        forcedMaster = parseSwitch(properties.getProperty(PROPERTY));
        int forcedOn = 0;
        int forcedOff = 0;
        for (Technique technique : Technique.values()) {
            if (technique.defaultWanted) {
                wantedMask |= technique.bit();
            }
            Boolean forced = parseSwitch(properties.getProperty(PROPERTY + "." + technique.id));
            if (forced != null) {
                if (forced) {
                    forcedOn |= technique.bit();
                } else {
                    forcedOff |= technique.bit();
                }
            }
        }
        forcedOnMask = forcedOn;
        forcedOffMask = forcedOff;
        logTransitions = Boolean.TRUE.equals(parseSwitch(properties.getProperty(PROPERTY + ".log")));
        recompute();
    }

    /** Parses an on/off property value; null when absent or unrecognized. */
    static Boolean parseSwitch(String value) {
        if (value == null) {
            return null;
        }
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "true", "on", "1", "yes" -> Boolean.TRUE;
            case "false", "off", "0", "no" -> Boolean.FALSE;
            default -> null;
        };
    }

    /** The player's master choice, as the settings menu persists it. */
    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        recompute();
    }

    /** The player's choice for one technique, as the settings menu persists it. */
    public boolean isWanted(Technique technique) {
        return (wantedMask & technique.bit()) != 0;
    }

    public void setWanted(Technique technique, boolean wanted) {
        wantedMask = wanted ? wantedMask | technique.bit() : wantedMask & ~technique.bit();
        recompute();
    }

    /**
     * Temporarily overrides the master choice without persisting it, for the
     * in-game A/B run; {@code null} returns to the player's choice.
     */
    public void setSessionOverride(Boolean on) {
        sessionOverride = on;
        recompute();
    }

    /** The master state in effect after the JVM property and any A/B override. */
    public boolean isMasterActive() {
        if (forcedMaster != null) {
            return forcedMaster;
        }
        return sessionOverride != null ? sessionOverride : enabled;
    }

    /** Whether a JVM property pins the master switch, which the A/B run cannot then change. */
    public boolean isMasterForced() {
        return forcedMaster != null;
    }

    /** Whether the technique runs this frame. One volatile read; callable from any thread. */
    public boolean active(Technique technique) {
        return (activeMask & technique.bit()) != 0;
    }

    public int activeMask() {
        return activeMask;
    }

    /** Increments on every change of {@link #activeMask}, so per-frame callers can poll for changes. */
    public int generation() {
        return generation;
    }

    /** Latches a technique off for the rest of the session and logs why, once. */
    public void veto(Technique technique, String reason) {
        if ((vetoMask & technique.bit()) != 0) {
            return;
        }
        vetoMask |= technique.bit();
        vetoReasons[technique.ordinal()] = reason;
        LOGGER.warn("Top Optimization: {} is disabled for this session: {}", technique.id, reason);
        recompute();
    }

    public boolean isVetoed(Technique technique) {
        return (vetoMask & technique.bit()) != 0;
    }

    /**
     * Why a technique cannot run whatever the player chooses, or null when
     * nothing blocks it: a veto, or a JVM property forcing it off.
     */
    public String blockedReason(Technique technique) {
        if (isVetoed(technique)) {
            return vetoReasons[technique.ordinal()];
        }
        if ((forcedOffMask & technique.bit()) != 0) {
            return "-D" + PROPERTY + "." + technique.id + "=false";
        }
        return null;
    }

    /** Why the master switch ignores the player's choice, or null when it does not. */
    public String masterBlockedReason() {
        return forcedMaster == null ? null : "-D" + PROPERTY + "=" + forcedMaster;
    }

    /** Whether present-mode transitions are logged as they happen. */
    public boolean logTransitions() {
        return logTransitions;
    }

    /** One line naming the effective state of every technique, for logs and the status command. */
    public String describe() {
        StringBuilder sb = new StringBuilder(isMasterActive() ? "on" : "off");
        if (forcedMaster != null) {
            sb.append(" (forced by ").append(masterBlockedReason()).append(')');
        } else if (sessionOverride != null) {
            sb.append(" (A/B run)");
        }
        sb.append(':');
        for (Technique technique : Technique.values()) {
            sb.append(' ').append(technique.id).append('=');
            if (active(technique)) {
                sb.append("on");
            } else if (isVetoed(technique)) {
                sb.append("vetoed(").append(vetoReasons[technique.ordinal()]).append(')');
            } else if ((forcedOffMask & technique.bit()) != 0) {
                sb.append("forced-off");
            } else if (!isMasterActive()) {
                sb.append("idle");
            } else {
                sb.append("off");
            }
        }
        return sb.toString();
    }

    private void recompute() {
        int mask = 0;
        if (isMasterActive()) {
            int wanted = (wantedMask | forcedOnMask) & ~forcedOffMask;
            mask = wanted & ~vetoMask;
        }
        if (mask != activeMask) {
            activeMask = mask;
            generation++;
            LOGGER.info("Top Optimization: {}", describe());
        }
    }
}
