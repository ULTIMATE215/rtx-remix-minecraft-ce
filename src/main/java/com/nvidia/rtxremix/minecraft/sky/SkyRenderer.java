/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Modified by RTX Remix for Minecraft Community Edition; see COMMUNITY.md.
 */
package com.nvidia.rtxremix.minecraft.sky;

import com.nvidia.rtxremix.minecraft.util.NativeImagePixels;
import com.mojang.blaze3d.platform.NativeImage;
import com.nvidia.rtxremix.HardcodedVertex;
import com.nvidia.rtxremix.InstanceCategoryBit;
import com.nvidia.rtxremix.InstanceInfoBlendEXT;
import com.nvidia.rtxremix.LightInfo;
import com.nvidia.rtxremix.LightInfoDomeEXT;
import com.nvidia.rtxremix.LightInfoRectEXT;
import com.nvidia.rtxremix.MaterialInfo;
import com.nvidia.rtxremix.MaterialInfoOpaqueEXT;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.TextureBatchEntry;
import com.nvidia.rtxremix.TextureFormat;
import com.nvidia.rtxremix.TexturePatch;
import com.nvidia.rtxremix.TexturePatchInfo;
import com.nvidia.rtxremix.TexturePurpose;
import com.nvidia.rtxremix.Transform;
import com.nvidia.rtxremix.minecraft.RemixRenderer;
import com.nvidia.rtxremix.minecraft.asset.RemixAssetBase;
import com.nvidia.rtxremix.minecraft.asset.RemixLight;
import com.nvidia.rtxremix.minecraft.asset.RemixLightStore;
import com.nvidia.rtxremix.minecraft.asset.RemixMaterial;
import com.nvidia.rtxremix.minecraft.asset.RemixMaterialStore;
import com.nvidia.rtxremix.minecraft.asset.RemixMesh;
import com.nvidia.rtxremix.minecraft.asset.RemixMeshStore;
import com.nvidia.rtxremix.minecraft.asset.RemixMeshSurface;
import com.nvidia.rtxremix.minecraft.asset.RemixTexture;
import com.nvidia.rtxremix.minecraft.asset.RemixTextureStore;
import com.nvidia.rtxremix.minecraft.community.CommunityLook;
import com.nvidia.rtxremix.minecraft.community.DaylightSky;
import com.nvidia.rtxremix.minecraft.community.LightningFlash;
import com.nvidia.rtxremix.minecraft.community.NightLight;
import com.nvidia.rtxremix.minecraft.community.NightSkyRenderer;
import com.nvidia.rtxremix.minecraft.community.OvercastSky;
import com.nvidia.rtxremix.minecraft.community.RoundSun;
import com.nvidia.rtxremix.minecraft.community.SunShafts;
import com.nvidia.rtxremix.minecraft.community.clouds.CloudSkyCompositor;
import com.nvidia.rtxremix.minecraft.community.clouds.VolumetricClouds;
import com.nvidia.rtxremix.minecraft.light.LightManager;
import com.nvidia.rtxremix.minecraft.logic.ConfLayerDriver;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import com.nvidia.rtxremix.minecraft.util.DirectBufferPool;
import org.joml.Quaternionf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.Vec3;

/**
 * Manages sky rendering for RTX Remix.
 *
 * <p>Uses a physically-based dome light generated from Rayleigh+Mie atmospheric
 * scattering, driven by Minecraft's time-of-day. Weather blends a static HDR
 * overcast dome over the atmospheric sky. Dimensions without a NORMAL sky publish a
 * constant dome instead: dark violet for the End, and black with no sky ambient for a
 * skyless (SkyType.NONE) dimension such as the Nether.
 */
public class SkyRenderer {

    private static final Logger LOGGER = LoggerFactory.getLogger(SkyRenderer.class);

    /** Equirectangular sky texture resolution. */
    private static final int SKY_W = AtmosphericSkyGenerator.WIDTH;
    private static final int SKY_H = AtmosphericSkyGenerator.HEIGHT;
    private static final String OVERCAST_TEXTURE_DDS = "textures/overcast_thunderstorm_bc6u.dds";
    private static final float RAIN_DOME_MIN_INTENSITY = 0.02f;
    private static final float OVERCAST_MEAN_R = 0.2393801f;
    private static final float OVERCAST_MEAN_G = 0.26890025f;
    private static final float OVERCAST_MEAN_B = 0.31064802f;
    private static final float WEATHER_UPDATE_DELTA = 1.0e-4f;
    /** Share of the storm dome's opacity kept while the volumetric clouds draw the overcast. */
    private static final float VOLUMETRIC_WEATHER_DOME_SHARE = 0.35f;
    /** Share of the previous cloud-dome mean kept per readback (about 10 a second). */
    private static final float CLOUD_MEAN_KEEP = 0.8f;
    private static final Identifier SUN_TEXTURE =
            Identifier.withDefaultNamespace("textures/environment/sun.png");
    private static final Identifier MOON_PHASES_TEXTURE =
            Identifier.withDefaultNamespace("textures/environment/moon_phases.png");
    private static final float CELESTIAL_DISTANCE = 100.0f;
    private static final float SUN_HALF_SIZE = 30.0f;
    private static final float MOON_HALF_SIZE = 20.0f;
    private static final float SUN_RADIANCE = 8.0f;
    private static final float MOON_RADIANCE = 0.2f;
    private static final float STAR_RADIANCE = 1.0f;
    private static final float STAR_DISTANCE_SCALE = 1.1f;
    private static final float CELESTIAL_HORIZON_FADE_DEGREES = 4.0f;
    private static final float CELESTIAL_HORIZON_FADE_MIN_HEIGHT =
            -(float) Math.sin(Math.toRadians(CELESTIAL_HORIZON_FADE_DEGREES));
    private static final float CELESTIAL_OPACITY_UPDATE_DELTA = 1.0f / 255.0f;
    private static final int FILTER_NEAREST = 0;
    private static final int FILTER_LINEAR = 1;
    /** Angular radius of the real sun, which the round sun's colour is evaluated over. */
    private static final float ROUND_SUN_TRANSMITTANCE_RADIUS = (float) Math.toRadians(0.265);
    private static final InstanceInfoBlendEXT CELESTIAL_BLEND =
            createCelestialBlend();

    /** Sky ambient rect light: Y position just above the 1.18+ overworld build limit (y=320). */
    private static final float SKY_AMBIENT_RECT_Y = 325.0f;
    /** Sky ambient rect light: half-size in blocks, so it covers 8000 blocks from origin. */
    private static final float SKY_AMBIENT_RECT_HALF_SIZE = 8000.0f;
    /**
     * Constant sky for the End, in the atmospheric dome's radiance units: vanilla end_sky.png's
     * mean chroma under its 0xFF282828 vertex tint, at the luminance of that tint (40/255) times
     * the End's 0.15 fog factor. It feeds both the dome and the ambient rect.
     */
    static final float[] END_SKY_RADIANCE = {0.0275f, 0.0210f, 0.0372f};
    /**
     * Constant sky for a skyless dimension (SkyType.NONE): a black dome and, since the ambient
     * rect carries the same constant, no sky ambient. Nothing above a roofed world reaches its
     * interior, so a rect above the build limit would light only the roof top; interior light
     * comes from emissives and the fog medium. The black dome is still published: without an
     * active dome light Remix composites escaped rays from its own sky matte, which nothing
     * draws here.
     */
    static final float[] SKYLESS_SKY_RADIANCE = {0.0f, 0.0f, 0.0f};
    /**
     * Community Edition default, down from 2.5 (and 1.4 in 0.1.0-ce.3): the dome
     * already lights the scene with the night sky, so the rect counted that sky
     * a second time, from straight above, in its cyan mean colour. At 1.4 the
     * night sky gave 4.7 times the moonlight; cyan fill on yellow-green grass is
     * what turned moonlit grass neon green. The setting stays for players who
     * want a brighter night.
     */
    public static final float DEFAULT_NIGHT_AMBIENT_BOOST = 0.0f;
    public static final float DEFAULT_DAY_AMBIENT_BOOST = 0.0f;
    public static final boolean DEFAULT_SKY_AMBIENT_RECT_ENABLED = true;
    public static final float DEFAULT_MINIMUM_CELESTIAL_WEATHER_VISIBILITY = 0.0f;
    /** Community Edition: 0.96 rather than 0.9, so the sun's glow stays a halo instead of washing out half the sky. */
    public static final float DEFAULT_MIE_SCATTERING_ANISOTROPY = 0.96f;
    public static final float MAX_MIE_SCATTERING_ANISOTROPY = 0.99f;
    private static float nightAmbientBoost = DEFAULT_NIGHT_AMBIENT_BOOST;
    private static float dayAmbientBoost = DEFAULT_DAY_AMBIENT_BOOST;
    private static float minimumCelestialWeatherVisibility = DEFAULT_MINIMUM_CELESTIAL_WEATHER_VISIBILITY;
    private static float mieScatteringAnisotropy = DEFAULT_MIE_SCATTERING_ANISOTROPY;

    public static float getNightAmbientBoost()        { return nightAmbientBoost; }
    public static void setNightAmbientBoost(float v)  { nightAmbientBoost = v; }
    public static float getDayAmbientBoost()          { return dayAmbientBoost; }
    public static void setDayAmbientBoost(float v)    { dayAmbientBoost = v; }
    public static float getMinimumCelestialWeatherVisibility()  { return minimumCelestialWeatherVisibility; }
    public static void setMinimumCelestialWeatherVisibility(float v) {
        minimumCelestialWeatherVisibility = Math.clamp(v, 0.0f, 1.0f);
    }
    public static float getMieScatteringAnisotropy()  { return mieScatteringAnisotropy; }
    public static void setMieScatteringAnisotropy(float v) {
        mieScatteringAnisotropy = Math.clamp(v, 0.0f, MAX_MIE_SCATTERING_ANISOTROPY);
    }

    /**
     * Sun path tilt in degrees, matching popular shader packs (BSL, Complementary).
     * Tilts the sun's orbital plane around the east-west (X) axis so that even
     * at noon, shadows have an angle instead of pointing straight down.
     */
    public static final float SUN_PATH_ROTATION_DEG = LightManager.SUN_PATH_ROTATION_DEG;
    // ---- Instance state ----

    private final RemixRenderer renderer;
    private ClientLevel currentWorld;
    private RemixTexture skyTextureAsset;
    private RemixLight skyDomeLightAsset;
    private long overcastTextureHandle;
    private RemixLight overcastDomeLightAsset;
    private RemixLight skyAmbientRectLight;
    private RemixTexture sunTextureAsset;
    private RemixTexture moonTextureAsset;
    private RemixMaterial sunMaterialAsset;
    private RemixMaterial moonMaterialAsset;
    private RemixMaterial starMaterialAsset;
    private RemixMesh sunMeshAsset;
    private final RemixMesh[] moonMeshAssets = new RemixMesh[8];
    private RemixMesh starMeshAsset;
    private RemixTexture roundSunTextureAsset;
    private RemixMaterial roundSunMaterialAsset;
    private RemixMesh roundSunMeshAsset;
    private ByteBuffer roundSunPixels;
    private final float[] roundSunColor = {-1.0f, -1.0f, -1.0f};
    private final float[] roundSunTransmittance = new float[3];
    private final TexturePatch roundSunTexturePatch = new TexturePatch();
    private final TextureBatchEntry roundSunTextureEntry = new TextureBatchEntry();
    private final TexturePatchInfo roundSunTextureUpdate = new TexturePatchInfo();
    private float lastRoundSunOpacity = -1.0f;
    private final NightSkyRenderer nightSky = new NightSkyRenderer();
    private long celestialBlendPNext;
    private final AtmosphericSkyGenerator atmosphericGenerator;
    private final TexturePatch atmosphericTexturePatch = new TexturePatch();
    private final TextureBatchEntry atmosphericTextureEntry = new TextureBatchEntry();
    private final TexturePatchInfo atmosphericTextureUpdate = new TexturePatchInfo();
    private final float[] atmosphericMean = new float[3];
    /** Community Edition: the far layer of the volumetric clouds, composited into the dome. */
    private CloudSkyCompositor cloudCompositor;
    private final float[] cloudSunIlluminance = new float[3];
    private final float[] cloudMoonIlluminance = new float[3];
    private final float[] cloudSunDirection = new float[3];
    private final float[] cloudMoonDirection = new float[3];
    /** Refilled every frame rather than allocated, since the compositor runs every frame. */
    private final CloudSkyCompositor.Inputs cloudInputs = new CloudSkyCompositor.Inputs();
    /** The band-per-frame upload of the held cloud readback into a dome texture of the same size. */
    private final DomeBandScan cloudDomeScan = new DomeBandScan();
    /** Whether the published dome texture holds the clouds, so turning them off republishes the bare sky. */
    private boolean domeShowsClouds;
    /** This frame's storm-dome opacity before the volumetric clouds thin it. */
    private float stormOpacity;
    private int skyTextureWidth;
    private int skyTextureHeight;
    private long publishedDomeTextureHandle;
    private final Transform sunTransform = new Transform();
    private final Transform moonTransform = new Transform();
    private final Transform starTransform = new Transform();
    private final float[] celestialMoonDirection = new float[3];
    private boolean celestialInitializationFailed;
    private float lastWeatherOpacity = -1f;
    private float lastWeatherIntensity = -1f;
    private float lastSkyDomeMultiplier = Float.NaN;
    private float atmosphericMeanR;
    private float atmosphericMeanG;
    private float atmosphericMeanB;
    private float lastSunOpacity = -1.0f;
    private float lastMoonOpacity = -1.0f;
    private float lastStarOpacity = -1.0f;
    private boolean overcastInitializationFailed;

    private static boolean enabled = true;
    private static boolean skyAmbientRectEnabled = DEFAULT_SKY_AMBIENT_RECT_ENABLED;
    private static float skyDomeMultiplier = 1.0f;

    public SkyRenderer(RemixRenderer renderer) {
        this.renderer = renderer;
        atmosphericGenerator = new AtmosphericSkyGenerator();
        atmosphericTexturePatch.width = SKY_W;
        atmosphericTexturePatch.height = SKY_H;
        atmosphericTexturePatch.rowPitch = SKY_W * 4 * Short.BYTES;
        atmosphericTexturePatch.format = TextureFormat.RGBA16_SFLOAT;
        atmosphericTextureEntry.patches = new TexturePatch[]{atmosphericTexturePatch};
        atmosphericTextureUpdate.entries = new TextureBatchEntry[]{atmosphericTextureEntry};
        roundSunTexturePatch.width = RoundSun.TEXTURE_SIZE;
        roundSunTexturePatch.height = RoundSun.TEXTURE_SIZE;
        roundSunTexturePatch.rowPitch = RoundSun.TEXTURE_SIZE * 4;
        roundSunTexturePatch.format = TextureFormat.RGBA8_UNORM;
        roundSunTextureEntry.patches = new TexturePatch[]{roundSunTexturePatch};
        roundSunTextureUpdate.entries = new TextureBatchEntry[]{roundSunTextureEntry};
        LOGGER.info("Sky renderer initialized (Rayleigh+Mie dome light)");
    }

    public static boolean isEnabled()                        { return enabled; }
    public static void setEnabled(boolean v)                 { enabled = v; }
    public static boolean isSkyAmbientRectEnabled()          { return skyAmbientRectEnabled; }
    public static void setSkyAmbientRectEnabled(boolean v)   { skyAmbientRectEnabled = v; }
    public static float getSkyDomeMultiplier()               { return skyDomeMultiplier; }
    public static void setSkyDomeMultiplier(float v)         { skyDomeMultiplier = v; }

    /** Invalidates cached sky parameters so they are applied on the next frame. */
    public void forceRefresh() {
        lastWeatherOpacity = -1f;
        lastWeatherIntensity = -1f;
        lastSunOpacity = -1.0f;
        lastMoonOpacity = -1.0f;
        lastStarOpacity = -1.0f;
    }

    // ====================================================================
    //  Main render entry
    // ====================================================================

    public void render(ClientLevel world, Vec3 cameraPos, float tickDelta) {
        if (world == null || renderer.getRemixApi() == null || !enabled) return;

        // A portal replaces the world without a disconnect, and each sky type publishes a
        // different set of lights, so nothing published for the previous world may carry over.
        if (world != currentWorld) {
            releaseSkyLights();
            currentWorld = world;
        }

        RemixAPI api = renderer.getRemixApi();
        float[] fixedRadiance = fixedSkyRadiance(WorldSky.skybox(world));
        if (fixedRadiance == null) {
            renderAtmosphericSky(api, world, cameraPos, tickDelta);
        } else {
            renderFixedSky(api, fixedRadiance);
        }
    }

    /**
     * Maps a sky type to the constant radiance its dome and ambient rect publish.
     *
     * @return the constant, or null for a NORMAL sky, whose dome is the time-driven atmosphere
     */
    static float[] fixedSkyRadiance(DimensionType.Skybox skyType) {
        return switch (skyType) {
            case OVERWORLD -> null;
            case END -> END_SKY_RADIANCE;
            case NONE -> SKYLESS_SKY_RADIANCE;
        };
    }

    private void renderAtmosphericSky(
            RemixAPI api,
            ClientLevel world,
            Vec3 cameraPos,
            float tickDelta) {
        float skyAngle;
        LightManager.SkyLightState lightState;
        float[] sunDirection;
        WeatherDomeState weather;
        try (ProfileScope ignored = ProfileScope.begin("Sky.Prepare")) {
            skyAngle = WorldSky.skyAngle(world, tickDelta);
            ConfLayerDriver confLayers = renderer.getConfLayerDriver();
            ConfLayerDriver.WeatherStrengths weatherStrengths =
                    confLayers != null && confLayers.isActive()
                            ? confLayers.weatherStrengths()
                            : new ConfLayerDriver.WeatherStrengths(
                                    world.getRainLevel(tickDelta),
                                    world.getThunderLevel(tickDelta));
            LightManager lightManager = renderer.getLightManager();
            lightState = lightManager != null
                    ? lightManager.getSkyLightState()
                    : LightManager.SkyLightState.empty();
            sunDirection = LightManager.computeSunDirection(skyAngle);
            float celestialIntensity =
                    lightState.weatherDomeRadiance();
            float domeScale = OvercastSky.domeScale();
            weather = weatherDomeState(
                    weatherStrengths.rain(),
                    weatherStrengths.thunder(),
                    Math.max(
                            celestialIntensity,
                            RAIN_DOME_MIN_INTENSITY) * domeScale,
                    celestialIntensity
                            * LightManager.THUNDER_SKY_INTENSITY * domeScale);
            stormOpacity = weather.opacity();
            if (VolumetricClouds.isActive()) {
                // Community Edition: the volumetric clouds are the overcast now; the static
                // storm dome only deepens it a little.
                weather = new WeatherDomeState(
                        weather.opacity() * VOLUMETRIC_WEATHER_DOME_SHARE, weather.intensity());
            }
        }

        try (ProfileScope ignored = ProfileScope.begin("Sky.Atmosphere")) {
            updateAtmosphericDomeLight(api, lightState, cameraPos, world.getDefaultClockTime() + (double) tickDelta);
        }
        try (ProfileScope ignored = ProfileScope.begin("Sky.Celestial")) {
            renderCelestialMeshes(
                    api,
                    world,
                    cameraPos,
                    skyAngle,
                    tickDelta,
                    sunDirection,
                    weather.opacity());
        }
        boolean weatherRendered;
        try (ProfileScope ignored = ProfileScope.begin("Sky.Weather")) {
            weatherRendered = updateWeatherDomeLight(api, weather);
        }
        try (ProfileScope ignored = ProfileScope.begin("Sky.Ambient")) {
            publishCombinedSkyAmbient(
                    api,
                    sunDirection[1],
                    weatherRendered
                            ? weather
                            : WeatherDomeState.CLEAR,
                    weatherRendered ? stormOpacity : 0.0f);
        }
    }

    /**
     * Publishes a dome of one constant radiance through the atmospheric dome's texture and
     * light, and an ambient rect of the same radiance, so a black sky publishes no ambient. The
     * texture is filled once per world and never dispatches the atmosphere compute.
     */
    private void renderFixedSky(RemixAPI api, float[] radiance) {
        if (skyTextureAsset == null || !skyTextureAsset.isAlive()) {
            destroyAsset(skyTextureAsset);
            ByteBuffer pixels = DirectBufferPool.acquire(AtmosphericSkyGenerator.OUTPUT_BYTES);
            fillFixedDome(pixels, radiance);
            skyTextureAsset = createSkyTexture(pixels, SKY_W, SKY_H);
            skyTextureWidth = SKY_W;
            skyTextureHeight = SKY_H;
            domeShowsClouds = false;
        }
        publishSkyDome(api);
        publishSkyAmbient(api, radiance[0], radiance[1], radiance[2], 1.0f);
    }

    /**
     * Fills an RGBA16F equirect of the dome's resolution with one opaque radiance, leaving the
     * buffer positioned at zero with its limit on the last dome byte.
     *
     * @throws IllegalArgumentException when the buffer cannot hold the whole dome
     */
    static void fillFixedDome(ByteBuffer pixels, float[] radiance) {
        if (pixels.capacity() < AtmosphericSkyGenerator.OUTPUT_BYTES) {
            throw new IllegalArgumentException(
                    "Dome buffer is smaller than " + AtmosphericSkyGenerator.OUTPUT_BYTES);
        }
        short r = Float.floatToFloat16(radiance[0]);
        short g = Float.floatToFloat16(radiance[1]);
        short b = Float.floatToFloat16(radiance[2]);
        short a = Float.floatToFloat16(1.0f);
        pixels.clear();
        pixels.order(ByteOrder.nativeOrder());
        pixels.limit(AtmosphericSkyGenerator.OUTPUT_BYTES);
        for (int texel = 0; texel < SKY_W * SKY_H; texel++) {
            pixels.putShort(r).putShort(g).putShort(b).putShort(a);
        }
        pixels.flip();
    }

    // ====================================================================
    //  Dome lights
    // ====================================================================

    private void updateAtmosphericDomeLight(
            RemixAPI api,
            LightManager.SkyLightState lightState,
            Vec3 cameraPos, double dayTime) {
        regenerateSkyTexture(api, lightState, cameraPos, dayTime);
        publishSkyDome(api);
    }

    /**
     * Creates the dome light over the live sky texture when it is missing, refreshes its
     * multiplier when that changed, and draws it. Does nothing until the texture exists.
     */
    private void publishSkyDome(RemixAPI api) {
        if (skyTextureAsset == null || !skyTextureAsset.isAlive()) {
            return;
        }
        boolean domeAlive = skyDomeLightAsset != null && skyDomeLightAsset.isAlive();
        if (!domeAlive || Float.compare(lastSkyDomeMultiplier, skyDomeMultiplier) != 0
                || publishedDomeTextureHandle != skyTextureAsset.getHandle()) {
            LightInfoDomeEXT domeExt = new LightInfoDomeEXT();
            domeExt.setColorTexture(skyTextureAsset.getHandle());
            LightInfo lightInfo = LightInfo.builder()
                    .radiance(skyDomeMultiplier, skyDomeMultiplier, skyDomeMultiplier)
                    .domeExt(domeExt)
                    .build();
            if (domeAlive) {
                RemixLightStore.get().update(skyDomeLightAsset, lightInfo);
            } else {
                destroyAsset(skyDomeLightAsset);
                skyDomeLightAsset = new RemixLight(lightInfo, null);
                RemixLightStore.get().create(skyDomeLightAsset);
            }
            lastSkyDomeMultiplier = skyDomeMultiplier;
            publishedDomeTextureHandle = skyTextureAsset.getHandle();
        }
        if (skyDomeLightAsset.isAlive()) {
            try (ProfileScope ignored =
                    ProfileScope.begin("Sky.Atmosphere.DomeDraw")) {
                skyDomeLightAsset.draw(api);
            }
        }
    }

    private boolean updateWeatherDomeLight(RemixAPI api, WeatherDomeState weather) {
        if (weather.opacity() <= 0.0f) {
            return false;
        }

        float domeIntensity = weather.intensity() * skyDomeMultiplier;
        boolean needsUpdate = overcastDomeLightAsset == null
                || Math.abs(weather.opacity() - lastWeatherOpacity) > WEATHER_UPDATE_DELTA
                || Math.abs(domeIntensity - lastWeatherIntensity) > WEATHER_UPDATE_DELTA;

        if (needsUpdate) {
            if (!ensureOvercastTexture(api)) {
                return false;
            }

            LightInfoDomeEXT domeExt = new LightInfoDomeEXT();
            domeExt.setColorTexture(overcastTextureHandle);
            domeExt.setOpacity(weather.opacity());
            domeExt.setBlendType(MaterialInfoOpaqueEXT.BLEND_TYPE_ALPHA);
            LightInfo lightInfo = LightInfo.builder()
                    .radiance(domeIntensity, domeIntensity, domeIntensity)
                    .domeExt(domeExt)
                    .build();

            if (overcastDomeLightAsset != null && overcastDomeLightAsset.isAlive()) {
                RemixLightStore.get().update(overcastDomeLightAsset, lightInfo);
            } else {
                if (overcastDomeLightAsset != null && !overcastDomeLightAsset.isDeleted()) {
                    overcastDomeLightAsset.destroy();
                }
                overcastDomeLightAsset = new RemixLight(lightInfo, null);
                RemixLightStore.get().create(overcastDomeLightAsset);
            }

            lastWeatherOpacity = weather.opacity();
            lastWeatherIntensity = domeIntensity;
        }

        if (overcastDomeLightAsset == null || !overcastDomeLightAsset.isAlive()) {
            return false;
        }
        overcastDomeLightAsset.draw(api);
        return true;
    }

    private boolean ensureOvercastTexture(RemixAPI api) {
        if (overcastTextureHandle != 0) {
            return true;
        }
        if (overcastInitializationFailed) {
            return false;
        }

        String gameDirectory = Minecraft.getInstance()
                .gameDirectory.getAbsolutePath();
        String texturePath = gameDirectory + File.separator + OVERCAST_TEXTURE_DDS;
        try {
            overcastTextureHandle = api.createTextureFromFile(texturePath);
            if (overcastTextureHandle == 0) {
                throw new IllegalStateException("Remix returned a null texture handle");
            }
            LOGGER.info("Loaded overcast dome texture {}", texturePath);
            return true;
        } catch (RuntimeException e) {
            overcastInitializationFailed = true;
            LOGGER.error("Failed to load overcast dome texture {}; using atmospheric sky", texturePath, e);
            return false;
        }
    }

    private void regenerateSkyTexture(
            RemixAPI api,
            LightManager.SkyLightState lightState,
            Vec3 cameraPos, double dayTime) {
        boolean clouds = VolumetricClouds.isActive() && VolumetricClouds.isUsable();
        // Both assets must be live for a skip to be safe. They are released whenever the world
        // changes, and a world whose time never advances keeps the parameters constant forever,
        // so gating on the parameters alone would never rebuild them and the dome would stay black.
        // A dome that still shows clouds after they were switched off is not valid either.
        boolean publishedOutputValid = skyTextureAsset != null
                && skyTextureAsset.isAlive()
                && skyDomeLightAsset != null
                && skyDomeLightAsset.isAlive()
                && (clouds || !domeShowsClouds);
        ByteBuffer pixelBuf = atmosphericGenerator.generate(
                lightState,
                atmosphericMean,
                mieScatteringAnisotropy,
                publishedOutputValid);
        if (clouds && atmosphericGenerator.hasOutput()) {
            if (compositeClouds(api, lightState, cameraPos, dayTime)) {
                return;
            }
        } else if (cloudCompositor != null && !clouds) {
            destroyCloudCompositor();
        }
        if (pixelBuf == null || clouds && domeShowsClouds) {
            return;
        }
        atmosphericMeanR = atmosphericMean[0];
        atmosphericMeanG = atmosphericMean[1];
        atmosphericMeanB = atmosphericMean[2];
        uploadDome(api, pixelBuf, SKY_W, SKY_H, AtmosphericSkyGenerator.OUTPUT_BYTES);
        domeShowsClouds = false;
    }

    /**
     * Runs the far layer of the volumetric clouds over the atmosphere the generator holds and
     * publishes the result as the dome when a readback is ready.
     *
     * @return whether the clouds own the dome this frame, so the bare sky must not be uploaded
     */
    private boolean compositeClouds(RemixAPI api, LightManager.SkyLightState lightState,
                                    Vec3 cameraPos, double dayTime) {
        try {
            VolumetricClouds.Quality quality = VolumetricClouds.currentQuality();
            if (cloudCompositor != null && cloudCompositor.quality().domeWidth() != quality.domeWidth()) {
                destroyCloudCompositor();
            }
            if (cloudCompositor == null) {
                CloudSkyCompositor.AtmosphereDefines.set(AtmosphereModel.shaderDefines());
                cloudCompositor = new CloudSkyCompositor(quality, SKY_W, SKY_H);
            }
            float scale = AtmosphericSkyGenerator.atmosphereScatteringScale(lightState.sunPosition()[1]);
            copyNormalized(lightState.sunPosition(), cloudSunDirection);
            copyNormalized(lightState.moonPosition(), cloudMoonDirection);
            for (int i = 0; i < 3; i++) {
                cloudSunIlluminance[i] = lightState.sunSourceRadiance()[i] * scale;
                cloudMoonIlluminance[i] = lightState.moonSourceRadiance()[i] * scale;
            }
            ByteBuffer pixels;
            try (ProfileScope ignored = ProfileScope.begin("Cloud.Dome")) {
                pixels = cloudCompositor.update(
                        atmosphericGenerator.finalSkyTexture(),
                        atmosphericGenerator.transmittanceLutTexture(),
                        cloudInputs.set(
                                cloudSunDirection, cloudMoonDirection,
                                cloudSunIlluminance, cloudMoonIlluminance,
                                lightState.sunAngularRadius(), lightState.moonAngularRadius(),
                                cameraPos.x, cameraPos.y, cameraPos.z,
                                dayTime, DaylightSky.isPhysicalAtmosphere(), DaylightSky.skyFill()),
                        atmosphericMean);
            }
            if (pixels != null) {
                // Each readback refreshes a quarter of the sky with fresh jitter, so its mean wobbles
                // a little; the whole world's sky ambient follows it, so ease it in rather than step
                // it, which would read as flicker under a dense storm.
                float keep = domeShowsClouds ? CLOUD_MEAN_KEEP : 0.0f;
                atmosphericMeanR = lerp(atmosphericMean[0], atmosphericMeanR, keep);
                atmosphericMeanG = lerp(atmosphericMean[1], atmosphericMeanG, keep);
                atmosphericMeanB = lerp(atmosphericMean[2], atmosphericMeanB, keep);
                publishCloudReadback(api, pixels);
                domeShowsClouds = true;
            } else {
                continueCloudDomeScan(api);
            }
            // Until the first cloud readback lands, the bare sky keeps the dome.
            return domeShowsClouds;
        } catch (RuntimeException e) {
            LOGGER.error("Volumetric clouds failed; falling back to the chosen cloud style", e);
            VolumetricClouds.reportFailure(e);
            destroyCloudCompositor();
            return false;
        }
    }

    /**
     * Publishes a freshly read-back cloud dome. A texture of another size, or none, is created
     * from the whole image at once and the readback released straight away, since the texture
     * then owns a copy. Otherwise the image is uploaded a quarter at a time over the next frames.
     *
     * <p>Why bands: a dome is 16 MB at the default quality (38 MB at Ultra), refreshed every
     * other tick, and one {@code UpdateTextureFromMemory} of all of it, with the mip chain it
     * regenerates, stalled the render thread for one frame in every few: a steady 10 Hz hitch,
     * which frame generation then stretches across its interpolated frames. A quarter per frame
     * spreads the same bytes evenly; the runtime regenerates mips only over each band.
     *
     * <p>A readback that lands while a scan is still going continues the scan from the band it
     * had reached, now reading the newer image, and restarts the count, so every band shows an
     * image no older than four frames behind the newest readback.
     */
    private void publishCloudReadback(RemixAPI api, ByteBuffer pixels) {
        int width = cloudCompositor.width();
        int height = cloudCompositor.height();
        if (!domeTextureMatches(width, height)) {
            // uploadDome stops any scan; the copy it makes runs before the next update() can
            // enqueue another readback, so the release cannot race it.
            uploadDome(api, pixels, width, height, cloudCompositor.outputBytes());
            cloudCompositor.releaseHeldReadback();
            return;
        }
        cloudDomeScan.retarget(pixels, width, height);
        uploadNextCloudDomeBand(api);
    }

    /**
     * Uploads the next band of the held readback on a frame without a new one. Abandons the
     * scan when the compositor no longer holds that readback (a restart discarded its scene,
     * and the GPU may already be writing a new one into the same memory) or the texture it
     * was writing into is gone; the next readback starts over.
     */
    private void continueCloudDomeScan(RemixAPI api) {
        if (!cloudDomeScan.active()) {
            return;
        }
        if (!cloudCompositor.holdsReadback()
                || !domeTextureMatches(cloudDomeScan.width(), cloudDomeScan.height())) {
            stopCloudDomeScan();
            return;
        }
        uploadNextCloudDomeBand(api);
    }

    private void uploadNextCloudDomeBand(RemixAPI api) {
        int width = cloudDomeScan.width();
        int height = cloudDomeScan.height();
        int band = cloudDomeScan.nextBand();
        ByteBuffer view = cloudDomeScan.bandView(band);
        int startRow = DomeBandScan.bandStartRow(height, band);
        try (ProfileScope ignored =
                ProfileScope.begin("Sky.Atmosphere.Upload").bytes(view.capacity())) {
            atmosphericTexturePatch.dstX = 0;
            atmosphericTexturePatch.dstY = startRow;
            atmosphericTexturePatch.width = width;
            atmosphericTexturePatch.height = DomeBandScan.bandStartRow(height, band + 1) - startRow;
            atmosphericTexturePatch.rowPitch = width * 4 * Short.BYTES;
            updateAtmosphericTexture(api, view);
        }
        if (!cloudDomeScan.active()) {
            stopCloudDomeScan();
        }
    }

    /** Ends any band scan and returns its readback to the compositor's ring. */
    private void stopCloudDomeScan() {
        cloudDomeScan.stop();
        if (cloudCompositor != null) {
            cloudCompositor.releaseHeldReadback();
        }
    }

    private boolean domeTextureMatches(int width, int height) {
        return skyTextureAsset != null && skyTextureAsset.isAlive()
                && skyTextureWidth == width && skyTextureHeight == height;
    }

    private void destroyCloudCompositor() {
        // The band views point into the compositor's mapped buffers, which destroy() unmaps.
        cloudDomeScan.forgetViews();
        if (cloudCompositor != null) {
            try {
                cloudCompositor.destroy();
            } catch (RuntimeException e) {
                LOGGER.warn("Failed to release the volumetric cloud compositor", e);
            }
            cloudCompositor = null;
        }
    }

    private static void copyNormalized(float[] source, float[] target) {
        float length = (float) Math.sqrt(
                source[0] * source[0] + source[1] * source[1] + source[2] * source[2]);
        float inverse = length > 0.0f ? 1.0f / length : 0.0f;
        target[0] = source[0] * inverse;
        target[1] = source[1] * inverse;
        target[2] = source[2] * inverse;
    }

    /**
     * Uploads a whole RGBA16F equirect into the dome texture in one call, replacing the texture
     * when its size changed. The bare atmosphere goes through here every time: at 256 × 128 it
     * is 256 KB, small enough that splitting it would only delay the sky by frames. A cloud
     * dome goes through here only to create its texture; same-size cloud updates are banded.
     */
    private void uploadDome(RemixAPI api, ByteBuffer pixelBuf, int width, int height, int bytes) {
        try (ProfileScope ignored =
                ProfileScope.begin("Sky.Atmosphere.Upload").bytes(bytes)) {
            if (!domeTextureMatches(width, height)) {
                // A new texture invalidates any band scan into the old one.
                stopCloudDomeScan();
                destroyAsset(skyTextureAsset);
                ByteBuffer ownedPixels = DirectBufferPool.acquire(bytes);
                pixelBuf.position(0);
                pixelBuf.limit(bytes);
                ownedPixels.put(pixelBuf);
                ownedPixels.flip();
                pixelBuf.position(0);
                skyTextureAsset = createSkyTexture(ownedPixels, width, height);
                skyTextureWidth = width;
                skyTextureHeight = height;
            } else {
                atmosphericTexturePatch.dstX = 0;
                atmosphericTexturePatch.dstY = 0;
                atmosphericTexturePatch.width = width;
                atmosphericTexturePatch.height = height;
                atmosphericTexturePatch.rowPitch = width * 4 * Short.BYTES;
                updateAtmosphericTexture(api, pixelBuf);
            }
        }
    }

    /** Creates the dome's equirect texture over a pool buffer the texture then owns. */
    private static RemixTexture createSkyTexture(ByteBuffer ownedPixels, int width, int height) {
        RemixTexture texture = new RemixTexture(
                width,
                height,
                TextureFormat.RGBA16_SFLOAT,
                0,
                ownedPixels);
        return RemixTextureStore.get().create(texture);
    }

    private void updateAtmosphericTexture(RemixAPI api, ByteBuffer pixelData) {
        atmosphericTexturePatch.pixelData = pixelData;
        atmosphericTextureEntry.handle = skyTextureAsset.getHandle();
        try {
            api.updateTextureFromMemory(atmosphericTextureUpdate);
        } finally {
            // The data is often a view into a mapped readback the compositor reuses or unmaps;
            // keep no reference to it past the call that reads it.
            atmosphericTexturePatch.pixelData = null;
        }
    }

    private void publishCombinedSkyAmbient(
            RemixAPI api,
            float sunHeight,
            WeatherDomeState weather,
            float stormOpacity) {
        // The lightning flash follows the full storm: with volumetric clouds the static dome is
        // thinned, but the clouds the flash lights are there all the same.
        float multiplier = skyAmbientMultiplier(sunHeight)
                + OvercastSky.extraAmbient(
                        weather.opacity(), LightManager.celestialVisibility(sunHeight))
                + LightningFlash.skyAmbientBoost(stormOpacity);
        publishSkyAmbient(
                api,
                lerp(atmosphericMeanR, OVERCAST_MEAN_R * weather.intensity(), weather.opacity())
                        * multiplier,
                lerp(atmosphericMeanG, OVERCAST_MEAN_G * weather.intensity(), weather.opacity())
                        * multiplier,
                lerp(atmosphericMeanB, OVERCAST_MEAN_B * weather.intensity(), weather.opacity())
                        * multiplier,
                SunShafts.ambientVolumetricScale(sunHeight, weather.opacity()));
    }

    /** Updates the sky ambient rect to this radiance and draws it; a black or disabled rect stays undrawn. */
    private void publishSkyAmbient(RemixAPI api, float radianceR, float radianceG, float radianceB,
                                   float volumetricRadianceScale) {
        if (!skyAmbientRectEnabled
                || (radianceR == 0.0f && radianceG == 0.0f && radianceB == 0.0f)) {
            return;
        }

        LightInfoRectEXT rectExt = LightInfoRectEXT.builder()
                .position(0.0f, SKY_AMBIENT_RECT_Y, 0.0f)
                .xAxis(1, 0, 0)
                .xSize(SKY_AMBIENT_RECT_HALF_SIZE)
                .yAxis(0, 0, 1)
                .ySize(SKY_AMBIENT_RECT_HALF_SIZE)
                .direction(0, -1, 0)
                .volumetricRadianceScale(volumetricRadianceScale)
                .build();

        LightInfo ambientInfo = LightInfo.builder()
                .radiance(radianceR, radianceG, radianceB)
                .rectExt(rectExt)
                .build();

        if (skyAmbientRectLight != null && skyAmbientRectLight.isAlive()) {
            RemixLightStore.get().update(skyAmbientRectLight, ambientInfo);
        } else {
            destroyAsset(skyAmbientRectLight);
            skyAmbientRectLight = new RemixLight(ambientInfo, null);
            RemixLightStore.get().create(skyAmbientRectLight);
        }
        if (skyAmbientRectLight.isAlive()) {
            skyAmbientRectLight.draw(api);
        }
    }

    static float skyAmbientMultiplier(float sunHeight) {
        float nightFactor = 1.0f - LightManager.celestialVisibility(sunHeight);
        return dayAmbientBoost * (1.0f - nightFactor) + nightAmbientBoost * nightFactor;
    }

    private void renderCelestialMeshes(
            RemixAPI api,
            ClientLevel world,
            Vec3 cameraPos,
            float skyAngle,
            float tickDelta,
            float[] sunDirection,
            float weatherOpacity) {
        if (!ensureCelestialAssets()) {
            return;
        }

        float weatherVisibility = Math.max(
                minimumCelestialWeatherVisibility, 1.0f - clamp01(weatherOpacity));
        float starBrightness = WorldSky.starBrightness(world, tickDelta);
        if (!drawStarryNight(api, world, cameraPos, skyAngle, starBrightness, weatherVisibility)) {
            float starsOpacity = starOpacity(starBrightness) * weatherVisibility;
            if (starsOpacity > 0.0f) {
                updateStarOpacity(starsOpacity);
                updateStarTransform(starTransform, cameraPos, skyAngle);
                drawCelestialMesh(api, starMeshAsset, starTransform);
            }
        }

        celestialMoonDirection[0] = -sunDirection[0];
        celestialMoonDirection[1] = -sunDirection[1];
        celestialMoonDirection[2] = -sunDirection[2];
        // Sky billboards are composited separately from scene meshes; both cloud layers must
        // attenuate their visible discs, even where a near mesh already shadows the ground.
        float sunOpacity = celestialBodyOpacity(sunDirection[1]) * weatherVisibility
                * VolumetricClouds.sunDiscTransmittance();
        float moonOpacity = celestialBodyOpacity(celestialMoonDirection[1]) * weatherVisibility
                * VolumetricClouds.moonDiscTransmittance();
        boolean roundSun = DaylightSky.isRoundSun();
        updateCelestialOpacity(roundSun ? 0.0f : sunOpacity, moonOpacity);

        if (roundSun) {
            updateRoundSun(api, sunDirection[1], sunOpacity);
            if (sunOpacity > 0.0f) {
                updateCelestialTransform(sunTransform, cameraPos, sunDirection, RoundSun.HALF_SIZE);
                drawCelestialMesh(api, roundSunMeshAsset, sunTransform);
            }
        } else if (sunOpacity > 0.0f) {
            updateCelestialTransform(sunTransform, cameraPos, sunDirection, SUN_HALF_SIZE);
            drawCelestialMesh(api, sunMeshAsset, sunTransform);
        }

        if (moonOpacity > 0.0f) {
            int moonPhase = WorldSky.moonPhase(world, tickDelta) & 7;
            updateCelestialTransform(
                    moonTransform,
                    cameraPos,
                    celestialMoonDirection,
                    MOON_HALF_SIZE);
            drawCelestialMesh(api, moonMeshAssets[moonPhase], moonTransform);
        }
    }

    /**
     * Re-tints the round sun with the sunlight that reaches the ground when that colour moved,
     * and fades its material with the sun's opacity. Both are cheap no-ops on most frames.
     */
    private void updateRoundSun(RemixAPI api, float sunHeight, float opacity) {
        AtmosphereModel.discTransmittance(
                sunHeight, ROUND_SUN_TRANSMITTANCE_RADIUS, roundSunTransmittance);
        float red = roundSunTransmittance[0];
        float green = roundSunTransmittance[1];
        float blue = roundSunTransmittance[2];
        if (RoundSun.colorChanged(roundSunColor, red, green, blue)) {
            RoundSun.fillTexture(roundSunPixels, red, green, blue);
            roundSunTexturePatch.pixelData = roundSunPixels;
            roundSunTextureEntry.handle = roundSunTextureAsset.getHandle();
            api.updateTextureFromMemory(roundSunTextureUpdate);
            roundSunColor[0] = red;
            roundSunColor[1] = green;
            roundSunColor[2] = blue;
        }
        if (Math.abs(opacity - lastRoundSunOpacity) > CELESTIAL_OPACITY_UPDATE_DELTA) {
            RemixMaterialStore.get().update(
                    roundSunMaterialAsset,
                    createCelestialMaterialInfo(
                            roundSunTextureAsset, RoundSun.DISC_RADIANCE, opacity, FILTER_LINEAR));
            lastRoundSunOpacity = opacity;
        }
    }

    /**
     * Draws the Community Edition night sky in place of vanilla's stars.
     *
     * @return whether it drew, so vanilla's stars stay as the fallback
     */
    private boolean drawStarryNight(
            RemixAPI api,
            ClientLevel world,
            Vec3 cameraPos,
            float skyAngle,
            float starBrightness,
            float weatherVisibility) {
        if (!CommunityLook.isStarryNight()) {
            return false;
        }
        float stars = NightSkyRenderer.starOpacity(starBrightness) * weatherVisibility;
        if (stars <= 0.0f) {
            return true;
        }
        updateStarTransform(starTransform, cameraPos, skyAngle);
        boolean drawn = nightSky.draw(api, starTransform, celestialBlendPNext, stars,
                NightSkyRenderer.bandOpacity(stars, WorldSky.moonSize(world, 1.0f)));
        if (drawn) {
            nightSky.drawMeteors(api, cameraPos.x, cameraPos.y, cameraPos.z, celestialBlendPNext,
                    stars, world.getDefaultClockTime() / 24000L);
        }
        return drawn;
    }

    private boolean ensureCelestialAssets() {
        if (starMeshAsset != null && sunMeshAsset != null && roundSunMeshAsset != null
                && moonMeshAssets[0] != null && celestialBlendPNext != 0) {
            return true;
        }
        if (celestialInitializationFailed) {
            return false;
        }

        destroyCelestialAssets();
        try {
            sunTextureAsset = loadCelestialTexture(SUN_TEXTURE);
            moonTextureAsset = loadCelestialTexture(MOON_PHASES_TEXTURE);
            sunMaterialAsset = createCelestialMaterial(sunTextureAsset, SUN_RADIANCE);
            moonMaterialAsset = createCelestialMaterial(
                    moonTextureAsset, NightLight.moonDiscRadiance(MOON_RADIANCE));
            starMaterialAsset = createStarMaterial();

            sunMeshAsset = createCelestialMesh(sunMaterialAsset, 0.0f, 0.0f, 1.0f, 1.0f);

            int roundSunBytes = RoundSun.TEXTURE_SIZE * RoundSun.TEXTURE_SIZE * 4;
            ByteBuffer initialRoundSun = DirectBufferPool.acquire(roundSunBytes);
            RoundSun.fillTexture(initialRoundSun, 1.0f, 1.0f, 1.0f);
            roundSunTextureAsset = RemixTextureStore.get().create(new RemixTexture(
                    RoundSun.TEXTURE_SIZE,
                    RoundSun.TEXTURE_SIZE,
                    TextureFormat.RGBA8_UNORM,
                    TexturePurpose.COLOR,
                    initialRoundSun));
            roundSunPixels = ByteBuffer.allocateDirect(roundSunBytes).order(ByteOrder.nativeOrder());
            roundSunColor[0] = 1.0f;
            roundSunColor[1] = 1.0f;
            roundSunColor[2] = 1.0f;
            roundSunMaterialAsset = RemixMaterialStore.get().create(new RemixMaterial(
                    createCelestialMaterialInfo(
                            roundSunTextureAsset, RoundSun.DISC_RADIANCE, 1.0f, FILTER_LINEAR),
                    List.of(roundSunTextureAsset),
                    null));
            roundSunMeshAsset = createCelestialMesh(roundSunMaterialAsset, 0.0f, 0.0f, 1.0f, 1.0f);
            lastRoundSunOpacity = 1.0f;

            for (int phase = 0; phase < moonMeshAssets.length; phase++) {
                int column = phase % 4;
                int row = phase / 4;
                float minU = column / 4.0f;
                float minV = row / 2.0f;
                float maxU = (column + 1) / 4.0f;
                float maxV = (row + 1) / 2.0f;
                moonMeshAssets[phase] = createCelestialMesh(
                        moonMaterialAsset,
                        minU,
                        minV,
                        maxU,
                        maxV);
            }

            starMeshAsset = createStarMesh(starMaterialAsset, getVanillaStarVertices());
            celestialBlendPNext = CELESTIAL_BLEND.allocNative(0);
            lastSunOpacity = 1.0f;
            lastMoonOpacity = 1.0f;
            lastStarOpacity = 1.0f;
            return true;
        } catch (RuntimeException | IOException e) {
            LOGGER.error("Failed to create celestial SKY meshes", e);
            destroyCelestialAssets();
            celestialInitializationFailed = true;
            return false;
        }
    }

    private RemixTexture loadCelestialTexture(Identifier textureId) throws IOException {
        var client = Minecraft.getInstance();
        var resource = client.getResourceManager().getResource(textureId)
                .orElseThrow(() -> new IOException("Missing texture " + textureId));

        byte[] pixels;
        int width;
        int height;
        try (NativeImage image = NativeImage.read(resource.open())) {
            width = image.getWidth();
            height = image.getHeight();
            pixels = new byte[width * height * 4];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int abgr = NativeImagePixels.abgr(image, x, y);
                    int index = (y * width + x) * 4;
                    pixels[index] = (byte) (abgr & 0xFF);
                    pixels[index + 1] = (byte) ((abgr >>> 8) & 0xFF);
                    pixels[index + 2] = (byte) ((abgr >>> 16) & 0xFF);
                    pixels[index + 3] = (byte) ((abgr >>> 24) & 0xFF);
                }
            }
        }

        ByteBuffer pixelBuffer = DirectBufferPool.acquire(pixels.length);
        pixelBuffer.put(pixels).flip();
        RemixTexture texture = new RemixTexture(
                width,
                height,
                TextureFormat.RGBA8_UNORM,
                TexturePurpose.COLOR,
                pixelBuffer);
        return RemixTextureStore.get().create(texture);
    }

    private static RemixMaterial createCelestialMaterial(RemixTexture texture, float radiance) {
        MaterialInfo info = createCelestialMaterialInfo(texture, radiance, 1.0f, FILTER_NEAREST);
        RemixMaterial material = new RemixMaterial(info, List.of(texture), null);
        return RemixMaterialStore.get().create(material);
    }

    private static RemixMaterial createStarMaterial() {
        RemixMaterial material = new RemixMaterial(createStarMaterialInfo(1.0f), List.of(), null);
        return RemixMaterialStore.get().create(material);
    }

    private static MaterialInfo createCelestialMaterialInfo(
            RemixTexture texture,
            float radiance,
            float opacity,
            int filterMode) {
        MaterialInfoOpaqueEXT opaque = MaterialInfoOpaqueEXT.builder()
                .albedoConstant(opacity, opacity, opacity)
                .opacityConstant(opacity)
                .roughnessConstant(1.0f)
                .metallicConstant(0.0f)
                .useDrawCallAlphaState(true)
                .build();
        return MaterialInfo.builder()
                .albedoTexture(texture.getHandle())
                .emissiveTexture(texture.getHandle())
                .emissiveIntensity(radiance * opacity)
                .filterMode(filterMode)
                .wrapMode(MaterialInfo.WRAP_MODE_CLAMP, MaterialInfo.WRAP_MODE_CLAMP)
                .opaqueExt(opaque)
                .build();
    }

    private void updateCelestialOpacity(float sunOpacity, float moonOpacity) {
        RemixMaterialStore materials = RemixMaterialStore.get();
        if (Math.abs(sunOpacity - lastSunOpacity) > CELESTIAL_OPACITY_UPDATE_DELTA) {
            materials.update(
                    sunMaterialAsset,
                    createCelestialMaterialInfo(sunTextureAsset, SUN_RADIANCE, sunOpacity, FILTER_NEAREST));
            lastSunOpacity = sunOpacity;
        }
        if (Math.abs(moonOpacity - lastMoonOpacity) > CELESTIAL_OPACITY_UPDATE_DELTA) {
            materials.update(
                    moonMaterialAsset,
                    createCelestialMaterialInfo(
                            moonTextureAsset, NightLight.moonDiscRadiance(MOON_RADIANCE), moonOpacity,
                            FILTER_NEAREST));
            lastMoonOpacity = moonOpacity;
        }
    }

    private void updateStarOpacity(float opacity) {
        if (Math.abs(opacity - lastStarOpacity) <= CELESTIAL_OPACITY_UPDATE_DELTA) {
            return;
        }
        RemixMaterialStore.get().update(starMaterialAsset, createStarMaterialInfo(opacity));
        lastStarOpacity = opacity;
    }

    private static MaterialInfo createStarMaterialInfo(float opacity) {
        MaterialInfoOpaqueEXT opaque = MaterialInfoOpaqueEXT.builder()
                .albedoConstant(opacity, opacity, opacity)
                .opacityConstant(opacity)
                .roughnessConstant(1.0f)
                .metallicConstant(0.0f)
                .useDrawCallAlphaState(true)
                .build();
        return MaterialInfo.builder()
                .emissiveColorConstant(1.0f, 1.0f, 1.0f)
                .emissiveIntensity(STAR_RADIANCE * opacity)
                .opaqueExt(opaque)
                .build();
    }

    private static RemixMesh createCelestialMesh(
            RemixMaterial material,
            float minU,
            float minV,
            float maxU,
            float maxV) {
        HardcodedVertex[] vertices = {
                HardcodedVertex.create(-1, -1, 0, 0, 0, -1, minU, maxV, 0xFFFFFFFF),
                HardcodedVertex.create(1, -1, 0, 0, 0, -1, maxU, maxV, 0xFFFFFFFF),
                HardcodedVertex.create(1, 1, 0, 0, 0, -1, maxU, minV, 0xFFFFFFFF),
                HardcodedVertex.create(-1, 1, 0, 0, 0, -1, minU, minV, 0xFFFFFFFF)
        };
        RemixMeshSurface surface = new RemixMeshSurface(material)
                .setVertices(vertices)
                .setIndices(new int[]{0, 1, 2, 0, 2, 3});
        RemixMesh mesh = new RemixMesh(new RemixMeshSurface[]{surface});
        return RemixMeshStore.get().create(mesh);
    }

    private static RemixMesh createStarMesh(RemixMaterial material, float[] positions) {
        int vertexCount = positions.length / 3;
        if (vertexCount == 0 || vertexCount % 4 != 0) {
            throw new IllegalStateException("Minecraft star buffer must contain position-only quads");
        }

        HardcodedVertex[] vertices = new HardcodedVertex[vertexCount];
        for (int vertex = 0; vertex < vertexCount; vertex++) {
            int position = vertex * 3;
            vertices[vertex] = HardcodedVertex.create(
                    positions[position],
                    positions[position + 1],
                    positions[position + 2]);
        }

        int[] indices = new int[vertexCount / 4 * 6];
        for (int quad = 0; quad < vertexCount / 4; quad++) {
            int vertex = quad * 4;
            int index = quad * 6;
            indices[index] = vertex;
            indices[index + 1] = vertex + 1;
            indices[index + 2] = vertex + 2;
            indices[index + 3] = vertex;
            indices[index + 4] = vertex + 2;
            indices[index + 5] = vertex + 3;
        }

        RemixMeshSurface surface = new RemixMeshSurface(material)
                .setVertices(vertices)
                .setIndices(indices);
        return RemixMeshStore.get().create(new RemixMesh(new RemixMeshSurface[]{surface}));
    }

    private static float[] getVanillaStarVertices() {
        var worldRenderer = Minecraft.getInstance().levelRenderer;
        if (!(worldRenderer instanceof WorldRendererStarAccess starAccess)) {
            throw new IllegalStateException("WorldRenderer star capture is unavailable");
        }
        return starAccess.rtxremix$copyStarVertices();
    }

    private void drawCelestialMesh(RemixAPI api, RemixMesh mesh, Transform transform) {
        api.drawInstance(
                mesh.getHandle(),
                transform.matrix,
                InstanceCategoryBit.SKY,
                1,
                celestialBlendPNext);
    }

    private static InstanceInfoBlendEXT createCelestialBlend() {
        InstanceInfoBlendEXT blend = InstanceInfoBlendEXT.textureTimesVertexColorAlphaTest(1);
        blend.alphaBlendEnabled = 1;
        blend.srcColorBlendFactor = InstanceInfoBlendEXT.VK_BLEND_FACTOR_ONE;
        blend.dstColorBlendFactor = InstanceInfoBlendEXT.VK_BLEND_FACTOR_ONE;
        blend.srcAlphaBlendFactor = InstanceInfoBlendEXT.VK_BLEND_FACTOR_ZERO;
        blend.dstAlphaBlendFactor = InstanceInfoBlendEXT.VK_BLEND_FACTOR_ONE;
        return blend;
    }

    private static void updateCelestialTransform(
            Transform transform,
            Vec3 cameraPos,
            float[] direction,
            float halfSize) {
        float dx = direction[0];
        float dy = direction[1];
        float dz = direction[2];
        float inverseDirectionLength = inverseLength(dx, dy, dz);
        dx *= inverseDirectionLength;
        dy *= inverseDirectionLength;
        dz *= inverseDirectionLength;

        float rx = dz;
        float ry = 0.0f;
        float rz = -dx;
        float inverseRightLength = inverseLength(rx, ry, rz);
        rx *= inverseRightLength;
        rz *= inverseRightLength;

        float ux = dy * rz - dz * ry;
        float uy = dz * rx - dx * rz;
        float uz = dx * ry - dy * rx;

        transform.matrix[0] = rx * halfSize;
        transform.matrix[1] = ux * halfSize;
        transform.matrix[2] = dx;
        transform.matrix[3] = (float) cameraPos.x + dx * CELESTIAL_DISTANCE;
        transform.matrix[4] = ry * halfSize;
        transform.matrix[5] = uy * halfSize;
        transform.matrix[6] = dy;
        transform.matrix[7] = (float) cameraPos.y + dy * CELESTIAL_DISTANCE;
        transform.matrix[8] = rz * halfSize;
        transform.matrix[9] = uz * halfSize;
        transform.matrix[10] = dz;
        transform.matrix[11] = (float) cameraPos.z + dz * CELESTIAL_DISTANCE;
    }

    private static void updateStarTransform(
            Transform transform,
            Vec3 cameraPos,
            float skyAngle) {
        Quaternionf rotation = new Quaternionf()
                .rotateX((float) Math.toRadians(SUN_PATH_ROTATION_DEG))
                .rotateY(-(float) Math.PI / 2.0f)
                .rotateX(skyAngle * (float) (Math.PI * 2.0));
        Transform rotated = Transform.fromTRS(
                (float) cameraPos.x,
                (float) cameraPos.y,
                (float) cameraPos.z,
                rotation,
                STAR_DISTANCE_SCALE);
        System.arraycopy(rotated.matrix, 0, transform.matrix, 0, transform.matrix.length);
    }

    private static float inverseLength(float x, float y, float z) {
        return 1.0f / (float) Math.sqrt(x * x + y * y + z * z);
    }

    public void reloadResources() {
        destroyCelestialAssets();
        celestialInitializationFailed = false;
    }

    private void destroyCelestialAssets() {
        CELESTIAL_BLEND.freeNative();
        celestialBlendPNext = 0;
        destroyMeshes();
        destroyAsset(sunMaterialAsset);
        destroyAsset(roundSunMaterialAsset);
        destroyAsset(roundSunTextureAsset);
        roundSunMaterialAsset = null;
        roundSunTextureAsset = null;
        roundSunTexturePatch.pixelData = null;
        roundSunColor[0] = -1.0f;
        lastRoundSunOpacity = -1.0f;
        destroyAsset(moonMaterialAsset);
        destroyAsset(starMaterialAsset);
        destroyAsset(sunTextureAsset);
        destroyAsset(moonTextureAsset);
        sunMaterialAsset = null;
        moonMaterialAsset = null;
        starMaterialAsset = null;
        sunTextureAsset = null;
        moonTextureAsset = null;
        lastSunOpacity = -1.0f;
        lastMoonOpacity = -1.0f;
        lastStarOpacity = -1.0f;
    }

    private void destroyMeshes() {
        destroyAsset(sunMeshAsset);
        sunMeshAsset = null;
        destroyAsset(roundSunMeshAsset);
        roundSunMeshAsset = null;
        for (int i = 0; i < moonMeshAssets.length; i++) {
            destroyAsset(moonMeshAssets[i]);
            moonMeshAssets[i] = null;
        }
        destroyAsset(starMeshAsset);
        starMeshAsset = null;
        nightSky.destroy();
        nightSky.resetFailure();
    }

    private static void destroyAsset(RemixAssetBase asset) {
        if (asset != null && !asset.isDeleted()) {
            asset.destroy();
        }
    }

    private static float smoothstep(float edge0, float edge1, float value) {
        float t = Math.max(0.0f, Math.min(1.0f, (value - edge0) / (edge1 - edge0)));
        return t * t * (3.0f - 2.0f * t);
    }

    /**
     * Which band of the held cloud readback goes up next, and the per-buffer band views.
     *
     * <p>The native upload reads a direct buffer from its base address, ignoring its position,
     * so a band needs a buffer that starts at the band: a slice. Slicing allocates, and the
     * compositor hands back the same few mapped buffers for its whole life, so each buffer's
     * views are built the first time it is seen and reused after; the frame path allocates
     * nothing once every ring slot has been seen. Pure bookkeeping, no Remix or Vulkan calls.
     */
    static final class DomeBandScan {
        static final int BANDS = 4;

        private final ByteBuffer[] cachedSources = new ByteBuffer[CloudSkyCompositor.READBACK_RING_SIZE];
        private final ByteBuffer[][] cachedViews =
                new ByteBuffer[CloudSkyCompositor.READBACK_RING_SIZE][BANDS];
        private int cacheWidth;
        private int cacheHeight;
        private int nextCacheSlot;

        private ByteBuffer[] views;
        private int width;
        private int height;
        private int cursor;
        private int remaining;

        /** First row of a band; {@code band == BANDS} gives the height, so bands tile every row. */
        static int bandStartRow(int height, int band) {
            return (int) ((long) height * band / BANDS);
        }

        /**
         * Points the scan at a new image and owes every band again, continuing from the band
         * the scan had reached so the bands still due under the older image go first.
         */
        void retarget(ByteBuffer source, int width, int height) {
            if (width != cacheWidth || height != cacheHeight) {
                forgetViews();
                cacheWidth = width;
                cacheHeight = height;
            }
            views = viewsFor(source);
            this.width = width;
            this.height = height;
            remaining = BANDS;
        }

        boolean active() {
            return remaining > 0;
        }

        int width() { return width; }
        int height() { return height; }

        /** The band to upload now, counting it as done; only valid while {@link #active()}. */
        int nextBand() {
            if (remaining <= 0) {
                throw new IllegalStateException("No dome band scan is active");
            }
            int band = cursor;
            cursor = (cursor + 1) % BANDS;
            remaining--;
            return band;
        }

        /** The current image's view of one band. */
        ByteBuffer bandView(int band) {
            return views[band];
        }

        /** Ends the scan and drops its image, keeping the cached views for the next one. */
        void stop() {
            remaining = 0;
            views = null;
        }

        /**
         * Ends any scan and drops every cached view, for when the buffers they slice are
         * unmapped or the image size changes.
         */
        void forgetViews() {
            stop();
            for (int slot = 0; slot < cachedSources.length; slot++) {
                cachedSources[slot] = null;
                Arrays.fill(cachedViews[slot], null);
            }
            nextCacheSlot = 0;
        }

        private ByteBuffer[] viewsFor(ByteBuffer source) {
            for (int slot = 0; slot < cachedSources.length; slot++) {
                if (cachedSources[slot] == source) {
                    return cachedViews[slot];
                }
            }
            // The cache has a slot per readback buffer, so with one compositor's buffers this
            // replaces only empty slots; a new compositor comes with forgetViews().
            int slot = nextCacheSlot;
            nextCacheSlot = (nextCacheSlot + 1) % cachedSources.length;
            int rowBytes = rowBytes(cacheWidth);
            ByteBuffer[] slotViews = cachedViews[slot];
            for (int band = 0; band < BANDS; band++) {
                int start = bandStartRow(cacheHeight, band);
                int end = bandStartRow(cacheHeight, band + 1);
                // Absolute slice: independent of the position and limit the compositor moves.
                slotViews[band] = source.slice(start * rowBytes, (end - start) * rowBytes);
            }
            cachedSources[slot] = source;
            return slotViews;
        }

        private static int rowBytes(int texels) {
            return texels * 4 * Short.BYTES;
        }
    }

    record WeatherDomeState(float opacity, float intensity) {
        private static final WeatherDomeState CLEAR = new WeatherDomeState(0.0f, 0.0f);
    }

    static WeatherDomeState weatherDomeState(
            float rainStrength,
            float thunderStrength,
            float rainIntensity,
            float thunderIntensity) {
        float rain = clamp01(rainStrength);
        float thunder = clamp01(thunderStrength);
        float opacity = lerp(rain, 1.0f, thunder);
        float premultipliedIntensity = lerp(
                rain * rainIntensity,
                thunderIntensity,
                thunder);
        float intensity = opacity > 0.0f ? premultipliedIntensity / opacity : 0.0f;
        return new WeatherDomeState(opacity, intensity);
    }

    static float celestialBodyOpacity(float verticalDirection) {
        return smoothstep(CELESTIAL_HORIZON_FADE_MIN_HEIGHT, 0.0f, verticalDirection);
    }

    static float starOpacity(float vanillaStarBrightness) {
        return clamp01(vanillaStarBrightness);
    }

    private static float lerp(float from, float to, float amount) {
        return from + (to - from) * clamp01(amount);
    }

    private static float clamp01(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    // ====================================================================
    //  Sun direction (shared with LightManager)
    // ====================================================================

    /**
     * Computes the sun's position direction from Minecraft's {@code skyAngle},
     * with the standard shader-pack tilt applied.
     *
     * @param skyAngle {@code world.getSkyAngle(tickDelta)}: 0.0 at noon, 0.25 at sunset, etc.
     * @return float[3] {x, y, z} normalised sun position direction
     */
    public static float[] computeSunDirection(float skyAngle) {
        return LightManager.computeSunDirection(skyAngle);
    }

    // ====================================================================
    //  Cleanup
    // ====================================================================

    /** Releases world-scoped Remix assets while preserving reusable atmospheric Vulkan resources. */
    public void cleanup() {
        destroyCelestialAssets();
        releaseSkyLights();
        currentWorld = null;

        RemixAPI api = renderer.getRemixApi();
        if (overcastTextureHandle != 0 && api != null) {
            try {
                api.destroyTexture(overcastTextureHandle);
            } catch (RuntimeException e) {
                LOGGER.warn("Failed to destroy overcast dome texture: {}", e.getMessage());
            }
        }
        overcastTextureHandle = 0;
        overcastInitializationFailed = false;
        celestialInitializationFailed = false;
    }

    /**
     * Releases the dome, overcast, and ambient lights with the sky texture they read, so the
     * next frame publishes them from scratch for whatever sky the current world has.
     */
    private void releaseSkyLights() {
        atmosphericGenerator.invalidate();
        destroyAsset(skyDomeLightAsset);
        skyDomeLightAsset = null;
        destroyAsset(skyTextureAsset);
        skyTextureAsset = null;
        skyTextureWidth = 0;
        skyTextureHeight = 0;
        publishedDomeTextureHandle = 0L;
        domeShowsClouds = false;
        stopCloudDomeScan();
        if (cloudCompositor != null) {
            cloudCompositor.invalidate();
        }
        atmosphericTexturePatch.pixelData = null;
        destroyAsset(overcastDomeLightAsset);
        overcastDomeLightAsset = null;
        destroyAsset(skyAmbientRectLight);
        skyAmbientRectLight = null;
        lastWeatherOpacity = -1f;
        lastWeatherIntensity = -1f;
        lastSkyDomeMultiplier = Float.NaN;
        atmosphericMeanR = 0.0f;
        atmosphericMeanG = 0.0f;
        atmosphericMeanB = 0.0f;
    }

    /** Releases world assets and process-lifetime atmospheric Vulkan resources. */
    public void shutdown() {
        try {
            cleanup();
        } finally {
            destroyCloudCompositor();
            atmosphericGenerator.destroy();
        }
    }
}
