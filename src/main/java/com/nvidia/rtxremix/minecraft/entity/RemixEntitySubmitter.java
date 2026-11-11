/*
 * SPDX-FileCopyrightText: Copyright (c) 2024-2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
 * SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * Modified by RTX Remix for Minecraft Community Edition; see COMMUNITY.md.
 */
package com.nvidia.rtxremix.minecraft.entity;

import com.nvidia.rtxremix.minecraft.util.DirectBufferPool;
import com.nvidia.rtxremix.minecraft.texture.ParticleAtlasManager;
import com.nvidia.rtxremix.minecraft.texture.LabPBRConverter;
import com.nvidia.rtxremix.minecraft.texture.DynamicTextureCache;
import com.nvidia.rtxremix.minecraft.texture.BlockTextureManager;
import com.nvidia.rtxremix.minecraft.texture.AtlasAnimationForwarder;
import com.nvidia.rtxremix.minecraft.mixin.SpriteContentsAccessor;
import com.nvidia.rtxremix.minecraft.profiling.ProfileScope;
import com.nvidia.rtxremix.minecraft.mixin.SpriteAtlasAccessor;
import com.nvidia.rtxremix.minecraft.block.CopperBeaconBlockEntity;
import com.nvidia.rtxremix.minecraft.asset.RemixTextureStore;
import com.nvidia.rtxremix.minecraft.asset.RemixTexture;
import com.nvidia.rtxremix.minecraft.asset.RemixMeshSurface;
import com.nvidia.rtxremix.minecraft.asset.RemixMeshStore;
import com.nvidia.rtxremix.minecraft.asset.RemixMesh;
import com.nvidia.rtxremix.minecraft.asset.RemixMaterialStore;
import com.nvidia.rtxremix.minecraft.asset.RemixMaterial;
import com.nvidia.rtxremix.minecraft.asset.RemixInstanceStore;
import com.nvidia.rtxremix.minecraft.asset.RemixInstance;
import com.mojang.blaze3d.platform.NativeImage;
import com.nvidia.rtxremix.HardcodedVertex;
import com.nvidia.rtxremix.InstanceCategoryBit;
import com.nvidia.rtxremix.InstanceInfoBlendEXT;
import com.nvidia.rtxremix.MaterialInfo;
import com.nvidia.rtxremix.MaterialInfoOpaqueEXT;
import com.nvidia.rtxremix.MaterialInfoOpaqueSubsurfaceEXT;
import com.nvidia.rtxremix.MeshInfoSurfaceTriangles;
import com.nvidia.rtxremix.RemixAPI;
import com.nvidia.rtxremix.RemixException;
import com.nvidia.rtxremix.TextureBatchEntry;
import com.nvidia.rtxremix.TextureFormat;
import com.nvidia.rtxremix.TexturePatch;
import com.nvidia.rtxremix.TexturePatchInfo;
import com.nvidia.rtxremix.TexturePurpose;
import com.nvidia.rtxremix.Transform;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntArrays;
import it.unimi.dsi.fastutil.ints.IntComparator;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * Converts {@link RemixEntityPass.EntityCaptureResult} into RTX Remix API calls.
 *
 * <p>An owner is an entity or a block entity, compared by identity. Each owner may
 * produce multiple {@link RemixVertexConsumerProvider.CapturedLayer} objects (base
 * model, clothing overlay, eyes, etc.). Each captured cuboid or
 * baked-item pose is submitted as rigid geometry: its local vertices live in a
 * pooled mesh and its vanilla pivot matrix becomes the retained instance transform.
 * Minecraft does not deform these vertices, so using Remix skinning here only adds
 * CPU bookkeeping and makes instance motion history harder to track.
 *
 * <p>Caches map logical keys to store-managed asset types:
 * <ul>
 *   <li>{@code rigidMeshPool}, {@link RemixMesh} keyed by texture, render phase, shell layer and
 *   local geometry: a cuboid by its identity and the sprite it was painted with, a canvas's
 *   pattern stack by its cuboid and every layer's sprite and tint in paint order, anything
 *   else by a hash of its vertices</li>
 *   <li>{@code materialCache}, {@link RemixMaterial} keyed by texture and render-phase behavior</li>
 *   <li>{@code textureSets}, owned albedo and LabPBR channel textures keyed by texture identifier</li>
 * </ul>
 *
 * <p>The mesh pool is shared across owners and frames. Identical cuboids or item
 * geometry reuse the same Remix mesh object while every occurrence gets its own
 * retained scene handle. The handle is updated with the current mesh and rigid
 * transform, then removed when that occurrence is no longer submitted. Parts of
 * a layer whose geometry is streamed afresh every frame bypass the pool: each
 * frame's mesh is transient, held on a list likewise shared across owners, and
 * destroyed once every instance has moved off it.
 */
public class RemixEntitySubmitter {

    private static final Logger LOGGER = LoggerFactory.getLogger(RemixEntitySubmitter.class);

    /**
     * Block units each face of a part stands off the shell layer below it; see
     * {@link #shellLayerFor} and {@link #shellOffsets}.
     */
    static final float SHELL_OFFSET = 0.0005f;
    /**
     * Depth steps for text, in the text's own pixel units: a sign pixel is 0.0104 blocks,
     * a name tag pixel 0.025. Vanilla draws text as coplanar quads in painter's order. A
     * glyph cell is wider than its advance, so its transparent padding lies over the next
     * glyph's ink, and glowing text stacks eight outline copies under the ink on a second
     * layer. A ray tracer resolves coplanar hits arbitrarily, so draw order becomes depth:
     * each quad steps ahead of the one before it along its normal, and each text layer
     * steps past every quad the layer below could hold.
     */
    private static final float TEXT_QUAD_STEP = 1f / 2048f;
    private static final float TEXT_LAYER_STEP =
            TEXT_QUAD_STEP * (RemixCapturingConsumer.MAX_VERTICES / 4);
    private static final float POM_DISPLACE_IN = 0.25f;
    private static final float EMISSIVE_INTENSITY = 100.0f;
    private static final float COPPER_BEACON_LENS_EMISSION_SCALE = 100.0f;
    private static final float THIN_SSS_MEASUREMENT_DISTANCE = 0.75f;
    private static final int DEFAULT_CUTOUT_ALPHA_REFERENCE = 1;
    private static final int COVERAGE_ALPHA_REFERENCE = 128;
    private static final Identifier BANNER_PATTERNS_ATLAS_TEXTURE =
            Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/banner_patterns.png");
    private static final Identifier SHIELD_PATTERNS_ATLAS_TEXTURE =
            Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/shield_patterns.png");
    private static final Identifier PARTICLE_ATLAS_ID = TextureAtlas.LOCATION_PARTICLES;
    private static final Identifier BEDS_ATLAS_TEXTURE =
            Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/beds.png");
    private static final Identifier MAP_DECORATIONS_ATLAS_TEXTURE =
            Identifier.fromNamespaceAndPath("minecraft", "textures/atlas/map_decorations.png");
    private static final String DYNAMIC_MAP_TEXTURE_PREFIX = "dynamic/map/";
    private static final EntityMaterialKey BEDS_ATLAS_MATERIAL_KEY =
            new EntityMaterialKey(BEDS_ATLAS_TEXTURE, RenderLayerUtils.SurfaceMode.OPAQUE);
    static final InstanceInfoBlendEXT VERTEX_COLOR_MODULATE =
            InstanceInfoBlendEXT.textureTimesVertexColor();
    static final InstanceInfoBlendEXT BANNER_PATTERN_BLEND =
            InstanceInfoBlendEXT.textureTimesVertexColorTextureAlphaBlend(1);
    /** Fewest vertices a triangle strip needs before it closes a triangle. */
    private static final int STRIP_MIN_VERTICES = 3;
    /**
     * Albedo for layers that carry no texture. Remix modulates it by vertex
     * colour, so a white texel leaves the vertex colour as the whole surface.
     */
    private static final byte[] UNTEXTURED_WHITE_PIXEL = {
            (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
    private static final int DEFAULT_MAX_MESH_CACHE_ENTRIES = 8192;
    private static final int MAX_MESH_CACHE_ENTRIES = Math.max(128,
            Integer.getInteger("rtxremix.entity.meshCache.maxEntries", DEFAULT_MAX_MESH_CACHE_ENTRIES));
    private static final Predicate<RetainedEntityPart> PART_REUSABLE =
            part -> !part.instance.isDeletedFast();

    private final RemixAPI api;

    /**
     * Material cache key. Overrides hashCode so the stored record and the
     * {@link EntityMaterialLookupKey} probe share one explicit formula. A record's
     * generated hash is unspecified; a probe pinned to it would miss every lookup
     * if that changed, and a miss here drops the layer with no error raised.
     */
    record EntityMaterialKey(Identifier textureId, RenderLayerUtils.SurfaceMode surfaceMode) {
        @Override
        public int hashCode() {
            return hash(textureId, surfaceMode);
        }

        static int hash(Identifier textureId, RenderLayerUtils.SurfaceMode surfaceMode) {
            return 31 * Objects.hashCode(textureId) + Objects.hashCode(surfaceMode);
        }
    }

    /**
     * Mutable probe for allocation-free materialCache lookups in the steady state.
     * equals compares against the immutable {@link EntityMaterialKey} records stored
     * in the map; hashCode is the record's own helper.
     */
    static final class EntityMaterialLookupKey {
        private Identifier textureId;
        private RenderLayerUtils.SurfaceMode surfaceMode;

        EntityMaterialLookupKey set(Identifier textureId, RenderLayerUtils.SurfaceMode surfaceMode) {
            this.textureId = textureId;
            this.surfaceMode = surfaceMode;
            return this;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof EntityMaterialKey e
                    && Objects.equals(textureId, e.textureId())
                    && surfaceMode == e.surfaceMode();
        }

        @Override
        public int hashCode() {
            return EntityMaterialKey.hash(textureId, surfaceMode);
        }
    }

    sealed interface RigidMeshKey permits HashedMeshKey, CuboidMeshKey, CanvasMeshKey, RigidMeshLookupKey {
        Identifier texId();
    }

    /**
     * Geometry identified by hashing the captured vertices.
     *
     * <p>{@code topology} is a component because a strip and a run of quads can
     * agree on every other field and still be different geometry.
     *
     * <p>{@code surfaceMode} is a component because the material is baked into
     * the pooled mesh and atlasSurfaceMode narrows item parts one at a time, so
     * two parts of one layer can share a geometry hash and need different materials.
     */
    record HashedMeshKey(Identifier texId, RenderLayerUtils.SurfaceMode surfaceMode,
                         RenderLayerUtils.Topology topology,
                         int shellLayer, int vertexCount, long geometryHash)
            implements RigidMeshKey {
        @Override
        public int hashCode() {
            return RigidMeshLookupKey.hashedHash(
                    texId, surfaceMode, topology, shellLayer, vertexCount, geometryHash);
        }
    }

    /**
     * Geometry identified by the {@code ModelPart.Cuboid} it was baked from and the
     * sprite it was painted with.
     *
     * <p>{@code sides} is a key component for its <em>identity</em>, not its
     * contents: every field it reaches is final and written once at bake time, so
     * one array reference means one fixed set of positions, normals and UVs. It is
     * compared by reference and hashed by identity on purpose. Do not "fix" it to
     * Arrays.equals or Arrays.hashCode: a model reload produces fresh arrays, and
     * content equality would then alias a reloaded cuboid onto the stale mesh built
     * from the old one.
     *
     * <p>{@code sprite} is the atlas sprite a {@code SpriteTexturedVertexConsumer}
     * painted the cuboid with, null for a cuboid drawn without one. The mesh bakes
     * the sprite's UV rectangle, and one cuboid is baked once and painted with many
     * sprites (chest variants, sixteen bed colours, pot sherds, a canvas base), so
     * the mesh is per cuboid and sprite. Identity again: an atlas re-stitch creates
     * new TextureAtlasSprite objects, and {@code invalidateMaterialTexture} drops every mesh
     * keyed on that atlas before a stale one could be reached through them.
     *
     * <p>The other components are the things proven to vary for one cuboid within a
     * single frame. {@code texId} separates iron from diamond armour on the shared
     * armour model. {@code surfaceMode} varies per entity via playerSkinSurfaceMode.
     * {@code shellLayer} scales the shell offset baked into the vertices, so dropping
     * it collapses a dyed-leather overlay onto the body.
     *
     * <p>Tint is deliberately NOT part of the key. It rides the instance as a
     * texture-factor blend, so one white mesh serves every recolour of a cuboid
     * and an animating tint (jeb_ sheep, warden fade, wet wolves) is a cheap
     * per-instance update instead of a native mesh create per part per frame.
     */
    record CuboidMeshKey(Identifier texId, RenderLayerUtils.SurfaceMode surfaceMode,
                         int shellLayer, ModelPart.Polygon[] sides, TextureAtlasSprite sprite)
            implements RigidMeshKey {
        @Override
        public boolean equals(Object other) {
            return other instanceof CuboidMeshKey key
                    && Objects.equals(texId, key.texId)
                    && surfaceMode == key.surfaceMode
                    && shellLayer == key.shellLayer
                    && sides == key.sides
                    && sprite == key.sprite;
        }

        @Override
        public int hashCode() {
            return RigidMeshLookupKey.cuboidHash(texId, surfaceMode, shellLayer, sides, sprite);
        }
    }

    /**
     * A canvas's pattern stack: one flag or plate cuboid painted once per pattern, every
     * layer in paint order with its own sprite rectangle, tint and shell depth, baked into
     * a single mesh.
     *
     * <p>One mesh rather than one per pattern because the runtime orders the coplanar
     * decals a ray crosses by primitive index, not by depth. Separate single-cuboid meshes
     * carry identical primitive indices, so their layers tie and composite in traversal
     * order; one mesh keeps them in paint order. {@code shellLayer} is the stack's base
     * depth: layer k sits {@code k + 1} shells beyond it. {@code sides} is identity, as in
     * {@link CuboidMeshKey}; {@code sprites} are identities layer by layer; {@code tints}
     * are values, baked as vertex colour.
     */
    record CanvasMeshKey(Identifier texId, RenderLayerUtils.SurfaceMode surfaceMode,
                         int shellLayer, ModelPart.Polygon[] sides, TextureAtlasSprite[] sprites, int[] tints)
            implements RigidMeshKey {
        @Override
        public boolean equals(Object other) {
            if (!(other instanceof CanvasMeshKey key)
                    || !Objects.equals(texId, key.texId)
                    || surfaceMode != key.surfaceMode
                    || shellLayer != key.shellLayer
                    || sides != key.sides
                    || sprites.length != key.sprites.length
                    || !Arrays.equals(tints, key.tints)) {
                return false;
            }
            for (int i = 0; i < sprites.length; i++) {
                if (sprites[i] != key.sprites[i]) return false;
            }
            return true;
        }

        @Override
        public int hashCode() {
            int result = RigidMeshLookupKey.canvasHashPrefix(texId, surfaceMode, shellLayer, sides);
            for (int i = 0; i < sprites.length; i++) {
                result = RigidMeshLookupKey.foldCanvasLayer(result, sprites[i], tints[i]);
            }
            return result;
        }
    }

    /**
     * Allocation-free probe for rigid-mesh pool hits. The pool stores only the
     * immutable records; this key exists to ask "is it there?" without minting a
     * record per part per frame, and {@link #immutableCopy()} materialises the
     * storable form on the rare miss. Its hashCode and equals mirror the records'
     * explicit hash helpers, which is why the records override hashCode at all.
     */
    static final class RigidMeshLookupKey implements RigidMeshKey {
        private Identifier texId;
        private RenderLayerUtils.SurfaceMode surfaceMode;
        private int shellLayer;
        private RenderLayerUtils.Topology topology;
        private int vertexCount;
        private long geometryHash;
        private ModelPart.Polygon[] sides;
        private TextureAtlasSprite sprite;
        private int layerHashPrefix;
        private int hashCode;

        private enum Form { CUBOID, HASHED, CANVAS }

        private Form form;
        /** The canvas form reads its layers' sprites and tints straight from the captured parts. */
        private RemixCapturingConsumer canvasSource;
        private int canvasFirstPart;
        private int canvasPatternCount;

        void setLayer(Identifier texId, RenderLayerUtils.SurfaceMode surfaceMode,
                      int shellLayer) {
            this.texId = texId;
            this.surfaceMode = surfaceMode;
            this.shellLayer = shellLayer;
            this.layerHashPrefix = layerHash(texId, surfaceMode, shellLayer);
        }

        void setCuboid(ModelPart.Polygon[] sides, TextureAtlasSprite sprite) {
            this.form = Form.CUBOID;
            this.sides = sides;
            this.sprite = sprite;
            this.vertexCount = 0;
            this.geometryHash = 0L;
            this.hashCode = cuboidHash(layerHashPrefix, sides, sprite);
        }

        void setHashed(RenderLayerUtils.Topology topology, int vertexCount, long geometryHash) {
            this.form = Form.HASHED;
            this.topology = topology;
            this.vertexCount = vertexCount;
            this.geometryHash = geometryHash;
            this.sides = null;
            this.sprite = null;
            this.hashCode = hashedHash(layerHashPrefix, topology, vertexCount, geometryHash);
        }

        /**
         * Probes for a canvas's pattern stack: parts {@code firstPart} to
         * {@code firstPart + patternCount} of {@code source}, all painting {@code sides}.
         * The parts are read through the probe until the next set, so the source must
         * stay untouched while this probe is in use.
         */
        void setCanvas(ModelPart.Polygon[] sides, RemixCapturingConsumer source,
                       int firstPart, int patternCount) {
            this.form = Form.CANVAS;
            this.sides = sides;
            this.sprite = null;
            this.vertexCount = 0;
            this.geometryHash = 0L;
            this.canvasSource = source;
            this.canvasFirstPart = firstPart;
            this.canvasPatternCount = patternCount;
            int result = canvasHashPrefix(layerHashPrefix, sides);
            for (int i = 0; i < patternCount; i++) {
                int part = firstPart + i;
                result = foldCanvasLayer(result, source.getPartSprite(part), source.getPartTint(part));
            }
            this.hashCode = result;
        }

        RigidMeshKey immutableCopy() {
            return switch (form) {
                case CUBOID -> new CuboidMeshKey(texId, surfaceMode, shellLayer, sides, sprite);
                case HASHED -> new HashedMeshKey(texId, surfaceMode, topology, shellLayer,
                        vertexCount, geometryHash);
                case CANVAS -> {
                    TextureAtlasSprite[] sprites = new TextureAtlasSprite[canvasPatternCount];
                    int[] tints = new int[canvasPatternCount];
                    for (int i = 0; i < canvasPatternCount; i++) {
                        int part = canvasFirstPart + i;
                        sprites[i] = canvasSource.getPartSprite(part);
                        tints[i] = canvasSource.getPartTint(part);
                    }
                    yield new CanvasMeshKey(texId, surfaceMode, shellLayer, sides, sprites, tints);
                }
            };
        }

        @Override
        public Identifier texId() {
            return texId;
        }

        @Override
        public int hashCode() {
            return hashCode;
        }

        @Override
        public boolean equals(Object other) {
            return switch (form) {
                case CUBOID -> other instanceof CuboidMeshKey key
                        && Objects.equals(texId, key.texId())
                        && surfaceMode == key.surfaceMode()
                        && shellLayer == key.shellLayer()
                        && sides == key.sides()
                        && sprite == key.sprite();
                case HASHED -> other instanceof HashedMeshKey key
                        && Objects.equals(texId, key.texId())
                        && surfaceMode == key.surfaceMode()
                        && topology == key.topology()
                        && shellLayer == key.shellLayer()
                        && vertexCount == key.vertexCount()
                        && geometryHash == key.geometryHash();
                case CANVAS -> other instanceof CanvasMeshKey key && equalsCanvas(key);
            };
        }

        private boolean equalsCanvas(CanvasMeshKey key) {
            if (!Objects.equals(texId, key.texId())
                    || surfaceMode != key.surfaceMode()
                    || shellLayer != key.shellLayer()
                    || sides != key.sides()
                    || canvasPatternCount != key.sprites().length) {
                return false;
            }
            for (int i = 0; i < canvasPatternCount; i++) {
                int part = canvasFirstPart + i;
                if (canvasSource.getPartSprite(part) != key.sprites()[i]
                        || canvasSource.getPartTint(part) != key.tints()[i]) {
                    return false;
                }
            }
            return true;
        }

        static int cuboidHash(Identifier texId, RenderLayerUtils.SurfaceMode surfaceMode,
                              int shellLayer, ModelPart.Polygon[] sides, TextureAtlasSprite sprite) {
            return cuboidHash(layerHash(texId, surfaceMode, shellLayer), sides, sprite);
        }

        private static int cuboidHash(int layerHashPrefix, ModelPart.Polygon[] sides, TextureAtlasSprite sprite) {
            int result = 31 * layerHashPrefix + System.identityHashCode(sides);
            return 31 * result + System.identityHashCode(sprite);
        }

        static int hashedHash(Identifier texId, RenderLayerUtils.SurfaceMode surfaceMode,
                              RenderLayerUtils.Topology topology, int shellLayer,
                              int vertexCount, long geometryHash) {
            return hashedHash(layerHash(texId, surfaceMode, shellLayer),
                    topology, vertexCount, geometryHash);
        }

        private static int hashedHash(int layerHashPrefix, RenderLayerUtils.Topology topology,
                                      int vertexCount, long geometryHash) {
            int result = 31 * layerHashPrefix + topology.ordinal();
            result = 31 * result + vertexCount;
            return 31 * result + Long.hashCode(geometryHash);
        }

        static int canvasHashPrefix(Identifier texId, RenderLayerUtils.SurfaceMode surfaceMode,
                                    int shellLayer, ModelPart.Polygon[] sides) {
            return canvasHashPrefix(layerHash(texId, surfaceMode, shellLayer), sides);
        }

        private static int canvasHashPrefix(int layerHashPrefix, ModelPart.Polygon[] sides) {
            return 31 * layerHashPrefix + System.identityHashCode(sides);
        }

        /** Folds one canvas layer, the sprite by identity and the tint by value, into a running hash. */
        static int foldCanvasLayer(int result, TextureAtlasSprite sprite, int tint) {
            return 31 * (31 * result + System.identityHashCode(sprite)) + tint;
        }

        private static int layerHash(Identifier texId,
                                     RenderLayerUtils.SurfaceMode surfaceMode,
                                     int shellLayer) {
            int result = Objects.hashCode(texId);
            result = 31 * result + Objects.hashCode(surfaceMode);
            return 31 * result + shellLayer;
        }
    }

    private static final class RetainedEntityPart {
        final RemixInstance instance;
        final Transform transform = new Transform();
        RigidMeshKey meshKey;

        RetainedEntityPart(RemixInstance instance, RigidMeshKey meshKey) {
            this.instance = instance;
            this.meshKey = meshKey;
        }
    }

    /** Pool entry: the mesh plus its recency stamp, so eviction can skip current-frame entries. */
    private static final class PooledMesh {
        final RemixMesh mesh;
        long lastUsedFrame;

        PooledMesh(RemixMesh mesh, long lastUsedFrame) {
            this.mesh = mesh;
            this.lastUsedFrame = lastUsedFrame;
        }
    }

    /**
     * Stable, bounded pool of rigid local-space meshes shared across frames and entities.
     * Access-ordered: {@link #evictColdRigidMeshesIfNeeded()} evicts in iteration order, so
     * the ordering is what makes it least-recently-used rather than arbitrary. The relink on
     * each pool hit swaps pointers and allocates nothing.
     */
    private final LinkedHashMap<RigidMeshKey, PooledMesh> rigidMeshPool =
            new LinkedHashMap<>(256, 0.75f, true);
    /**
     * Meshes built this frame for parts whose geometry is streamed afresh every frame, and
     * last frame's, which live until {@link #retireTransientMeshes()} has seen every instance
     * move off them. See {@link #createTransientMesh}.
     */
    private List<RemixMesh> transientMeshes = new ArrayList<>();
    private List<RemixMesh> previousTransientMeshes = new ArrayList<>();
    /**
     * Live scene instances keyed by owner identity and part slot, independent
     * of the pooled mesh they reference. The owner is whatever game object a
     * capture front-end names in its {@link RemixEntityPass.EntityCaptureResult},
     * compared by identity: a recycled numeric entity ID can never inherit
     * another owner's native state, and owners of unrelated types never collide.
     */
    private final RetainedEntityPartRegistry<Object, RetainedEntityPart> retainedEntityParts =
            new RetainedEntityPartRegistry<>();
    /** Whether a capture window is open; see {@link #beginFrame()}. */
    private boolean frameOpen = false;
    private RemixMesh pendingPartMesh;
    private RigidMeshKey pendingPartKey;
    private final Supplier<RetainedEntityPart> PENDING_PART_FACTORY = this::createPendingRetainedPart;
    private final EntityMaterialLookupKey materialLookupKey = new EntityMaterialLookupKey();
    private final Map<EntityMaterialKey, RemixMaterial> materialCache = new HashMap<>();
    /**
     * A placed beacon's lens material is independently mutable, so its one-surface
     * mesh cannot participate in the global rigid mesh pool.
     */
    private final IdentityHashMap<CopperBeaconBlockEntity, BeaconLensAssets>
            beaconLensAssets = new IdentityHashMap<>();
    private final Map<Identifier, EntityTextureSet> textureSets = new HashMap<>();
    private final Map<Identifier, TextureSize> dynamicTextureSizes = new HashMap<>();
    private final Map<Identifier, Long> dynamicTextureVersions = new HashMap<>();
    private final Set<Identifier> failedTextures = new HashSet<>();
    /**
     * Texture IDs absent from the resource packs. Only the pack lookup is memoized, not the
     * whole texture set: a downloaded skin also misses the resource manager and arrives later
     * through {@link DynamicTextureCache}, so treating an absent resource as a permanent
     * failure would strand it. Resource presence changes only on a reload, which clears this.
     */
    private final Set<Identifier> resourceMissing = new HashSet<>();
    private LabPBRConverter.VariantCache labPBRVariants;

    /**
     * Kill switch for off-thread texture-set loading: {@code -Drtxremix.entityTextures.sync=true}
     * builds every entity texture set on the render thread the frame it is first needed, as
     * before. Off-thread loading moves the PNG reads, LabPBR decodes, and channel conversion of
     * a newly seen texture onto {@link #textureLoader}; the price is that the new texture's
     * layers are missing for the few frames until its set lands, the same gap a downloading
     * skin already has.
     */
    static final boolean SYNC_ENTITY_TEXTURES = Boolean.getBoolean("rtxremix.entityTextures.sync");
    /**
     * Texture sets turned into Remix textures per frame. A set is up to eight full-size
     * uploads, so a burst of new entity types, such as walking into a village, spreads over
     * a few frames rather than landing in one.
     */
    private static final int MAX_TEXTURE_SETS_PER_FRAME = 2;
    /** Stops a frame's texture-set creation early once this much time has gone into it. */
    private static final long TEXTURE_SET_FRAME_BUDGET_NANOS = 2_000_000L;
    /**
     * Texture sets being prepared or waiting to be created at once. Each holds its channels
     * on the heap until created, so this also caps the memory a burst can pin.
     */
    private static final int MAX_TEXTURE_SETS_IN_FLIGHT = 8;
    /**
     * Prepares texture sets off the render thread. Only CPU data crosses back: every Remix
     * texture and material is still created here, on the render thread, by
     * {@link #createLoadedTextureSets}.
     */
    private final EntityTextureLoader<Identifier, PreparedTextureSet> textureLoader =
            EntityTextureLoader.withDaemonThread(MAX_TEXTURE_SETS_IN_FLIGHT, "RTX Remix entity textures");
    /** Held so the per-frame drain passes a sink without allocating one. */
    private final EntityTextureLoader.Sink<Identifier, PreparedTextureSet> loadedTextureSink =
            this::acceptLoadedTextureSet;
    /**
     * The loader thread's LabPBR variant index and the loader generation it was built for.
     * The index walks every pack, so the loader builds it once per generation when the render
     * thread has none to lend, and hands it back with its result for the render thread to adopt.
     */
    private volatile LoaderVariants loaderVariants;

    private record LoaderVariants(long generation, LabPBRConverter.VariantCache cache) {}

    /** Albedo decoded from a resource pack, as tightly packed RGBA bytes. */
    private record DecodedImage(byte[] pixels, int width, int height) {}

    /**
     * The render-thread-side state of a runtime-stitched atlas: its stitched albedo and
     * where each sprite sits. Captured on the render thread because the sprites' images
     * are freed by a resource reload there; the LabPBR work that follows reads only this.
     */
    private record AtlasSnapshot(byte[] pixels, int width, int height,
                                 Identifier[] spriteIds, int[] spriteRects, boolean[] animated) {}

    // A captured layer with no material is dropped silently, which is
    // indistinguishable from an entity that simply is not there. Most gaps are
    // transient though: the block atlas has no materials until
    // BlockTextureManager initializes, and a player skin has none until its
    // async download lands. Reporting the first drop would fire on every
    // healthy launch, so only a key that stays materialless across a full
    // window is treated as a fault.
    private static final long MISSING_MATERIAL_WARN_FRAMES = 600;
    private final Map<EntityMaterialKey, Long> missingMaterialSince = new HashMap<>();
    private final Set<EntityMaterialKey> warnedMissingMaterials = new HashSet<>();

    private static RemixEntitySubmitter activeSubmitter;

    private final ParticleAtlasManager particleAtlasManager;

    private static final class EntityTextureSet {
        RemixTexture albedo;
        RemixTexture normal;
        RemixTexture height;
        RemixTexture roughness;
        RemixTexture metallic;
        RemixTexture emissive;
        RemixTexture sssThickness;
        RemixTexture sssAlbedo;
        boolean hasPBR;
        final List<RemixTexture> ownedTextures = new ArrayList<>();
        AtlasAnimationForwarder.Target animation;

        void own(RemixTexture texture) {
            if (texture != null) ownedTextures.add(texture);
        }

        void destroy() {
            if (animation != null) {
                animation.close();
                animation = null;
            }
            for (RemixTexture texture : ownedTextures) {
                if (texture != null && !texture.isDeleted()) texture.destroy();
            }
            ownedTextures.clear();
        }
    }

    /**
     * Every channel of an entity texture set as heap data, before any Remix texture
     * exists. Built by {@link #preparePlainTextureSet} or {@link #prepareAtlasTextureSet},
     * on the loader thread or inline, and turned into an {@link EntityTextureSet} on the
     * render thread by {@link #uploadPreparedTextureSet}. A null channel is not uploaded.
     */
    private static final class PreparedTextureSet {
        /** The texture id for a plain set, {@code atlas_<id>} for an atlas; names the uploads. */
        final String name;
        final boolean atlas;
        final byte[] albedo;
        final int width;
        final int height;
        byte[] normal;
        byte[] heightMap;
        byte[] roughness;
        byte[] metallic;
        byte[] emissive;
        byte[] sssThickness;
        byte[] sssAlbedo;
        boolean hasPBR;
        int spriteCount;
        Set<Identifier> spriteIds = Set.of();
        Map<String, BlockTextureManager.AnimatedSpriteLabPBR> animationPbr = Map.of();
        /** The {@link DynamicTextureCache} version the albedo was copied from, or 0. */
        long dynamicVersion;
        /** The variant index the loader used, offered to the render thread to adopt. */
        LabPBRConverter.VariantCache variants;

        PreparedTextureSet(String name, boolean atlas, byte[] albedo, int width, int height) {
            this.name = name;
            this.atlas = atlas;
            this.albedo = albedo;
            this.width = width;
            this.height = height;
        }

        /** The upload's name, in the form each kind of set has always logged under. */
        String channelName(String channel) {
            return atlas ? name + "_" + channel : "entity_" + channel + "_" + name;
        }
    }

    private static final class BeaconLensAssets {
        final RemixMaterial material;
        final RemixMesh mesh;
        final ModelPart.Polygon[] sides;
        final TextureAtlasSprite sprite;
        float intensityMultiplier;
        long lastSeenFrame;

        BeaconLensAssets(
                RemixMaterial material,
                RemixMesh mesh,
                ModelPart.Polygon[] sides,
                TextureAtlasSprite sprite,
                float intensityMultiplier,
                long lastSeenFrame) {
            this.material = material;
            this.mesh = mesh;
            this.sides = sides;
            this.sprite = sprite;
            this.intensityMultiplier = intensityMultiplier;
            this.lastSeenFrame = lastSeenFrame;
        }
    }

    private int lastDrawCount;
    private int lastMeshCreateCount;
    /** Frame clock shared with retained-part stamping; owned by the registry. */
    private long frameIndex() {
        return retainedEntityParts.frameIndex();
    }

    // Reusable Transform objects to avoid per-part, per-frame allocations.
    private final Transform reusableWorldTransform = new Transform();
    private final Transform reusableRigidTransform = new Transform();

    // Cuboid parts carry no captured vertices, so a pool miss rebuilds the
    // geometry here from the immutable ModelPart.Cuboid. Misses run at ~0.2 per
    // frame, so the round trip costs nothing. recordParts is false: this
    // consumer never needs part records of its own.
    private final RemixCapturingConsumer rebuildScratch =
            new RemixCapturingConsumer(null, false);

    /**
     * @param particleAtlasManager owner of the particle atlas material, which the
     *                             held-item flame layer borrows
     */
    public RemixEntitySubmitter(RemixAPI api, ParticleAtlasManager particleAtlasManager) {
        this.api = api;
        this.particleAtlasManager = particleAtlasManager;
        activeSubmitter = this;
    }

    public int getLastDrawCount() { return lastDrawCount; }
    public int getLastMeshCreateCount() { return lastMeshCreateCount; }
    /** Pooled rigid meshes currently retained; watches identity keying's dedupe rate. */
    public int getPoolSize() { return rigidMeshPool.size(); }

    /**
     * Opens the frame. Idempotent, because in-place entity capture opens it from
     * the start of vanilla's world render while {@code renderAndSubmitFrame}
     * still opens it for the frames that reach no entity. Retained parts are
     * stamped with the registry's frame index, so the frame must not advance
     * between a part being submitted and {@link #endFrame} deciding what to
     * retire; advancing it twice would retire every instance captured in place.
     */
    public void beginFrame() {
        if (frameOpen) {
            return;
        }
        frameOpen = true;
        retainedEntityParts.beginFrame();
        lastDrawCount = 0;
        lastMeshCreateCount = 0;
        createLoadedTextureSets();
    }

    /**
     * Creates the Remix textures for texture sets the loader has finished preparing, within
     * the per-frame budget. Runs as the frame opens, before any capture asks for a material,
     * so a set that lands here is used the same frame. A frame with nothing finished costs
     * one empty-queue check and allocates nothing.
     */
    private void createLoadedTextureSets() {
        if (!textureLoader.hasCompleted()) return;
        try (ProfileScope ignored = ProfileScope.begin("Entity createTextureSets")) {
            textureLoader.drain(MAX_TEXTURE_SETS_PER_FRAME, TEXTURE_SET_FRAME_BUDGET_NANOS,
                    System::nanoTime, loadedTextureSink);
        }
    }

    /**
     * Retires entity parts not reached this frame, releases the transient meshes nothing
     * references any more, then trims unreferenced pooled meshes.
     */
    public void endFrame() {
        if (!frameOpen) {
            return;
        }
        // The frame closes even when a native destroy fails: leaving it open would make
        // every later beginFrame a no-op and every later endFrame throw.
        try {
            retainedEntityParts.endFrame(RemixEntitySubmitter::destroyRetainedPart);
            retireUnseenBeaconLensAssets();
            retireTransientMeshes();
            evictColdRigidMeshesIfNeeded();
        } finally {
            frameOpen = false;
        }
    }

    /**
     * Destroys last frame's transient meshes and holds this frame's for one more. A transient
     * part's instance references last frame's mesh until this frame either updates it to a
     * new one or the retirement sweep removes the instance, so once that sweep has run nothing
     * in the scene refers to the previous set.
     */
    private void retireTransientMeshes() {
        destroyMeshes(previousTransientMeshes);
        List<RemixMesh> emptied = previousTransientMeshes;
        previousTransientMeshes = transientMeshes;
        transientMeshes = emptied;
    }

    private static void destroyMeshes(List<RemixMesh> meshes) {
        for (RemixMesh mesh : meshes) {
            if (!mesh.isDeletedFast()) mesh.destroy();
        }
        meshes.clear();
    }

    private static void destroyRetainedPart(RetainedEntityPart part) {
        if (!part.instance.isDeletedFast()) part.instance.destroy();
    }

    private void retireUnseenBeaconLensAssets() {
        BlockTextureManager textures = BlockTextureManager.get();
        Iterator<BeaconLensAssets> iterator = beaconLensAssets.values().iterator();
        while (iterator.hasNext()) {
            BeaconLensAssets assets = iterator.next();
            if (assets.lastSeenFrame == frameIndex()) {
                continue;
            }
            iterator.remove();
            destroyBeaconLensAssets(textures, assets);
        }
    }

    private static void destroyBeaconLensAssets(
            BlockTextureManager textures, BeaconLensAssets assets) {
        if (!assets.mesh.isDeletedFast()) {
            assets.mesh.destroy();
        }
        if (textures != null) {
            textures.destroyEmissionScaledAtlasMaterial(assets.material);
        } else if (!assets.material.isDeleted()) {
            assets.material.destroy();
        }
    }

    private void clearBeaconLensAssets() {
        BlockTextureManager textures = BlockTextureManager.get();
        for (BeaconLensAssets assets : beaconLensAssets.values()) {
            destroyBeaconLensAssets(textures, assets);
        }
        beaconLensAssets.clear();
    }

    // --- per-entity submission ---

    /**
     * Submits an entity with a render-only root translation. Entity state and
     * captured rigid-part transforms remain in their authoritative positions.
     */
    public void submitEntity(RemixEntityPass.EntityCaptureResult result, Vec3 renderOffset) {
        Transform worldTransform = buildWorldTransform(result.worldPos().add(renderOffset));
        submitEntity(result, worldTransform, baseCategoryFlags(result), NO_EXTRA_CATEGORIES);
    }

    /**
     * Submits a captured owner at its world position. The base categories come
     * from the capture itself; {@code policy} adds the owner's per-part categories.
     */
    void submitEntity(RemixEntityPass.EntityCaptureResult result, PartCategoryPolicy policy) {
        Transform worldTransform = buildWorldTransform(result.worldPos());
        submitEntity(result, worldTransform, baseCategoryFlags(result), policy);
    }

    /**
     * Submits captured geometry under an explicit object-to-world root and base
     * category set. Every submit ends here: the world-position overloads derive
     * the root and base categories from the capture, and a caller that owns its
     * root, such as the camera-attached view model, calls it directly.
     *
     * <p>Every part carries {@code baseCategoryFlags}; {@code policy} adds the
     * categories the owner wants for a part's canvas role, so one submit path
     * serves owners that want their canvas patterns as decals and owners that
     * do not.
     *
     * <p>A failure in any part retires every part the owner holds before it
     * propagates, so a caller that catches it sees the owner absent for the frame
     * rather than half drawn; the next successful submit rebuilds it from scratch.
     */
    void submitEntity(RemixEntityPass.EntityCaptureResult result,
                      Transform worldTransform,
                      int baseCategoryFlags,
                      PartCategoryPolicy policy) {
        if (result.layers().isEmpty()) return;

        collectCanvasBases(result);
        // The layer carries the ordinal it was assigned when the entity first
        // requested it. Counting position in this list instead would shift every
        // later layer's shell offset, and therefore its mesh key, on any frame
        // where an earlier layer happened to emit nothing.
        try {
            for (RemixVertexConsumerProvider.CapturedLayer layer : result.layers()) {
                submitLayer(result, layer, worldTransform, baseCategoryFlags, policy, layer.layerIndex());
            }
        } catch (RuntimeException | Error failure) {
            // Parts submitted before the failure already sit at this frame's transform
            // and parts after it were never reached. Retiring all of them keeps the
            // failure whole instead of leaving a partial owner in the scene.
            retainedEntityParts.discardOwner(result.owner(), RemixEntitySubmitter::destroyRetainedPart);
            throw failure;
        }
    }

    private void submitLayer(RemixEntityPass.EntityCaptureResult result,
                             RemixVertexConsumerProvider.CapturedLayer layer,
                             Transform worldTransform,
                             int baseCategoryFlags,
                             PartCategoryPolicy policy,
                             int layerIndex) {
        Identifier texId = layer.textureId();
        if (texId == null) return;
        RenderType renderLayer = layer.renderLayer();
        RenderLayerUtils.SurfaceMode layerMode = RenderLayerUtils.surfaceMode(renderLayer);
        RenderLayerUtils.SurfaceMode surfaceMode = playerSkinSurfaceMode(layerMode,
                result.owner() instanceof Player
                        && renderLayer == net.minecraft.client.renderer.rendertype.RenderTypes.entityTranslucent(texId));

        RemixCapturingConsumer consumer = layer.consumer();
        // Guard on parts, not vertices: a cuboid part carries no captured
        // vertices, so a layer of nothing but cuboids has vertexCount 0 while
        // still holding every mob in the scene.
        if (consumer.getPartCount() == 0) return;

        long frame = frameIndex();
        if (dynamicTextureCheckFrames.put(texId, frame) != frame) {
            refreshDynamicTexture(texId);
        }

        // Parts are recorded during capture: a quad layer closes a part as its
        // quads close, folding the geometry hash while each quad is still hot, and
        // a strip layer closes one wherever the pivot changes, folding the run once
        // its normals are final. A quad whose four vertices disagree on a pivot
        // ends its run and is dropped there. Each part narrows its own surface
        // mode, so the material is resolved per part where the mesh is built
        // rather than once for the layer.
        canvasReader.beginLayer(texId, layerMode);
        // A canvas's patterns are consecutive parts of one run and become one mesh, so
        // they are held back until the run ends; see submitCanvasPatterns.
        int canvasFirstPart = 0;
        int canvasPatternCount = 0;
        int textureCategoryFlags = additionalCategoryFlagsForTexture(texId)
                | (renderLayer.pipeline() == net.minecraft.client.renderer.RenderPipelines.CRUMBLING
                        ? InstanceCategoryBit.DECAL_STATIC : 0);
        int patternCategoryFlags = baseCategoryFlags | textureCategoryFlags
                | policy.categoryFlags(PatternCanvasRole.PATTERN);
        for (int part = 0, partCount = consumer.getPartCount(); part < partCount; part++) {
            ModelPart.Polygon[] sides = consumer.getPartSides(part);
            int bone = consumer.getPartBone(part);
            // Canvas roles are for cuboid parts, which arrive white with an instance
            // tint. A hashed part on a pattern atlas carries its colour in its
            // vertices and needs neither the canvas blend nor the canvas depth.
            PatternCanvasRole role = sides == null
                    ? PatternCanvasRole.NONE
                    : canvasReader.next(sides, bone);
            if (role == PatternCanvasRole.PATTERN) {
                if (canvasPatternCount == 0) canvasFirstPart = part;
                canvasPatternCount++;
                continue;
            }
            if (canvasPatternCount > 0) {
                submitCanvasPatterns(result, consumer, canvasFirstPart, canvasPatternCount,
                        texId, surfaceMode, layerIndex, worldTransform, patternCategoryFlags);
                canvasPatternCount = 0;
            }
            if (canvasReader.isBacking(role, sides, bone)) continue;
            submitRigidPart(result, consumer, part, texId,
                    atlasSurfaceMode(texId, surfaceMode, consumer.getPartBlockItemLayer(part)),
                    layer.topology(), layer.transientGeometry(), layerIndex,
                    shellLayerFor(layerIndex, canvasReader.depth(), role), role,
                    worldTransform, baseCategoryFlags | textureCategoryFlags
                            | policy.categoryFlags(role));
        }
        if (canvasPatternCount > 0) {
            submitCanvasPatterns(result, consumer, canvasFirstPart, canvasPatternCount,
                    texId, surfaceMode, layerIndex, worldTransform, patternCategoryFlags);
        }
    }

    /**
     * Narrows a block-atlas surface mode to what the geometry actually is.
     *
     * <p>Minecraft draws ordinary opaque items through its translucent item
     * layer, so on the atlas a captured TRANSLUCENT means "an item" far more
     * often than it means "a translucent block". Taking it at face value would
     * make apples out of glass. Only geometry the capture path saw come from a
     * translucent block keeps it, and everything else falls back to the cutout
     * treatment the atlas has always used. Every part narrows through here, so
     * the material cache holds one interpretation of an atlas mode.
     *
     * <p>Clear glass goes the other way: vanilla draws it as a cutout, and terrain
     * gives it its own refracting layer, so geometry from a clear-glass block item
     * takes {@link RenderLayerUtils.SurfaceMode#GLASS} whatever it was captured as.
     *
     * @param blockItemLayer the terrain layer of the block item the geometry came
     *                       from, or {@link EntityRenderContext#NO_BLOCK_ITEM}
     */
    static RenderLayerUtils.SurfaceMode atlasSurfaceMode(Identifier texId,
                                                         RenderLayerUtils.SurfaceMode surfaceMode,
                                                         int blockItemLayer) {
        if (!BlockTextureManager.BLOCK_ATLAS_ID.equals(texId)
                && !TextureAtlas.LOCATION_ITEMS.equals(texId)) {
            return surfaceMode;
        }
        if (blockItemLayer == BlockTextureManager.L_GLASS) {
            return RenderLayerUtils.SurfaceMode.GLASS;
        }
        if (surfaceMode != RenderLayerUtils.SurfaceMode.TRANSLUCENT
                || blockItemLayer == BlockTextureManager.L_TRANS) {
            return surfaceMode;
        }
        return RenderLayerUtils.SurfaceMode.CUTOUT;
    }

    /**
     * The terrain atlas material a narrowed block-atlas surface mode borrows, so a
     * held block shades as its placed counterpart does: ice refracts like ice, clear
     * glass like clear glass, and everything else takes the atlas cutout material.
     */
    static int atlasLayerType(RenderLayerUtils.SurfaceMode surfaceMode) {
        return switch (surfaceMode) {
            case TRANSLUCENT -> BlockTextureManager.L_TRANS;
            case GLASS -> BlockTextureManager.L_GLASS;
            default -> BlockTextureManager.L_CUTOUT;
        };
    }

    /**
     * Vanilla submits the complete player model through an entity-translucent
     * layer so its outer skin may use alpha. Treating that entire layer as a
     * blended Remix surface sends even the opaque body through the unordered
     * translucency path and makes it appear ghosted. Preserve texture alpha as
     * a cutout instead.
     */
    static RenderLayerUtils.SurfaceMode playerSkinSurfaceMode(
            RenderLayerUtils.SurfaceMode capturedMode, boolean vanillaPlayerSkinLayer) {
        return vanillaPlayerSkinLayer && capturedMode == RenderLayerUtils.SurfaceMode.TRANSLUCENT
                ? RenderLayerUtils.SurfaceMode.CUTOUT
                : capturedMode;
    }

    /**
     * Where a cuboid sits on a banner or shield canvas.
     *
     * <p>Vanilla paints a canvas by rendering one flag or plate cuboid once per
     * layer, all through the pattern atlas: an untinted pass on the solid layer as a
     * depth backing, then the dyed base and each pattern in authored order on the
     * translucent no-outline layer. The pole, crossbar and shield handle share the
     * solid pass. The roles name those positions so the blend, the shell depth and
     * the backing skip are each decided from one value.
     */
    enum PatternCanvasRole {
        /** Not on a pattern atlas. */
        NONE,
        /** Pattern atlas, solid layer: pole, bar, handle, or the untinted backing pass. */
        FRAME,
        /** Pattern atlas, translucent layer, first cuboid: the dyed base. */
        BASE,
        /** Pattern atlas, translucent layer, later cuboids: one pattern each, baked together into one canvas mesh. */
        PATTERN
    }

    /**
     * The categories an owner adds to a part for its canvas role, OR-ed into the
     * part's base flags. Category is the owner's choice, not the geometry's: the
     * same pattern cuboid is a static decal on a placed banner and plain geometry
     * on one carried in a hand, so the capture front-end supplies the policy.
     */
    @FunctionalInterface
    interface PartCategoryPolicy {
        int categoryFlags(PatternCanvasRole role);
    }

    /** Adds nothing: every part keeps the capture's base categories. */
    static final PartCategoryPolicy NO_EXTRA_CATEGORIES = role -> 0;

    /**
     * Reads the canvases out of one entity's captured parts.
     *
     * <p>A canvas is one flag or plate cuboid drawn several times at one pose: vanilla's
     * {@code renderCanvas} draws it untinted on the solid layer as a depth backing for the
     * passes that follow, then dyed on the translucent no-outline layer as the base, then
     * once per pattern in authored order. Same cuboid, same pose, in that order, so the
     * cuboid and its pose identify a canvas without reading a sprite name or a UV
     * rectangle: a run of translucent cuboids sharing both is one canvas, its first part
     * the base and the rest patterns at increasing depth, and a solid-layer cuboid that
     * matches a base's cuboid and pose is that base's backing. A capture can hold several
     * canvases (a shield in each hand, the copies of a dropped banner stack); each is its
     * own run and each backing is matched to its own base, so a pattern-less shield
     * beside a patterned one keeps its plate.
     *
     * <p>Per capture: {@link #beginEntity}, {@link #collectBases} for every layer, then
     * per layer {@link #beginLayer} and {@link #next} for each cuboid part in order, with
     * {@link #depth()} valid for the part last classified. Bases are collected up front
     * because the solid layer that carries a backing is captured, and so submitted,
     * before the translucent layer that carries its base.
     */
    static final class CanvasReader {
        private static final int FLOATS_PER_BONE = 12;

        private float[] boneData;
        private final List<ModelPart.Polygon[]> baseSides = new ArrayList<>();
        private final IntArrayList baseBones = new IntArrayList();

        private boolean canvasLayer;
        private PatternCanvasRole solidRole;
        private boolean runOpen;
        private ModelPart.Polygon[] runSides;
        private int runBone;
        private int depth;

        /** Starts a capture whose part bones index {@code boneData}, twelve floats each. */
        void beginEntity(float[] boneData) {
            this.boneData = boneData;
            baseSides.clear();
            baseBones.clear();
        }

        /** Records the bases of a layer; only a translucent pattern-atlas layer holds any. */
        void collectBases(Identifier texId, RenderLayerUtils.SurfaceMode layerMode,
                          RemixCapturingConsumer consumer) {
            beginLayer(texId, layerMode);
            if (!canvasLayer) return;
            for (int part = 0, partCount = consumer.getPartCount(); part < partCount; part++) {
                ModelPart.Polygon[] sides = consumer.getPartSides(part);
                if (sides == null) continue;
                int bone = consumer.getPartBone(part);
                if (next(sides, bone) == PatternCanvasRole.BASE) {
                    baseSides.add(sides);
                    baseBones.add(bone);
                }
            }
        }

        /** Starts a layer; {@link #next} then classifies its cuboids in part order. */
        void beginLayer(Identifier texId, RenderLayerUtils.SurfaceMode layerMode) {
            boolean patternAtlas = needsVertexColorBlend(texId);
            canvasLayer = patternAtlas && layerMode == RenderLayerUtils.SurfaceMode.TRANSLUCENT;
            solidRole = patternAtlas ? PatternCanvasRole.FRAME : PatternCanvasRole.NONE;
            runOpen = false;
            depth = 0;
        }

        /** Classifies the layer's next cuboid part, which hangs off {@code bone}. */
        PatternCanvasRole next(ModelPart.Polygon[] sides, int bone) {
            if (!canvasLayer) return solidRole;
            if (runOpen && sides == runSides && samePose(bone, runBone)) {
                depth++;
                return PatternCanvasRole.PATTERN;
            }
            runOpen = true;
            runSides = sides;
            runBone = bone;
            depth = 0;
            return PatternCanvasRole.BASE;
        }

        /** Canvas depth of the part {@link #next} last classified: 0 for a base, its place behind the base for a pattern. */
        int depth() {
            return depth;
        }

        /**
         * Whether a solid-layer canvas cuboid is vanilla's untinted depth backing for a
         * base drawn over it. Its only purpose is depth for passes that write none, which
         * the path tracer does not need, and drawn it would show as white cloth through
         * the base. Pole, bar and handle are other cuboids, a pattern-less shield plate
         * is drawn at no base's pose, and a hashed part has no cuboid to match, so those
         * stay.
         */
        boolean isBacking(PatternCanvasRole role, ModelPart.Polygon[] sides, int bone) {
            if (role != PatternCanvasRole.FRAME || sides == null) return false;
            for (int i = 0, n = baseSides.size(); i < n; i++) {
                if (baseSides.get(i) == sides && samePose(bone, baseBones.getInt(i))) return true;
            }
            return false;
        }

        /**
         * Every pass of one canvas is rendered from one matrix stack state through one
         * {@code ModelPart.rotate}, so the poses agree to the bit.
         */
        private boolean samePose(int bone, int other) {
            int a = bone * FLOATS_PER_BONE;
            int b = other * FLOATS_PER_BONE;
            return Arrays.equals(boneData, a, a + FLOATS_PER_BONE, boneData, b, b + FLOATS_PER_BONE);
        }
    }

    /**
     * The shell layer a part's mesh is baked with. Every part takes its layer's
     * ordinal except canvas base and pattern cuboids, which add their canvas depth:
     * they share one render layer, so the ordinal alone would bake every pattern at
     * the base's depth and leave them fighting for the same surface.
     */
    static int shellLayerFor(int layerIndex, int canvasDepth, PatternCanvasRole role) {
        return role == PatternCanvasRole.BASE || role == PatternCanvasRole.PATTERN
                ? layerIndex + canvasDepth
                : layerIndex;
    }

    private final CanvasReader canvasReader = new CanvasReader();

    private void collectCanvasBases(RemixEntityPass.EntityCaptureResult result) {
        canvasReader.beginEntity(result.boneData());
        for (RemixVertexConsumerProvider.CapturedLayer layer : result.layers()) {
            Identifier texId = layer.textureId();
            if (texId == null) continue;
            canvasReader.collectBases(texId,
                    RenderLayerUtils.surfaceMode(layer.renderLayer()), layer.consumer());
        }
    }

    private static boolean isDynamicMapTexture(Identifier textureId) {
        return textureId.getNamespace().equals(Identifier.DEFAULT_NAMESPACE)
                && textureId.getPath().startsWith(DYNAMIC_MAP_TEXTURE_PREFIX);
    }

    /**
     * Decorations are already positioned just above the map by Minecraft, so they are
     * no-offset decals rather than independent surfaces that can shadow the map below.
     *
     * <p>Geometry on the particle atlas is a held item's flame and smoke, the same quads
     * the particle pipeline streams for a placed torch. Told they are particles, the
     * runtime resolves them the way it resolves those: soft, unordered and in the
     * transparency layer, rather than as opaque hits taken with the odds of their alpha.
     */
    static int additionalCategoryFlagsForTexture(Identifier textureId) {
        if (MAP_DECORATIONS_ATLAS_TEXTURE.equals(textureId)) {
            return InstanceCategoryBit.DECAL_NO_OFFSET;
        }
        if (PARTICLE_ATLAS_ID.equals(textureId)) {
            return InstanceCategoryBit.PARTICLE;
        }
        return 0;
    }

    /**
     * Where a cutout material cuts. Maps, map decorations and glyph pages are coverage:
     * their texels are either empty, full, or an antialiased edge, so the cut belongs at
     * half coverage, where the runtime's generated mip levels interpolate back to the
     * authored boundary. Every other texture shows any texel with alpha, as vanilla does.
     */
    static int cutoutAlphaReferenceValue(Identifier textureId) {
        return isDynamicMapTexture(textureId)
                || MAP_DECORATIONS_ATLAS_TEXTURE.equals(textureId)
                || DynamicTextureCache.isGlyphPage(textureId)
                ? COVERAGE_ALPHA_REFERENCE
                : DEFAULT_CUTOUT_ALPHA_REFERENCE;
    }

    private void submitRigidPart(RemixEntityPass.EntityCaptureResult result,
                                 RemixCapturingConsumer consumer,
                                 int partIndex,
                                 Identifier texId,
                                 RenderLayerUtils.SurfaceMode surfaceMode,
                                 RenderLayerUtils.Topology topology,
                                 boolean transientGeometry,
                                 int layerIndex,
                                 int shellLayer,
                                 PatternCanvasRole role,
                                 Transform worldTransform,
                                 int categoryFlags) {
        int startVertex = consumer.getPartStart(partIndex);
        int vertexCount = consumer.getPartVertexCount(partIndex);
        ModelPart.Polygon[] sides = consumer.getPartSides(partIndex);
        TextureAtlasSprite sprite = consumer.getPartSprite(partIndex);
        // Key and material both derive from this part's narrowed mode, so a
        // pooled mesh is never handed to a part whose material differs from the
        // one baked into it.
        rigidMeshLookupKey.setLayer(texId, surfaceMode, shellLayer);
        if (sides != null) {
            rigidMeshLookupKey.setCuboid(sides, sprite);
        } else {
            rigidMeshLookupKey.setHashed(topology, vertexCount, consumer.getPartHash(partIndex));
        }
        // Materialised only on a pool miss or an identity change; the steady state
        // never allocates a key.
        pendingPartKey = null;
        // A cuboid part is never transient: its mesh is keyed by the cuboid's identity
        // and rebuilt from that immutable cuboid, so it is shared however its layer
        // streams.
        CopperBeaconBlockEntity beacon = placedBeaconLens(result.owner(), sprite);
        boolean emissiveBeaconLens = beacon != null
                && CopperBeaconBlockEntityRenderer.LENS_SPRITE_ID.equals(
                        sprite.contents().name());
        RemixMesh mesh = emissiveBeaconLens
                ? acquireBeaconLensMesh(
                        beacon, vertexCount, texId, shellLayer, sides, sprite)
                : transientGeometry && sides == null
                        ? createTransientMesh(consumer, startVertex, vertexCount,
                                texId, surfaceMode, topology, shellLayer)
                        : acquirePooledMesh(consumer, startVertex, vertexCount,
                                texId, surfaceMode, topology, shellLayer, sides, sprite);
        if (mesh == null) return;

        drawRetainedPart(result, consumer.getPartBone(partIndex), layerIndex, partIndex,
                categoryFlags, mesh,
                blendExtForPart(
                        texId,
                        surfaceMode,
                        sides != null,
                        consumer.getPartTint(partIndex),
                        role,
                        beacon != null),
                worldTransform);
    }

    private static CopperBeaconBlockEntity placedBeaconLens(Object owner, TextureAtlasSprite sprite) {
        return owner instanceof CopperBeaconBlockEntity beacon
                        && sprite != null
                        && (CopperBeaconBlockEntityRenderer.LENS_SPRITE_ID.equals(
                                        sprite.contents().name())
                                || CopperBeaconBlockEntityRenderer.UNLIT_LENS_SPRITE_ID.equals(
                                        sprite.contents().name()))
                ? beacon
                : null;
    }

    private RemixMesh acquireBeaconLensMesh(
            CopperBeaconBlockEntity beacon,
            int vertexCount,
            Identifier textureId,
            int shellLayer,
            ModelPart.Polygon[] sides,
            TextureAtlasSprite sprite) {
        if (sides == null) {
            return null;
        }
        BlockTextureManager textures = BlockTextureManager.get();
        if (textures == null || !textures.isInitialized()) {
            return null;
        }

        BeaconLensAssets assets = beaconLensAssets.get(beacon);
        if (assets != null
                && (assets.mesh.isDeletedFast()
                        || assets.material.isDeleted()
                        || assets.sides != sides
                        || assets.sprite != sprite)) {
            destroyBeaconLensAssets(textures, assets);
            beaconLensAssets.remove(beacon);
            assets = null;
        }

        float intensityMultiplier = beacon.getIntensityMultiplier();
        if (assets == null) {
            RemixMaterial material =
                    textures.createEmissionScaledAtlasMaterial(
                            beaconLensMaterialMultiplier(intensityMultiplier));
            if (material == null) {
                return null;
            }
            rebuildScratch.clear();
            rebuildScratch.emitCuboidGeometry(sides, 0xFFFFFFFF, 0, sprite);
            rebuildScratch.finishOpenVertex();
            RemixMesh mesh = createMeshWithMaterial(
                    rebuildScratch,
                    0,
                    vertexCount,
                    textureId,
                    RenderLayerUtils.Topology.QUADS,
                    shellLayer,
                    material);
            if (mesh == null) {
                textures.destroyEmissionScaledAtlasMaterial(material);
                return null;
            }
            assets = new BeaconLensAssets(
                    material,
                    mesh,
                    sides,
                    sprite,
                    intensityMultiplier,
                    frameIndex());
            beaconLensAssets.put(beacon, assets);
        } else {
            if (Float.compare(assets.intensityMultiplier, intensityMultiplier) != 0) {
                textures.updateEmissionScaledAtlasMaterial(
                        assets.material, beaconLensMaterialMultiplier(intensityMultiplier));
                assets.intensityMultiplier = intensityMultiplier;
            }
            assets.lastSeenFrame = frameIndex();
        }
        return assets.mesh;
    }

    static float beaconLensMaterialMultiplier(float intensityMultiplier) {
        return intensityMultiplier * COPPER_BEACON_LENS_EMISSION_SCALE;
    }

    /**
     * Submits one canvas's pattern stack, parts {@code firstPart} to
     * {@code firstPart + patternCount} of the layer, as a single retained instance over
     * its base: one pooled mesh holding every layer in paint order, see {@link CanvasMeshKey}.
     * The stack takes the first pattern's slot and pose. Its layers carry their dyes as
     * vertex colour, so it blends through {@link #BANNER_PATTERN_BLEND}.
     */
    private void submitCanvasPatterns(RemixEntityPass.EntityCaptureResult result,
                                      RemixCapturingConsumer consumer,
                                      int firstPart,
                                      int patternCount,
                                      Identifier texId,
                                      RenderLayerUtils.SurfaceMode surfaceMode,
                                      int layerIndex,
                                      Transform worldTransform,
                                      int categoryFlags) {
        rigidMeshLookupKey.setLayer(texId, surfaceMode, layerIndex);
        rigidMeshLookupKey.setCanvas(consumer.getPartSides(firstPart), consumer, firstPart, patternCount);
        pendingPartKey = null;
        RemixMesh mesh = pooledMesh();
        if (mesh == null) {
            RigidMeshKey meshKey = rigidMeshLookupKey.immutableCopy();
            pendingPartKey = meshKey;
            rebuildScratch.clear();
            emitCanvasPatterns(rebuildScratch, consumer, firstPart, patternCount, layerIndex);
            rebuildScratch.finishOpenVertex();
            // Every layer's depth is already in its vertices, so the mesh is built at shell 0.
            mesh = createRigidMesh(meshKey, rebuildScratch, 0, rebuildScratch.getVertexCount(),
                    texId, surfaceMode, RenderLayerUtils.Topology.QUADS, 0);
            if (mesh == null) return;
        }
        drawRetainedPart(result, consumer.getPartBone(firstPart), layerIndex, firstPart,
                categoryFlags, mesh, BANNER_PATTERN_BLEND, worldTransform);
    }

    /**
     * Expands a canvas's pattern stack into {@code scratch}: parts {@code firstPart} to
     * {@code firstPart + patternCount} of {@code source}, all one cuboid, each painted with
     * its own sprite and tint in paint order, layer k stepped {@code shellLayer + k + 1}
     * shells out along its normals. Those are the depths the patterns had as separate
     * parts: the stack stays in front of its opaque base, and a held canvas, whose patterns
     * are plain geometry rather than decals, still orders by depth.
     *
     * <p>The patterns keep this per-face push rather than the dilation that
     * {@link #shellOffsets} gives every other shell layer. The stack's mesh is built at
     * shell layer 0, so that dilation never sees it, and the corner seams the push opens
     * on a pattern's thin box expose only the dyed base cloth of the same stack directly
     * beneath, so nothing foreign shows through them.
     */
    static void emitCanvasPatterns(RemixCapturingConsumer scratch, RemixCapturingConsumer source,
                                   int firstPart, int patternCount, int shellLayer) {
        ModelPart.Polygon[] sides = source.getPartSides(firstPart);
        for (int layer = 0; layer < patternCount; layer++) {
            int part = firstPart + layer;
            scratch.emitCuboidGeometry(sides, source.getPartTint(part), 0, source.getPartSprite(part),
                    (shellLayer + layer + 1) * SHELL_OFFSET);
        }
    }

    /**
     * Retains the scene instance for one part of {@code result} and draws it with
     * {@code mesh}. The pose is composed from the part's bone; the native handle is
     * touched only where transform, mesh, categories or blend actually changed.
     */
    private void drawRetainedPart(RemixEntityPass.EntityCaptureResult result,
                                  int bone, int layerIndex, int partIndex, int categoryFlags,
                                  RemixMesh mesh, InstanceInfoBlendEXT blendExt,
                                  Transform worldTransform) {
        composeRigidTransform(worldTransform, result.boneData(), result.boneCount(),
                bone, reusableRigidTransform);

        pendingPartMesh = mesh;
        RetainedEntityPart part = retainedEntityParts.retain(
                result.owner(), categoryFlags, layerIndex, partIndex,
                PART_REUSABLE,
                PENDING_PART_FACTORY,
                RemixEntitySubmitter::destroyRetainedPart);

        if (!Arrays.equals(part.transform.matrix, reusableRigidTransform.matrix)) {
            System.arraycopy(reusableRigidTransform.matrix, 0,
                    part.transform.matrix, 0, reusableRigidTransform.matrix.length);
            part.instance.setTransform(part.transform);
        }
        part.instance
                .setMesh(mesh)
                .setCategoryFlags(categoryFlags)
                .setBlendExt(blendExt)
                .setDoubleSided(true);
        part.instance.drawFast(api);
        if (!rigidMeshLookupKey.equals(part.meshKey)) {
            part.meshKey = pendingPartKey != null ? pendingPartKey : rigidMeshLookupKey.immutableCopy();
        }
        lastDrawCount++;
    }

    /**
     * The live pooled mesh for {@link #rigidMeshLookupKey}, or null on a miss. An entry
     * whose mesh was destroyed under it is dropped and reported as a miss, so the caller
     * rebuilds at once.
     */
    private RemixMesh pooledMesh() {
        PooledMesh pooled = rigidMeshPool.get(rigidMeshLookupKey);
        if (pooled == null) return null;
        if (pooled.mesh.isDeletedFast()) {
            rigidMeshPool.remove(rigidMeshLookupKey);
            return null;
        }
        pooled.lastUsedFrame = frameIndex();
        return pooled.mesh;
    }

    /**
     * Returns the pooled mesh for {@link #rigidMeshLookupKey}, building it on a miss. A miss
     * mints the immutable key and leaves it in {@link #pendingPartKey} for the part record.
     */
    private RemixMesh acquirePooledMesh(RemixCapturingConsumer consumer,
                                        int startVertex,
                                        int vertexCount,
                                        Identifier texId,
                                        RenderLayerUtils.SurfaceMode surfaceMode,
                                        RenderLayerUtils.Topology topology,
                                        int shellLayer,
                                        ModelPart.Polygon[] sides,
                                        TextureAtlasSprite sprite) {
        RemixMesh pooled = pooledMesh();
        if (pooled != null) return pooled;

        RigidMeshKey meshKey = rigidMeshLookupKey.immutableCopy();
        pendingPartKey = meshKey;
        if (sides == null) {
            return createRigidMesh(meshKey, consumer, startVertex, vertexCount,
                    texId, surfaceMode, topology, shellLayer);
        }
        // Rebuild through the same emitter and the same buildSurface the captured
        // path uses, so there is one geometry path rather than two that can drift.
        rebuildScratch.clear();
        // The mesh is tint-free: tint is applied per instance, so every recolour
        // of this cuboid shares the one white-baked mesh. The sprite's UV remap is
        // baked here instead, which is why the sprite is part of the key.
        rebuildScratch.emitCuboidGeometry(sides, 0xFFFFFFFF, 0, sprite);
        rebuildScratch.finishOpenVertex();
        // A cuboid is quads by construction, whatever topology its layer draws.
        return createRigidMesh(meshKey, rebuildScratch, 0, vertexCount,
                texId, surfaceMode, RenderLayerUtils.Topology.QUADS, shellLayer);
    }

    private RetainedEntityPart createPendingRetainedPart() {
        RigidMeshKey key = pendingPartKey != null ? pendingPartKey : rigidMeshLookupKey.immutableCopy();
        RemixInstance instance = new RemixInstance(pendingPartMesh);
        // Registers the instance with the store, whose cleaner is drained by shutdown().
        RemixInstanceStore.get().create(instance);
        return new RetainedEntityPart(instance, key);
    }

    private final RigidMeshLookupKey rigidMeshLookupKey = new RigidMeshLookupKey();
    /** Frame stamp per dynamic texture, so each refreshes once a frame, not once a layer. */
    private final Object2LongOpenHashMap<Identifier> dynamicTextureCheckFrames =
            new Object2LongOpenHashMap<>();

    /**
     * Interned tint extensions, one per distinct canvas role and tint. {@code setBlendExt}
     * dirty-checks by reference, so a stable object per tint keeps unchanged
     * instances at zero native calls while an animating tint degrades to one
     * cheap scene-instance update per part per frame.
     */
    private final Long2ObjectOpenHashMap<InstanceInfoBlendEXT> tintBlendCache =
            new Long2ObjectOpenHashMap<>();

    InstanceInfoBlendEXT blendExtForPart(
            Identifier textureId,
            RenderLayerUtils.SurfaceMode surfaceMode,
            boolean cuboid,
            int tintColor,
            PatternCanvasRole role,
            boolean placedBeaconLens) {
        return placedBeaconLens
                ? internedTintBlend(PatternCanvasRole.NONE, tintColor)
                : blendExtFor(textureId, surfaceMode, cuboid, tintColor, role);
    }

    /**
     * Chooses the texture-stage extension for a part by where its colour lives and what
     * it is painted on.
     *
     * <p>A cuboid part is baked white and carries its colour per instance as {@code tintColor};
     * every other part, a canvas's pattern stack included, carries colour in its vertices.
     * Neither shape needs to combine the two
     * sources: a cuboid's vertex colour is white by construction, so modulating by it is an
     * identity, and every other part is untinted. Combining would in any case be possible only
     * for colour, where the texture factor can multiply on top of the stage's two arguments;
     * the alpha stage takes exactly two sources.
     *
     * <ol>
     *   <li>Block atlas: the blend terrain pairs with the same material, so a held block
     *   carries the instance state of its placed counterpart. A white cuboid mesh makes the
     *   vertex-colour modulate an identity.</li>
     *   <li>Pattern atlas, hashed part (a glinted shield's handle and backing plate, or any
     *   canvas the cuboid path did not intercept): colour and coverage from the baked
     *   vertices, alpha blended.</li>
     *   <li>Pattern atlas, cuboid part: the canvas role decides. The dyed base is texture
     *   times tint with the texture's own alpha and no blend, so it is opaque and the tint's
     *   alpha never touches coverage. A pattern stack is baked with each layer's dye in its
     *   vertices, so it takes rule 2's blend. Frame cuboids are untinted and fall through.</li>
     *   <li>Otherwise vertex-colour modulate, which for an untinted white cuboid is an
     *   identity, or the interned tint for a tinted one.</li>
     * </ol>
     *
     * <p>Rule 3 exists because CuboidMixin looks through {@code SpriteTexturedVertexConsumer}:
     * a canvas base arrives as a white cuboid part with an instance tint, so the vertex colour
     * rule 2 reads is not there. The pattern materials use draw-call alpha state, so this
     * instance state, not the material, decides what blends.
     */
    InstanceInfoBlendEXT blendExtFor(Identifier textureId,
                                     RenderLayerUtils.SurfaceMode surfaceMode,
                                     boolean cuboid,
                                     int tintColor,
                                     PatternCanvasRole role) {
        if (BlockTextureManager.BLOCK_ATLAS_ID.equals(textureId)) {
            return surfaceMode == RenderLayerUtils.SurfaceMode.TRANSLUCENT
                    || surfaceMode == RenderLayerUtils.SurfaceMode.GLASS
                    ? BlockTextureManager.ATLAS_TRANSLUCENT_BLEND
                    : BlockTextureManager.ATLAS_CUTOUT_BLEND;
        }
        if (needsVertexColorBlend(textureId)) {
            if (!cuboid || role == PatternCanvasRole.PATTERN) return BANNER_PATTERN_BLEND;
            if (role == PatternCanvasRole.BASE) return internedTintBlend(role, tintColor);
        }
        if (!cuboid || tintColor == 0xFFFFFFFF) {
            // Without an extension the runtime never reads vertex colour, and
            // outside a cuboid that is the only place an entity's colour lives:
            // baked item quads, streamed vertices, and the damage flash folded
            // into either.
            return VERTEX_COLOR_MODULATE;
        }
        return internedTintBlend(PatternCanvasRole.NONE, tintColor);
    }

    private InstanceInfoBlendEXT internedTintBlend(PatternCanvasRole role, int tintColor) {
        // A hue-cycling tint mints new values indefinitely; the extensions are
        // tiny and rebuildable, so a full reset is cheaper than an LRU.
        if (tintBlendCache.size() > 4096) {
            tintBlendCache.clear();
        }
        long key = ((long) role.ordinal() << 32) | (tintColor & 0xFFFFFFFFL);
        InstanceInfoBlendEXT ext = tintBlendCache.get(key);
        if (ext == null) {
            ext = tintBlend(role, tintColor);
            tintBlendCache.put(key, ext);
        }
        return ext;
    }

    /** The extension {@link #blendExtFor} interns for a tinted cuboid in {@code role}. */
    static InstanceInfoBlendEXT tintBlend(PatternCanvasRole role, int tintColor) {
        return switch (role) {
            case BASE -> canvasBaseBlend(tintColor);
            case NONE, FRAME -> tintModulateBlend(tintColor);
            case PATTERN -> throw new IllegalArgumentException(
                    "a pattern stack carries its dyes as vertex colour, not as a tint");
        };
    }

    /** Texture times tint on colour and on alpha: a dyed cuboid whose tint may also fade it. */
    static InstanceInfoBlendEXT tintModulateBlend(int tintColor) {
        InstanceInfoBlendEXT ext = new InstanceInfoBlendEXT();
        ext.textureColorArg1Source = InstanceInfoBlendEXT.RT_TEXTURE_ARG_TEXTURE;
        ext.textureColorArg2Source = InstanceInfoBlendEXT.RT_TEXTURE_ARG_TFACTOR;
        ext.textureColorOperation = InstanceInfoBlendEXT.RT_TEX_OP_MODULATE;
        // Warden fade and wet wolves tint through alpha, not only colour.
        ext.textureAlphaArg1Source = InstanceInfoBlendEXT.RT_TEXTURE_ARG_TEXTURE;
        ext.textureAlphaArg2Source = InstanceInfoBlendEXT.RT_TEXTURE_ARG_TFACTOR;
        ext.textureAlphaOperation = InstanceInfoBlendEXT.RT_TEX_OP_MODULATE;
        ext.tFactor = tintColor;
        return ext;
    }

    /**
     * Texture times tint on colour, alpha straight from the texture, no blend: the dyed
     * canvas base, opaque wherever its sprite is.
     */
    static InstanceInfoBlendEXT canvasBaseBlend(int tintColor) {
        InstanceInfoBlendEXT ext = new InstanceInfoBlendEXT();
        ext.textureColorArg1Source = InstanceInfoBlendEXT.RT_TEXTURE_ARG_TEXTURE;
        ext.textureColorArg2Source = InstanceInfoBlendEXT.RT_TEXTURE_ARG_TFACTOR;
        ext.textureColorOperation = InstanceInfoBlendEXT.RT_TEX_OP_MODULATE;
        ext.textureAlphaArg1Source = InstanceInfoBlendEXT.RT_TEXTURE_ARG_TEXTURE;
        ext.textureAlphaArg2Source = InstanceInfoBlendEXT.RT_TEXTURE_ARG_NONE;
        ext.textureAlphaOperation = InstanceInfoBlendEXT.RT_TEX_OP_SELECT_ARG1;
        ext.tFactor = tintColor;
        return ext;
    }

    /** Categories every part of a capture carries before the owner's policy adds its own. */
    private static int baseCategoryFlags(RemixEntityPass.EntityCaptureResult result) {
        if (result.firstPersonLocalPlayer()) {
            return InstanceCategoryBit.THIRD_PERSON_PLAYER_MODEL | InstanceCategoryBit.THIRD_PERSON_PLAYER_BODY;
        }
        return 0;
    }

    // --- mesh creation ---

    /**
     * How far the {@code quadIndex}-th quad of a part stands off its base surface.
     * Every part takes its shell layer's offset; a text part adds its draw order, see
     * {@link #TEXT_QUAD_STEP}. Text applies it per vertex along the quad normal; every
     * other part goes through {@link #shellOffsets}.
     */
    static float surfaceDepth(boolean text, int shellLayer, int quadIndex) {
        return text
                ? shellLayer * TEXT_LAYER_STEP + quadIndex * TEXT_QUAD_STEP
                : shellLayer * SHELL_OFFSET;
    }

    /** Distance from a face's plane within which a vertex counts as standing on it, in blocks. */
    private static final float SHELL_PLANE_EPSILON = 1.0e-5f;
    /** Overshoot past a face's edges, as a fraction of the edge, within which a vertex still counts as on it. */
    private static final float SHELL_EXTENT_EPSILON = 1.0e-4f;

    /**
     * Displacement that moves the vertices {@code [start, start + count)} out to their
     * shell, written to {@code ox}, {@code oy}, {@code oz} at {@code index - start}.
     *
     * <p>Every face travels {@code depth} along its own normal, and a vertex travels along
     * the sum of the distinct normals of every face it stands on, at a corner or anywhere
     * on the surface, so a closed shell grows into a slightly larger closed shell. Moving
     * each vertex along its own face normal alone would pull the faces apart and open a
     * hairline gap along every seam, through which whatever is inside shows. Corners cover
     * the edges of a box; the surface covers T-junctions, where one element's corner
     * stands mid-face on another. For faces meeting at right angles, which is nearly all
     * of Minecraft's geometry, the sum is the exact offset; the faces of a rotated element
     * overshoot by less than a factor of two, harmless at this size. Two coplanar faces
     * count once. A face whose exact opposite passes through a vertex, as the two large
     * faces of a zero-thickness cuboid do, leaves that opposite out of its own sum, so both
     * faces still travel their offset and the sheet grows into a thin box instead of
     * staying where it was.
     *
     * <p>An item card whose faces the capture left whole, {@code cardFace >= 0} naming a
     * vertex of one of them, keeps only the travel along that face's normal. Its
     * silhouette is then the texture's alpha on two full-card faces, and its side
     * slivers stand on those faces at the texel edges; dilating a sliver in the card's
     * plane carries it away from an alpha edge that does not move with it, and a
     * hairline of whatever lies behind the card shows between the two. The card may
     * thicken, so an overlay drawn over it still stands off both its faces, but nothing
     * in its plane moves. A card rebuilt from its opaque texels is a closed shell and
     * needs none of this.
     *
     * <p>Only a quad layer has faces to stand on; a triangle strip welds the positions its
     * vertices share exactly and nothing more.
     */
    static void shellOffsets(float[] px, float[] py, float[] pz,
                             float[] nx, float[] ny, float[] nz,
                             int start, int count, float depth,
                             RenderLayerUtils.Topology topology, int cardFace,
                             float[] ox, float[] oy, float[] oz) {
        if (depth == 0.0f) {
            Arrays.fill(ox, 0, count, 0.0f);
            Arrays.fill(oy, 0, count, 0.0f);
            Arrays.fill(oz, 0, count, 0.0f);
            return;
        }
        if (topology == RenderLayerUtils.Topology.QUADS) {
            quadShellOffsets(px, py, pz, nx, ny, nz, start, count, depth, cardFace, ox, oy, oz);
        } else {
            sharedPositionShellOffsets(px, py, pz, nx, ny, nz, start, count, depth, ox, oy, oz);
        }
    }

    /**
     * Every vertex travels along the distinct normals of the quads whose surface it stands
     * on; on an item card only the component along the card's normal is kept.
     */
    private static void quadShellOffsets(float[] px, float[] py, float[] pz,
                                         float[] nx, float[] ny, float[] nz,
                                         int start, int count, float depth, int cardFace,
                                         float[] ox, float[] oy, float[] oz) {
        boolean card = cardFace >= 0;
        float ax = card ? nx[cardFace] : 0f;
        float ay = card ? ny[cardFace] : 0f;
        float az = card ? nz[cardFace] : 0f;
        int quadCount = count / 4;
        float[] countedX = new float[quadCount + 1];
        float[] countedY = new float[quadCount + 1];
        float[] countedZ = new float[quadCount + 1];
        for (int i = 0; i < count; i++) {
            int v = start + i;
            // A vertex always travels its own face, degenerate or not.
            countedX[0] = nx[v]; countedY[0] = ny[v]; countedZ[0] = nz[v];
            int counted = 1;
            float sx = nx[v], sy = ny[v], sz = nz[v];
            for (int q = 0; q < quadCount; q++) {
                int a = start + q * 4;
                float qx = nx[a], qy = ny[a], qz = nz[a];
                // The face directly opposite this one is not a neighbour to grow along.
                if (nx[v] * qx + ny[v] * qy + nz[v] * qz < -0.999f) continue;
                if (directionCounted(countedX, countedY, countedZ, counted, qx, qy, qz)) continue;
                if (!standsOnQuad(px, py, pz, a, px[v], py[v], pz[v], qx, qy, qz)) continue;
                countedX[counted] = qx; countedY[counted] = qy; countedZ[counted] = qz;
                counted++;
                sx += qx;
                sy += qy;
                sz += qz;
            }
            if (card) {
                float along = sx * ax + sy * ay + sz * az;
                sx = along * ax;
                sy = along * ay;
                sz = along * az;
            }
            ox[i] = sx * depth;
            oy[i] = sy * depth;
            oz[i] = sz * depth;
        }
    }

    /** Whether a direction within a degree of {@code (x, y, z)} is already among the first {@code counted}. */
    private static boolean directionCounted(float[] cx, float[] cy, float[] cz, int counted,
                                            float x, float y, float z) {
        for (int k = 0; k < counted; k++) {
            if (cx[k] * x + cy[k] * y + cz[k] * z > 0.999f) return true;
        }
        return false;
    }

    /**
     * Whether the point {@code (x, y, z)} stands on the rectangle whose corners start at
     * vertex {@code a}, in winding order, with unit normal {@code (qx, qy, qz)}: at one of
     * its corners, or anywhere on its surface. A rectangle with a zero-length edge, as the
     * side quads of a zero-thickness cuboid have, has corners but no surface.
     */
    private static boolean standsOnQuad(float[] px, float[] py, float[] pz, int a,
                                        float x, float y, float z,
                                        float qx, float qy, float qz) {
        for (int corner = a; corner < a + 4; corner++) {
            if (Math.abs(px[corner] - x) <= SHELL_PLANE_EPSILON
                    && Math.abs(py[corner] - y) <= SHELL_PLANE_EPSILON
                    && Math.abs(pz[corner] - z) <= SHELL_PLANE_EPSILON) {
                return true;
            }
        }
        float dx = x - px[a], dy = y - py[a], dz = z - pz[a];
        if (Math.abs(dx * qx + dy * qy + dz * qz) > SHELL_PLANE_EPSILON) return false;
        float e1x = px[a + 1] - px[a], e1y = py[a + 1] - py[a], e1z = pz[a + 1] - pz[a];
        float e2x = px[a + 3] - px[a], e2y = py[a + 3] - py[a], e2z = pz[a + 3] - pz[a];
        float l1 = e1x * e1x + e1y * e1y + e1z * e1z;
        float l2 = e2x * e2x + e2y * e2y + e2z * e2z;
        if (l1 < 1.0e-12f || l2 < 1.0e-12f) return false;
        float s = (dx * e1x + dy * e1y + dz * e1z) / l1;
        float t = (dx * e2x + dy * e2y + dz * e2z) / l2;
        return s >= -SHELL_EXTENT_EPSILON && s <= 1.0f + SHELL_EXTENT_EPSILON
                && t >= -SHELL_EXTENT_EPSILON && t <= 1.0f + SHELL_EXTENT_EPSILON;
    }

    /** Every vertex travels along the distinct normals of the vertices sharing its exact position. */
    private static void sharedPositionShellOffsets(float[] px, float[] py, float[] pz,
                                                   float[] nx, float[] ny, float[] nz,
                                                   int start, int count, float depth,
                                                   float[] ox, float[] oy, float[] oz) {
        int[] order = new int[count];
        for (int i = 0; i < count; i++) order[i] = start + i;
        // Adding 0f folds -0f into 0f, so the order agrees with the == used below.
        IntArrays.quickSort(order, 0, count, (IntComparator) (a, b) -> {
            int c = Float.compare(px[a] + 0f, px[b] + 0f);
            if (c == 0) c = Float.compare(py[a] + 0f, py[b] + 0f);
            if (c == 0) c = Float.compare(pz[a] + 0f, pz[b] + 0f);
            return c;
        });
        int runStart = 0;
        while (runStart < count) {
            int first = order[runStart];
            int runEnd = runStart + 1;
            while (runEnd < count && px[order[runEnd]] == px[first]
                    && py[order[runEnd]] == py[first] && pz[order[runEnd]] == pz[first]) {
                runEnd++;
            }
            for (int k = runStart; k < runEnd; k++) {
                int v = order[k];
                float sx = 0f, sy = 0f, sz = 0f;
                for (int j = runStart; j < runEnd; j++) {
                    int w = order[j];
                    if (normalCountedBefore(nx, ny, nz, order, runStart, j)) continue;
                    // The face directly opposite this one is not a neighbour to grow along.
                    if (nx[v] * nx[w] + ny[v] * ny[w] + nz[v] * nz[w] < -0.999f) continue;
                    sx += nx[w];
                    sy += ny[w];
                    sz += nz[w];
                }
                int o = v - start;
                ox[o] = sx * depth;
                oy[o] = sy * depth;
                oz[o] = sz * depth;
            }
            runStart = runEnd;
        }
    }

    /** Whether an earlier vertex of the same position run already contributed this normal. */
    private static boolean normalCountedBefore(float[] nx, float[] ny, float[] nz,
                                               int[] order, int runStart, int k) {
        int v = order[k];
        for (int j = runStart; j < k; j++) {
            int w = order[j];
            if (nx[v] * nx[w] + ny[v] * ny[w] + nz[v] * nz[w] > 0.999f) return true;
        }
        return false;
    }
    /** Per-vertex displacement of a text part: each quad steps along its normal by its draw order. */
    private static void textOffsets(float[] nx, float[] ny, float[] nz,
                                    int start, int count, int shellLayer,
                                    float[] ox, float[] oy, float[] oz) {
        for (int i = 0; i < count; i++) {
            int source = start + i;
            float depth = surfaceDepth(true, shellLayer, i >> 2);
            ox[i] = nx[source] * depth;
            oy[i] = ny[source] * depth;
            oz[i] = nz[source] * depth;
        }
    }

    private float[] shellOffsetX = new float[0];
    private float[] shellOffsetY = new float[0];
    private float[] shellOffsetZ = new float[0];

    /** Builds one rigid-part surface from a contiguous range of captured vertices. */
    private RemixMeshSurface buildSurface(RemixCapturingConsumer consumer,
                                          int sourceStart,
                                          int vertCount,
                                          RenderLayerUtils.Topology topology,
                                          boolean text,
                                          int shellLayer,
                                          RemixMaterial mat) {
        float[] cpx = consumer.getPx(), cpy = consumer.getPy(), cpz = consumer.getPz();
        float[] cnx = consumer.getNx(), cny = consumer.getNy(), cnz = consumer.getNz();
        float[] ctu = consumer.getTu(), ctv = consumer.getTv();
        int[] cColors = consumer.getColors();
        int[] boundsMin = consumer.getTexcoordBoundsMin();
        int[] boundsMax = consumer.getTexcoordBoundsMax();

        // Text steps its quads even on the base layer; anything else on the base layer stays put.
        boolean offset = text || shellLayer > 0;
        if (offset) {
            if (shellOffsetX.length < vertCount) {
                shellOffsetX = new float[vertCount];
                shellOffsetY = new float[vertCount];
                shellOffsetZ = new float[vertCount];
            }
            if (text) {
                textOffsets(cnx, cny, cnz, sourceStart, vertCount, shellLayer,
                        shellOffsetX, shellOffsetY, shellOffsetZ);
            } else {
                shellOffsets(cpx, cpy, cpz, cnx, cny, cnz, sourceStart, vertCount,
                        surfaceDepth(false, shellLayer, 0), topology,
                        consumer.cardFaceOfPartStartingAt(sourceStart),
                        shellOffsetX, shellOffsetY, shellOffsetZ);
            }
        }

        // Written straight into the pooled buffer in the native layout: a HardcodedVertex per
        // vertex (four objects each) was the entity path's largest remaining allocation.
        ByteBuffer vertices = DirectBufferPool.acquire(vertCount * HardcodedVertex.STRIDE);
        for (int i = 0; i < vertCount; i++) {
            int source = sourceStart + i;
            RemixMeshSurface.putVertex(vertices,
                offset ? cpx[source] + shellOffsetX[i] : cpx[source],
                offset ? cpy[source] + shellOffsetY[i] : cpy[source],
                offset ? cpz[source] + shellOffsetZ[i] : cpz[source],
                cnx[source], cny[source], cnz[source],
                ctu[source], ctv[source],
                cColors[source],
                boundsMin[source], boundsMax[source]);
        }
        vertices.flip();

        int[] indices = buildIndices(vertCount, topology);
        return new RemixMeshSurface(mat)
                .setVertexData(vertices, vertCount)
                .setIndexDataFrom(indices, indices.length)
                .setFlags(MeshInfoSurfaceTriangles.TEXCOORD_BOUNDS);
    }

    /**
     * Triangle indices for a run of captured vertices.
     *
     * <p>Quads become two triangles fanned from their first vertex. A strip
     * instead advances one vertex at a time, and every other triangle has its
     * first two swapped, which is what keeps the whole strip facing one way.
     */
    static int[] buildIndices(int vertCount, RenderLayerUtils.Topology topology) {
        if (topology == RenderLayerUtils.Topology.TRIANGLE_STRIP) {
            int triangleCount = vertCount - 2;
            int[] indices = new int[triangleCount * 3];
            for (int t = 0; t < triangleCount; t++) {
                int bi = t * 3;
                boolean flipped = (t & 1) == 1;
                indices[bi] = flipped ? t + 1 : t;
                indices[bi + 1] = flipped ? t : t + 1;
                indices[bi + 2] = t + 2;
            }
            return indices;
        }

        int quadCount = vertCount / 4;
        int[] indices = new int[quadCount * 6];
        for (int q = 0; q < quadCount; q++) {
            int bv = q * 4, bi = q * 6;
            indices[bi]     = bv; indices[bi + 1] = bv + 2; indices[bi + 2] = bv + 1;
            indices[bi + 3] = bv; indices[bi + 4] = bv + 3; indices[bi + 5] = bv + 2;
        }
        return indices;
    }

    /** Builds a mesh for {@code meshKey} and records it in the pool. */
    private RemixMesh createRigidMesh(RigidMeshKey meshKey,
                                      RemixCapturingConsumer consumer,
                                      int sourceStart,
                                      int vertexCount,
                                      Identifier textureId,
                                      RenderLayerUtils.SurfaceMode surfaceMode,
                                      RenderLayerUtils.Topology topology,
                                      int shellLayer) {
        RemixMesh mesh = createMesh(consumer, sourceStart, vertexCount,
                textureId, surfaceMode, topology, shellLayer);
        if (mesh != null) {
            rigidMeshPool.put(meshKey, new PooledMesh(mesh, frameIndex()));
        }
        return mesh;
    }

    /**
     * Builds a mesh for a part whose geometry is streamed afresh every frame. It is held
     * only until {@link #retireTransientMeshes()} has seen every instance move off it.
     *
     * <p>{@link RenderLayerUtils#isStreamedPerFrame} names such layers: the lead's strip is
     * recomputed each frame from the offset between mob and holder, and the beacon beam's
     * texture coordinate scrolls with world time. Such a part's content hash never repeats
     * while it animates, so the pool could not share it and would only retain every stale
     * mesh, with its buffers and acceleration structure, until it overflowed at thousands
     * of entries. A mesh per frame is the cost of dynamic geometry; keeping dead ones is
     * not, so streamed parts never enter the pool.
     */
    private RemixMesh createTransientMesh(RemixCapturingConsumer consumer,
                                          int sourceStart,
                                          int vertexCount,
                                          Identifier textureId,
                                          RenderLayerUtils.SurfaceMode surfaceMode,
                                          RenderLayerUtils.Topology topology,
                                          int shellLayer) {
        RemixMesh mesh = createMesh(consumer, sourceStart, vertexCount,
                textureId, surfaceMode, topology, shellLayer);
        if (mesh != null) {
            transientMeshes.add(mesh);
        }
        return mesh;
    }

    @SuppressWarnings("resource")
    private RemixMesh createMesh(RemixCapturingConsumer consumer,
                                 int sourceStart,
                                 int vertexCount,
                                 Identifier textureId,
                                 RenderLayerUtils.SurfaceMode surfaceMode,
                                 RenderLayerUtils.Topology topology,
                                 int shellLayer) {
        RemixMaterial material = materialCache.get(materialLookupKey.set(textureId, surfaceMode));
        if (material == null || material.isDeleted()) {
            EntityMaterialKey materialKey = new EntityMaterialKey(textureId, surfaceMode);
            ensureMaterial(materialKey);
            material = materialCache.get(materialLookupKey);
            if (material == null || material.isDeleted()) {
                noteMissingMaterial(materialKey);
                return null;
            }
        }
        return createMeshWithMaterial(
                consumer,
                sourceStart,
                vertexCount,
                textureId,
                topology,
                shellLayer,
                material);
    }

    private RemixMesh createMeshWithMaterial(
            RemixCapturingConsumer consumer,
            int sourceStart,
            int vertexCount,
            Identifier textureId,
            RenderLayerUtils.Topology topology,
            int shellLayer,
            RemixMaterial material) {
        boolean strip = topology == RenderLayerUtils.Topology.TRIANGLE_STRIP;
        boolean valid = strip
                ? vertexCount >= STRIP_MIN_VERTICES
                : vertexCount >= 4 && vertexCount % 4 == 0;
        if (!valid) {
            LOGGER.warn("Invalid entity mesh vertex count ({}) for {} {} shell layer {}",
                    vertexCount, textureId, topology, shellLayer);
            return null;
        }
        RemixMeshSurface surface = buildSurface(consumer, sourceStart, vertexCount,
                topology, DynamicTextureCache.isGlyphPage(textureId), shellLayer, material);
        try {
            RemixMesh mesh = new RemixMesh(new RemixMeshSurface[]{surface});
            try (ProfileScope createScope = ProfileScope.begin("Entity createRigidMesh")) {
                createScope.vertexCount(vertexCount).surfaceCount(1);
                RemixMeshStore.get().create(mesh);
            }
            lastMeshCreateCount++;
            LOGGER.debug("Created entity mesh for {} {} ({} verts, shell layer {})",
                    textureId, topology, vertexCount, shellLayer);
            return mesh;
        } catch (RemixException e) {
            LOGGER.error("Failed to create entity mesh for {} {}: {}",
                    textureId, topology, e.getMessage());
            return null;
        }
    }

    private void evictColdRigidMeshesIfNeeded() {
        if (rigidMeshPool.size() <= MAX_MESH_CACHE_ENTRIES) return;

        int evicted = 0;
        Iterator<Map.Entry<RigidMeshKey, PooledMesh>> it = rigidMeshPool.entrySet().iterator();
        while (rigidMeshPool.size() > MAX_MESH_CACHE_ENTRIES && it.hasNext()) {
            PooledMesh entry = it.next().getValue();
            if (entry.lastUsedFrame == frameIndex()) continue;

            it.remove();
            if (entry.mesh != null && !entry.mesh.isDeletedFast()) entry.mesh.destroy();
            evicted++;
        }

        if (evicted > 0) {
            LOGGER.debug("Evicted {} pooled rigid entity meshes ({} remain)",
                    evicted, rigidMeshPool.size());
        }
    }

    // --- material creation ---

    /**
     * Records that a captured layer was dropped for want of a material, and warns
     * once if this key has stayed materialless for {@link #MISSING_MATERIAL_WARN_FRAMES}.
     * Transient gaps clear themselves when {@link #ensureMaterial} finally caches a
     * material, so a healthy launch never warns.
     */
    private void noteMissingMaterial(EntityMaterialKey materialKey) {
        if (warnedMissingMaterials.contains(materialKey)) return;
        long frame = frameIndex();
        Long since = missingMaterialSince.putIfAbsent(materialKey, frame);
        if (since != null && frame - since >= MISSING_MATERIAL_WARN_FRAMES) {
            warnedMissingMaterials.add(materialKey);
            LOGGER.warn("Entity layers using {} ({}) have been dropped for {} frames: no material "
                    + "could be created, so those layers are missing from the scene",
                    materialKey.textureId(), materialKey.surfaceMode(), frame - since);
        }
    }

    /** Caches a freshly created material and retires any pending missing-material record for it. */
    private void cacheMaterial(EntityMaterialKey materialKey, RemixMaterial material) {
        if (material == null) return;
        materialCache.put(materialKey, material);
        missingMaterialSince.remove(materialKey);
    }

    private void ensureMaterial(EntityMaterialKey materialKey) {
        ensureMaterial(materialKey, !SYNC_ENTITY_TEXTURES);
    }

    /**
     * Caches a material for {@code materialKey}, creating its texture set first if needed.
     *
     * @param loadOffThread whether a missing texture set may be prepared on the loader
     *                      thread, leaving the key materialless until it lands; false builds
     *                      it here and now
     */
    private void ensureMaterial(EntityMaterialKey materialKey, boolean loadOffThread) {
        if (materialKey == null || materialKey.textureId() == null) return;
        RemixMaterial cached = materialCache.get(materialKey);
        if (cached != null && !cached.isDeleted()) return;
        if (cached != null) materialCache.remove(materialKey);
        Identifier textureId = materialKey.textureId();

        // Item entities borrow the block atlas and its already-built LabPBR materials.
        if (BlockTextureManager.BLOCK_ATLAS_ID.equals(textureId)) {
            BlockTextureManager btm = BlockTextureManager.get();
            if (btm == null || !btm.isInitialized()) return;
            // A held translucent or clear-glass block gets the same atlas material
            // its placed counterpart does, so ice and glass in the hand refract
            // like ice and glass in the world rather than being alpha blended or
            // alpha tested. The key's mode is trusted here: Minecraft draws opaque
            // items through its translucent item layer too, so a captured atlas
            // TRANSLUCENT only reaches a material key after atlasSurfaceMode has
            // narrowed it per part.
            RemixMaterial material = btm.getAtlasMaterial(atlasLayerType(materialKey.surfaceMode()));
            cacheMaterial(materialKey, material);
            return;
        }

        // The held-item flame borrows the particle pipeline's material so it is
        // lit by the same numbers as the placed torch's flame particle. The
        // manager initializes lazily and defers until the atlas has stitched, so
        // a null here means try again next frame, not that the layer failed.
        if (PARTICLE_ATLAS_ID.equals(textureId)) {
            if (!particleAtlasManager.isInitialized()) particleAtlasManager.initialize();
            cacheMaterial(materialKey, particleAtlasManager.getMaterial());
            return;
        }

        if (failedTextures.contains(textureId)) return;

        try {
            EntityTextureSet textures = textureSets.get(textureId);
            if (textures == null) {
                if (loadOffThread && requestTextureSetOffThread(textureId)) return;
                textures = RenderLayerUtils.UNTEXTURED_LAYER_ID.equals(textureId)
                        ? createUntexturedTextureSet()
                        : isAtlasTexture(textureId)
                        ? tryCreateAtlasTextureSet(textureId)
                        : tryCreateTextureSet(textureId);
                if (textures == null) return;
                textureSets.put(textureId, textures);
                // A set built here overtakes any request still preparing the same texture.
                textureLoader.cancel(textureId);
            }

            RemixMaterial material = createMaterial(materialKey, textures);
            cacheMaterial(materialKey, material);
        } catch (Exception e) {
            LOGGER.error("Failed to create material for {}: {}", textureId, e.getMessage(), e);
        }
    }

    /**
     * Texture set for layers that draw without one. Their whole appearance is
     * vertex colour, so a single white texel is all the albedo they need and
     * there is no LabPBR variant to look for. Returns null when the upload fails,
     * so the caller retries next frame instead of caching a set with no albedo.
     */
    private EntityTextureSet createUntexturedTextureSet() {
        return uploadPreparedTextureSet(preparePlainTextureSet(
                RenderLayerUtils.UNTEXTURED_LAYER_ID.toString(),
                UNTEXTURED_WHITE_PIXEL.clone(), 1, 1, null, null));
    }

    private EntityTextureSet tryCreateTextureSet(Identifier textureId) throws Exception {
        var client = Minecraft.getInstance();
        if (client == null) return null;
        ResourceManager rm = client.getResourceManager();
        // getResource walks every registered pack and stats the directory-backed ones. For a
        // texture that is not in any pack this never succeeds, so without the memo it is paid
        // on the render thread on every frame the entity is visible.
        Optional<Resource> resource = resourceMissing.contains(textureId)
                ? Optional.empty()
                : rm.getResource(textureId);
        if (resource.isEmpty()) {
            resourceMissing.add(textureId);
        }

        int width, height;
        byte[] pixels;
        DynamicTextureCache.Pixels cachedPixels = null;
        if (resource.isPresent()) {
            DecodedImage image = decodeRGBA(resource.get());
            width = image.width();
            height = image.height();
            pixels = image.pixels();
        } else {
            // A downloaded skin reaches the cache when its texture uploads. Until then the
            // entity renders with the default and picks the skin up on a later frame.
            DynamicTextureCache.Pixels cached = DynamicTextureCache.get(textureId);
            if (cached == null) {
                return null;
            }
            width = cached.width();
            height = cached.height();
            pixels = Arrays.copyOf(cached.data(), cached.data().length);
            cachedPixels = cached;
        }

        Identifier spriteId = deriveLabPBRSpriteId(textureId);
        PreparedTextureSet prepared = preparePlainTextureSet(textureId.toString(), pixels,
                width, height, spriteId == null ? null : renderThreadVariants(rm), spriteId);
        EntityTextureSet textures = uploadPreparedTextureSet(prepared);
        if (textures == null) return null;
        if (cachedPixels != null && cachedPixels.version() > 0L) {
            recordDynamicTexture(textureId, cachedPixels.version(), width, height);
        }
        return textures;
    }

    private void recordDynamicTexture(Identifier textureId, long version, int width, int height) {
        dynamicTextureVersions.put(textureId, version);
        dynamicTextureSizes.put(textureId, new TextureSize(width, height));
    }

    /**
     * Decodes a pack texture to RGBA bytes. Safe off the render thread: the image is
     * private to the call and never touches the GPU.
     */
    private static DecodedImage decodeRGBA(Resource resource) throws IOException {
        try (InputStream stream = resource.open();
             NativeImage image = NativeImage.read(stream)) {
            int width = image.getWidth();
            int height = image.getHeight();
            return new DecodedImage(abgrToRGBA(image, width, height), width, height);
        }
    }

    /** The render thread's variant index, built on first use after each reload. */
    private LabPBRConverter.VariantCache renderThreadVariants(ResourceManager rm) {
        if (labPBRVariants == null) labPBRVariants = LabPBRConverter.buildVariantCache(rm);
        return labPBRVariants;
    }

    private static NativeImage loadVariant(LabPBRConverter.VariantCache variants,
                                           Identifier spriteId, String suffix) {
        if (variants == null || spriteId == null) return null;
        return variants.loadVariant(spriteId, suffix);
    }

    // --- off-thread texture-set loading ---

    /**
     * Starts preparing {@code textureId}'s texture set on the loader thread. The pack read,
     * PNG decodes, and LabPBR conversion go there; anything that reads live Minecraft
     * texture state stays here, because a resource reload frees it on this thread.
     *
     * @return true when the caller should leave the set missing this frame: it is being
     *         prepared, there is no room to queue it yet, or there is nothing to build it
     *         from yet; false when it should be built inline as before
     */
    private boolean requestTextureSetOffThread(Identifier textureId) {
        // One white texel and no variants: nothing worth a round trip.
        if (RenderLayerUtils.UNTEXTURED_LAYER_ID.equals(textureId)) return false;
        if (textureLoader.isPending(textureId)) return true;
        if (!textureLoader.hasCapacity()) return true;
        var client = Minecraft.getInstance();
        if (client == null) return true;
        return isAtlasTexture(textureId)
                ? requestAtlasTextureSet(client, textureId)
                : requestPlainTextureSet(client, textureId);
    }

    private boolean requestPlainTextureSet(Minecraft client, Identifier textureId) {
        Identifier spriteId = deriveLabPBRSpriteId(textureId);
        LabPBRConverter.VariantCache variants = labPBRVariants;
        DynamicTextureCache.Pixels cached = null;
        if (!resourceMissing.contains(textureId)) {
            // Pixels the game already holds under this id are what vanilla draws for it, not
            // a pack file, so the id is memoized as absent from the packs here rather than
            // after a loader job's pack walk misses. Waiting for that miss would leave a newly
            // downloaded skin, a new glyph page, or a new map without a layer for the frames
            // the round trip takes, and spend loader slots on a lookup known to miss.
            cached = DynamicTextureCache.get(textureId);
            if (cached != null) resourceMissing.add(textureId);
        }
        if (resourceMissing.contains(textureId)) {
            // The pixels are already in memory: a downloaded skin, a glyph page, a map. With
            // no variant to look for, or an index already built to look in, what is left is a
            // copy and a lookup, so build inline rather than blank the layer while a skin
            // swaps in. Only a first build of the variant index is worth moving. A skin still
            // downloading has no pixels yet and waits, as it does inline.
            if (spriteId == null || variants != null) return false;
            if (cached == null) cached = DynamicTextureCache.get(textureId);
            if (cached == null) return true;
        }
        ResourceManager rm = client.getResourceManager();
        String name = textureId.toString();
        long generation = textureLoader.generation();
        if (cached != null) {
            byte[] pixels = Arrays.copyOf(cached.data(), cached.data().length);
            int width = cached.width();
            int height = cached.height();
            long version = cached.version();
            textureLoader.request(textureId, () -> {
                LabPBRConverter.VariantCache used = loaderVariantCache(generation, rm, null);
                PreparedTextureSet prepared = preparePlainTextureSet(
                        name, pixels, width, height, used, spriteId);
                prepared.variants = used;
                prepared.dynamicVersion = version;
                return prepared;
            });
            return true;
        }
        textureLoader.request(textureId, () -> {
            // A miss comes back as null for the render thread to memoize in
            // resourceMissing, so the pack walk is paid once, as on the inline path.
            Optional<Resource> resource = rm.getResource(textureId);
            if (resource.isEmpty()) return null;
            DecodedImage image = decodeRGBA(resource.get());
            LabPBRConverter.VariantCache used = spriteId == null
                    ? null : loaderVariantCache(generation, rm, variants);
            PreparedTextureSet prepared = preparePlainTextureSet(
                    name, image.pixels(), image.width(), image.height(), used, spriteId);
            prepared.variants = used;
            return prepared;
        });
        return true;
    }

    private boolean requestAtlasTextureSet(Minecraft client, Identifier textureId) {
        try {
            AtlasSnapshot snapshot = snapshotAtlas(client, textureId);
            if (snapshot == null) return true;
            ResourceManager rm = client.getResourceManager();
            LabPBRConverter.VariantCache variants = labPBRVariants;
            long generation = textureLoader.generation();
            String name = "atlas_" + textureId;
            textureLoader.request(textureId, () -> {
                LabPBRConverter.VariantCache used = loaderVariantCache(generation, rm, variants);
                PreparedTextureSet prepared = prepareAtlasTextureSet(name, snapshot, used);
                prepared.variants = used;
                return prepared;
            });
        } catch (Exception e) {
            LOGGER.error("Failed to create atlas textures for {}: {}", textureId, e.getMessage(), e);
            failedTextures.add(textureId);
        }
        return true;
    }

    /**
     * The variant index a loader job looks its variants up in. Runs on the loader thread.
     * The render thread's index is lent when it has one; otherwise the loader builds its
     * own once per loader generation, so after a reload, which bumps the generation, no
     * job reads an index built from the previous packs.
     */
    private LabPBRConverter.VariantCache loaderVariantCache(long generation, ResourceManager rm,
                                                            LabPBRConverter.VariantCache lent) {
        if (lent != null) return lent;
        LoaderVariants cached = loaderVariants;
        if (cached != null && cached.generation() == generation) return cached.cache();
        LabPBRConverter.VariantCache built = LabPBRConverter.buildVariantCache(rm);
        loaderVariants = new LoaderVariants(generation, built);
        return built;
    }

    /**
     * Turns a prepared set into Remix textures; the loader's sink, on the render thread.
     * Each outcome matches the inline build's: a missing resource is memoized, a failed
     * atlas is not retried until it is uploaded again, and a failed plain texture is
     * retried the next time it is drawn.
     */
    private void acceptLoadedTextureSet(Identifier textureId, PreparedTextureSet prepared,
                                        Throwable failure) {
        boolean atlas = isAtlasTexture(textureId);
        try {
            if (failure != null) {
                reportTextureSetFailure(textureId, atlas, failure);
                return;
            }
            if (prepared == null) {
                resourceMissing.add(textureId);
                return;
            }
            if (labPBRVariants == null && prepared.variants != null) {
                labPBRVariants = prepared.variants;
            }
            if (textureSets.containsKey(textureId) || failedTextures.contains(textureId)) return;
            EntityTextureSet textures = atlas
                    ? createAtlasTextureSet(textureId, prepared)
                    : uploadPreparedTextureSet(prepared);
            if (textures == null) return;
            textureSets.put(textureId, textures);
            if (prepared.dynamicVersion > 0L) {
                recordDynamicTexture(textureId, prepared.dynamicVersion,
                        prepared.width, prepared.height);
            }
        } catch (RuntimeException e) {
            reportTextureSetFailure(textureId, atlas, e);
        }
    }

    private void reportTextureSetFailure(Identifier textureId, boolean atlas, Throwable failure) {
        if (atlas) {
            LOGGER.error("Failed to create atlas textures for {}: {}",
                    textureId, failure.getMessage(), failure);
            failedTextures.add(textureId);
        } else {
            LOGGER.error("Failed to create material for {}: {}",
                    textureId, failure.getMessage(), failure);
        }
    }

    /**
     * Creates the largest block-entity atlas during renderer warm-up instead of
     * stalling the first frame in which a bed becomes visible.
     *
     * @return true only when a live material is cached for the atlas
     */
    public boolean preloadLargeBlockEntityAtlases() {
        // Built inline even with off-thread loading: warm-up runs under the join
        // screen, where a stall is hidden, and the caller needs the answer now.
        ensureMaterial(BEDS_ATLAS_MATERIAL_KEY, false);
        return hasLiveLargeBlockEntityAtlasMaterial();
    }

    /** Returns whether the preloaded bed-atlas material is still cached and usable. */
    public boolean hasLiveLargeBlockEntityAtlasMaterial() {
        RemixMaterial material = materialCache.get(BEDS_ATLAS_MATERIAL_KEY);
        return material != null && !material.isDeleted();
    }

    static boolean needsVertexColorBlend(Identifier textureId) {
        return BANNER_PATTERNS_ATLAS_TEXTURE.equals(textureId)
                || SHIELD_PATTERNS_ATLAS_TEXTURE.equals(textureId)
                || isPatternTexture(textureId, "textures/entity/banner/")
                || isPatternTexture(textureId, "textures/entity/shield/");
    }

    private static boolean isPatternTexture(Identifier textureId, String pathPrefix) {
        return "minecraft".equals(textureId.getNamespace())
                && textureId.getPath().startsWith(pathPrefix)
                && textureId.getPath().endsWith(".png");
    }

    private static boolean isAtlasTexture(Identifier id) {
        return id.getPath().startsWith("textures/atlas/")
                && id.getPath().endsWith(".png");
    }

    /**
     * Whether materials for this texture are owned elsewhere and only cached here:
     * the block atlas by {@code BlockTextureManager}, the particle atlas by
     * {@code ParticleAtlasManager}. This submitter never destroys them.
     */
    private static boolean isBorrowedAtlasMaterial(Identifier textureId) {
        return BlockTextureManager.BLOCK_ATLAS_ID.equals(textureId)
                || PARTICLE_ATLAS_ID.equals(textureId);
    }

    /** Builds parallel LabPBR textures for runtime-stitched entity atlases. */
    private EntityTextureSet tryCreateAtlasTextureSet(Identifier textureId) {
        if (failedTextures.contains(textureId)) return null;
        try {
            var client = Minecraft.getInstance();
            if (client == null) return null;
            AtlasSnapshot snapshot = snapshotAtlas(client, textureId);
            if (snapshot == null) return null;
            PreparedTextureSet prepared = prepareAtlasTextureSet("atlas_" + textureId, snapshot,
                    renderThreadVariants(client.getResourceManager()));
            return createAtlasTextureSet(textureId, prepared);
        } catch (Exception e) {
            LOGGER.error("Failed to create atlas textures for {}: {}", textureId, e.getMessage(), e);
            failedTextures.add(textureId);
            return null;
        }
    }

    /**
     * Copies what the atlas LabPBR build needs out of the live atlas: the stitched albedo
     * and each sprite's placement. Render thread only, since the sprites' images belong to
     * the atlas and a resource reload frees them here. Records a permanent failure and
     * returns null when the id is not a stitched atlas or has no size yet.
     */
    private AtlasSnapshot snapshotAtlas(Minecraft client, Identifier textureId) {
        AbstractTexture tex = client.getTextureManager().getTexture(textureId);
        if (!(tex instanceof TextureAtlas atlas)) {
            LOGGER.warn("Atlas texture {} is not a TextureAtlas", textureId);
            failedTextures.add(textureId);
            return null;
        }

        int w = BlockTextureManager.atlasWidth(atlas);
        int h = BlockTextureManager.atlasHeight(atlas);
        if (w == 0 || h == 0) {
            failedTextures.add(textureId);
            return null;
        }
        byte[] pixels = BlockTextureManager.stitchAtlasRGBA(atlas);
        Map<Identifier, TextureAtlasSprite> sprites = ((SpriteAtlasAccessor) atlas).getSprites();
        Identifier[] spriteIds = new Identifier[sprites.size()];
        int[] spriteRects = new int[sprites.size() * 4];
        boolean[] animated = new boolean[sprites.size()];
        int i = 0;
        for (Map.Entry<Identifier, TextureAtlasSprite> entry : sprites.entrySet()) {
            TextureAtlasSprite sprite = entry.getValue();
            spriteIds[i] = entry.getKey();
            spriteRects[i * 4] = Math.round(sprite.getU0() * w);
            spriteRects[i * 4 + 1] = Math.round(sprite.getV0() * h);
            spriteRects[i * 4 + 2] = sprite.contents().width();
            spriteRects[i * 4 + 3] = sprite.contents().height();
            animated[i] = ((SpriteContentsAccessor) sprite.contents()).getAnimation() != null;
            i++;
        }
        return new AtlasSnapshot(pixels, w, h, spriteIds, spriteRects, animated);
    }

    /**
     * Builds an atlas's parallel LabPBR channels from its snapshot and each sprite's pack
     * variants. Reads nothing live, so it runs on the loader thread as readily as inline.
     * Applies the {@code _n} ambient occlusion to the snapshot's albedo in place.
     */
    private static PreparedTextureSet prepareAtlasTextureSet(String name, AtlasSnapshot snapshot,
                                                             LabPBRConverter.VariantCache variants) {
        int w = snapshot.width();
        int h = snapshot.height();
        byte[] pixels = snapshot.pixels();
        int pixelCount = w * h;
        byte[] normalData = new byte[pixelCount * 4];
        byte[] heightData = new byte[pixelCount];
        byte[] roughData = new byte[pixelCount];
        byte[] metalData = new byte[pixelCount];
        byte[] emissiveData = new byte[pixelCount * 4];
        byte[] sssData = new byte[pixelCount * 4];
        byte[] sssAlbedoData = LabPBRConverter.createSingleScatteringAlbedoFallback(pixels);
        Arrays.fill(heightData, (byte) 255);
        Arrays.fill(roughData, (byte) 180);
        fillFlatNormals(normalData);

        boolean hasNormal = false;
        boolean hasSpecular = false;
        boolean hasEmissive = false;
        boolean hasSss = false;
        Identifier[] spriteIds = snapshot.spriteIds();
        int[] rects = snapshot.spriteRects();
        Map<String, BlockTextureManager.AnimatedSpriteLabPBR> animationPbr = new HashMap<>();
        for (int i = 0; i < spriteIds.length; i++) {
            Identifier spriteId = spriteIds[i];
            int x = rects[i * 4];
            int y = rects[i * 4 + 1];
            int spriteW = rects[i * 4 + 2];
            int spriteH = rects[i * 4 + 3];
            NativeImage nImg = loadVariant(variants, spriteId, "_n");
            NativeImage sImg = loadVariant(variants, spriteId, "_s");
            NativeImage sssImg = loadVariant(variants, spriteId, "_sss");
            try {
                if (snapshot.animated()[i] && (nImg != null || sImg != null)) {
                    animationPbr.put(spriteId.toString(), BlockTextureManager.AnimatedSpriteLabPBR.fromVariants(spriteId, nImg, sImg));
                }
                if (nImg != null) {
                    LabPBRConverter.blitNormal(normalData, w, nImg, x, y, spriteW, spriteH);
                    LabPBRConverter.blitHeight(heightData, w, nImg, x, y, spriteW, spriteH);
                    LabPBRConverter.applyAO(pixels, w, nImg, x, y, spriteW, spriteH);
                    LabPBRConverter.applyAOToData(sssAlbedoData, w, nImg, x, y, spriteW, spriteH);
                    hasNormal = true;
                }
                if (sImg != null) {
                    LabPBRConverter.blitRoughness(roughData, w, sImg, x, y, spriteW, spriteH);
                    LabPBRConverter.blitMetallic(metalData, w, sImg, x, y, spriteW, spriteH);
                    hasSpecular = true;
                    if (LabPBRConverter.hasEmissiveData(sImg)) {
                        LabPBRConverter.blitEmissive(emissiveData, w, pixels, sImg,
                                x, y, spriteW, spriteH, 0f);
                        hasEmissive = true;
                    }
                    if (LabPBRConverter.hasSSSData(sImg)) {
                        LabPBRConverter.blitSSS(sssData, w, sImg, x, y, spriteW, spriteH);
                        hasSss = true;
                    }
                }
                if (sssImg != null) {
                    LabPBRConverter.blitSingleScatteringAlbedo(
                            sssAlbedoData, w, sssImg, x, y, spriteW, spriteH);
                }
            } finally {
                if (nImg != null) nImg.close();
                if (sImg != null) sImg.close();
                if (sssImg != null) sssImg.close();
            }
        }

        PreparedTextureSet prepared = new PreparedTextureSet(name, true, pixels, w, h);
        if (hasNormal) {
            prepared.normal = normalData;
            prepared.heightMap = heightData;
        }
        if (hasSpecular) {
            prepared.roughness = roughData;
            prepared.metallic = metalData;
        }
        if (hasEmissive) {
            prepared.emissive = emissiveData;
        }
        if (hasSss) {
            prepared.sssThickness = sssData;
            prepared.sssAlbedo = sssAlbedoData;
        }
        prepared.hasPBR = hasNormal || hasSpecular || hasSss;
        prepared.spriteCount = spriteIds.length;
        prepared.spriteIds = Set.of(spriteIds);
        prepared.animationPbr = animationPbr;
        return prepared;
    }

    /** Uploads a prepared atlas set; a failed albedo upload marks the atlas failed. */
    private EntityTextureSet createAtlasTextureSet(Identifier textureId, PreparedTextureSet prepared) {
        EntityTextureSet textures = uploadPreparedTextureSet(prepared);
        if (textures == null) {
            failedTextures.add(textureId);
            return null;
        }
        textures.animation = AtlasAnimationForwarder.register(textureId, prepared.spriteIds, prepared.animationPbr,
                textures.albedo.getHandle(), textures.emissive == null ? 0 : textures.emissive.getHandle(),
                textures.roughness == null ? 0 : textures.roughness.getHandle(),
                textures.metallic == null ? 0 : textures.metallic.getHandle(),
                textures.sssThickness == null ? 0 : textures.sssThickness.getHandle());
        LOGGER.info("Created entity atlas textures for {} ({}x{}, {} sprites, LabPBR={})",
                textureId, prepared.width, prepared.height, prepared.spriteCount, textures.hasPBR);
        return textures;
    }

    public static void onMinecraftAtlasUploaded(Identifier atlasId) {
        RemixEntitySubmitter submitter = activeSubmitter;
        if (submitter != null) {
            submitter.refreshUploadedAtlas(atlasId);
        }
    }

    private void refreshUploadedAtlas(Identifier atlasId) {
        if (!isAtlasTexture(atlasId)) return;
        failedTextures.remove(atlasId);
        if (textureSets.containsKey(atlasId) || textureLoader.isPending(atlasId)
                || isBorrowedAtlasMaterial(atlasId)) {
            // Rebuild the full texture set. Updating albedo alone would leave AO,
            // emissive, and every parallel LabPBR atlas out of sync. A borrowed
            // material is rebuilt by its owner; the meshes bound to it here must go.
            // A set still being prepared was stitched from the old upload, so it is
            // dropped too.
            invalidateMaterialTexture(atlasId);
        }
    }

    private void refreshDynamicTexture(Identifier textureId) {
        DynamicTextureCache.Pixels pixels = DynamicTextureCache.get(textureId);
        if (pixels == null || pixels.version() <= 0L) return;

        Long appliedVersion = dynamicTextureVersions.get(textureId);
        if (appliedVersion != null && appliedVersion >= pixels.version()) return;

        EntityTextureSet textures = textureSets.get(textureId);
        if (textures == null || textures.albedo == null || textures.albedo.isDeleted()) return;

        if (textures.hasPBR) {
            // AO and emissive are derived from albedo; rebuild the complete set.
            invalidateMaterialTexture(textureId);
            return;
        }

        TextureSize oldSize = dynamicTextureSizes.get(textureId);
        if (oldSize == null || oldSize.width != pixels.width() || oldSize.height != pixels.height()) {
            invalidateMaterialTexture(textureId);
            return;
        }

        try {
            updateTextureFromMemory(textures.albedo, pixels.data(), pixels.width(), pixels.height());

            dynamicTextureVersions.put(textureId, pixels.version());
            dynamicTextureSizes.put(textureId, new TextureSize(pixels.width(), pixels.height()));
        } catch (Exception e) {
            LOGGER.warn("Failed to update dynamic texture {} after Minecraft upload; recreating on next use: {}",
                    textureId, e.getMessage());
            invalidateMaterialTexture(textureId);
        }
    }

    private void updateTextureFromMemory(RemixTexture texture, byte[] pixels, int width, int height) {
        ByteBuffer pixelData = DirectBufferPool.acquire(pixels.length);
        try {
            pixelData.put(pixels).flip();

            TexturePatch patch = new TexturePatch();
            patch.dstX = 0;
            patch.dstY = 0;
            patch.width = width;
            patch.height = height;
            patch.pixelData = pixelData;
            patch.rowPitch = width * 4;
            patch.format = TextureFormat.RGBA8_UNORM;

            TextureBatchEntry entry = new TextureBatchEntry();
            entry.handle = texture.getHandle();
            entry.patches = new TexturePatch[] { patch };

            TexturePatchInfo info = new TexturePatchInfo();
            info.entries = new TextureBatchEntry[] { entry };
            api.updateTextureFromMemory(info);
        } finally {
            DirectBufferPool.release(pixelData);
        }
    }

    private void invalidateMaterialTexture(Identifier textureId) {
        invalidateMeshesForTexture(textureId);
        Iterator<Map.Entry<EntityMaterialKey, RemixMaterial>> materials = materialCache.entrySet().iterator();
        while (materials.hasNext()) {
            Map.Entry<EntityMaterialKey, RemixMaterial> entry = materials.next();
            if (!textureId.equals(entry.getKey().textureId())) continue;
            RemixMaterial material = entry.getValue();
            materials.remove();
            if (!isBorrowedAtlasMaterial(textureId) && material != null && !material.isDeleted()) {
                material.destroy();
            }
        }
        EntityTextureSet textures = textureSets.remove(textureId);
        if (textures != null) textures.destroy();
        textureLoader.cancel(textureId);
        dynamicTextureSizes.remove(textureId);
        dynamicTextureVersions.remove(textureId);
        failedTextures.remove(textureId);
    }

    private void invalidateMeshesForTexture(Identifier textureId) {
        retainedEntityParts.discardIf(
                part -> textureId.equals(part.meshKey.texId()),
                RemixEntitySubmitter::destroyRetainedPart);

        Iterator<Map.Entry<RigidMeshKey, PooledMesh>> rigidIt = rigidMeshPool.entrySet().iterator();
        while (rigidIt.hasNext()) {
            Map.Entry<RigidMeshKey, PooledMesh> entry = rigidIt.next();
            if (!textureId.equals(entry.getKey().texId())) continue;

            RemixMesh mesh = entry.getValue().mesh;
            rigidIt.remove();
            if (mesh != null && !mesh.isDeletedFast()) mesh.destroy();
        }

    }

    private record TextureSize(int width, int height) {}

    static Identifier deriveLabPBRSpriteId(Identifier textureId) {
        String path = textureId.getPath();
        if (path.startsWith("textures/") && path.endsWith(".png")) {
            return Identifier.fromNamespaceAndPath(textureId.getNamespace(),
                    path.substring("textures/".length(), path.length() - ".png".length()));
        }
        // Downloaded player skins use minecraft:skins/<content-hash>. Packs can
        // target one explicitly with textures/skins/<content-hash>_{n,s,sss}.png.
        if (path.startsWith("skins/") && path.length() > "skins/".length()) {
            return textureId;
        }
        return null;
    }

    /**
     * Builds a plain texture's LabPBR channels from its albedo and its pack's {@code _n},
     * {@code _s}, and {@code _sss} variants. Reads no live Minecraft or Remix state, so it
     * runs on the loader thread as readily as inline. Applies the {@code _n} ambient
     * occlusion to {@code albedoRGBA} in place.
     */
    private static PreparedTextureSet preparePlainTextureSet(String textureName,
                                                             byte[] albedoRGBA, int w, int h,
                                                             LabPBRConverter.VariantCache variants,
                                                             Identifier spriteId) {
        NativeImage nImg = loadVariant(variants, spriteId, "_n");
        NativeImage sImg = loadVariant(variants, spriteId, "_s");
        NativeImage sssImg = loadVariant(variants, spriteId, "_sss");
        try {
            PreparedTextureSet prepared = new PreparedTextureSet(textureName, false, albedoRGBA, w, h);
            if (nImg != null) {
                LabPBRConverter.applyAO(albedoRGBA, w, nImg, 0, 0, w, h);
                prepared.normal = new byte[w * h * 4];
                prepared.heightMap = new byte[w * h];
                LabPBRConverter.blitNormal(prepared.normal, w, nImg, 0, 0, w, h);
                LabPBRConverter.blitHeight(prepared.heightMap, w, nImg, 0, 0, w, h);
                prepared.hasPBR = true;
            }
            if (sImg != null) {
                prepared.roughness = new byte[w * h];
                prepared.metallic = new byte[w * h];
                LabPBRConverter.blitRoughness(prepared.roughness, w, sImg, 0, 0, w, h);
                LabPBRConverter.blitMetallic(prepared.metallic, w, sImg, 0, 0, w, h);
                if (LabPBRConverter.hasEmissiveData(sImg)) {
                    prepared.emissive = new byte[w * h * 4];
                    LabPBRConverter.blitEmissive(prepared.emissive, w, albedoRGBA, sImg,
                            0, 0, w, h, 0f);
                }
                if (LabPBRConverter.hasSSSData(sImg)) {
                    prepared.sssThickness = new byte[w * h * 4];
                    LabPBRConverter.blitSSS(prepared.sssThickness, w, sImg, 0, 0, w, h);
                }
                prepared.hasPBR = true;
            }
            // _sss only colors subsurface scattering. _s.B is the authoritative
            // activation mask, so the sss color alone must not turn the whole
            // entity texture into an SSS material.
            if (sssImg != null && prepared.sssThickness != null) {
                prepared.sssAlbedo = new byte[w * h * 4];
                LabPBRConverter.blitSingleScatteringAlbedo(prepared.sssAlbedo, w, sssImg,
                        0, 0, w, h);
            }
            return prepared;
        } finally {
            if (nImg != null) nImg.close();
            if (sImg != null) sImg.close();
            if (sssImg != null) sssImg.close();
        }
    }

    /**
     * Creates the Remix textures of a prepared set, on the render thread. Returns null, with
     * nothing left allocated, when the albedo upload fails, so the caller retries rather
     * than caching a set with no albedo.
     */
    private EntityTextureSet uploadPreparedTextureSet(PreparedTextureSet prepared) {
        int w = prepared.width;
        int h = prepared.height;
        EntityTextureSet textures = new EntityTextureSet();
        try {
            textures.albedo = uploadAndOwn(textures, prepared.channelName("a"), prepared.albedo,
                    w, h, TextureFormat.RGBA8_UNORM, TexturePurpose.COLOR);
            if (textures.albedo == null) {
                textures.destroy();
                return null;
            }
            if (prepared.normal != null) {
                textures.normal = uploadAndOwn(textures, prepared.channelName("n"),
                        prepared.normal, w, h, TextureFormat.RGBA8_UNORM, TexturePurpose.NORMAL_DX);
            }
            if (prepared.heightMap != null) {
                textures.height = uploadAndOwn(textures, prepared.channelName("h"),
                        prepared.heightMap, w, h, TextureFormat.R8_UNORM, TexturePurpose.HEIGHTMAP);
            }
            if (prepared.roughness != null) {
                textures.roughness = uploadAndOwn(textures, prepared.channelName("r"),
                        prepared.roughness, w, h, TextureFormat.R8_UNORM, TexturePurpose.DATA);
            }
            if (prepared.metallic != null) {
                textures.metallic = uploadAndOwn(textures, prepared.channelName("m"),
                        prepared.metallic, w, h, TextureFormat.R8_UNORM, TexturePurpose.DATA);
            }
            if (prepared.emissive != null) {
                textures.emissive = uploadAndOwn(textures, prepared.channelName("e"),
                        prepared.emissive, w, h, TextureFormat.RGBA8_UNORM, TexturePurpose.COLOR);
            }
            if (prepared.sssThickness != null) {
                textures.sssThickness = uploadAndOwn(textures, prepared.channelName("sss"),
                        prepared.sssThickness, w, h, TextureFormat.RGBA8_UNORM, TexturePurpose.DATA);
            }
            // A plain texture's _sss colour means nothing without its _s mask's texture; an
            // atlas uploads both whenever any sprite has a mask, as it always has.
            if (prepared.sssAlbedo != null && (prepared.atlas || textures.sssThickness != null)) {
                textures.sssAlbedo = uploadAndOwn(textures, prepared.channelName("ssa"),
                        prepared.sssAlbedo, w, h, TextureFormat.RGBA8_UNORM, TexturePurpose.DATA);
            }
            textures.hasPBR = prepared.hasPBR;
        } catch (RuntimeException e) {
            textures.destroy();
            throw e;
        }
        return textures;
    }

    private RemixMaterial createMaterial(EntityMaterialKey key, EntityTextureSet textures) {
        MaterialInfoOpaqueEXT.Builder opaque = MaterialInfoOpaqueEXT.builder()
                .albedoConstant(1f, 1f, 1f)
                .roughnessConstant(textures.roughness != null ? 1f : 0.7f)
                .metallicConstant(textures.metallic != null ? 1f : 0f);

        switch (key.surfaceMode()) {
            case OPAQUE -> { }
            case CUTOUT -> opaque.alphaTestType(4)
                    .alphaReferenceValue(cutoutAlphaReferenceValue(key.textureId()));
            case TRANSLUCENT -> opaque.blendEnabled(true)
                    .blendType(MaterialInfoOpaqueEXT.BLEND_TYPE_ALPHA);
            case EMISSIVE -> opaque.blendEnabled(true)
                    .blendType(MaterialInfoOpaqueEXT.BLEND_TYPE_ALPHA_EMISSIVE);
        }
        if (MAP_DECORATIONS_ATLAS_TEXTURE.equals(key.textureId())
                && key.surfaceMode() == RenderLayerUtils.SurfaceMode.CUTOUT) {
            // Remix recognizes decal categories only for blend-enabled materials. The
            // half-coverage alpha test above still rejects the empty atlas texels.
            opaque.blendEnabled(true).blendType(MaterialInfoOpaqueEXT.BLEND_TYPE_ALPHA);
        }
        if (needsVertexColorBlend(key.textureId())) {
            // Banner bases and pattern decals share one atlas material.
            // It's the instance info that determines which surfaces are opaque and which blend, not the material itself.
            opaque.useDrawCallAlphaState(true);
        }
        if (textures.roughness != null) opaque.roughnessTexture(textures.roughness.getHandle());
        if (textures.metallic != null) opaque.metallicTexture(textures.metallic.getHandle());
        if (textures.height != null) {
            opaque.heightTexture(textures.height.getHandle()).displaceIn(POM_DISPLACE_IN);
        }
        boolean hasSubsurface = shouldAttachSubsurface(
                textures.sssThickness != null, key.surfaceMode());
        boolean diffusionProfile = hasSubsurface && shouldUseDiffusionProfile(key.surfaceMode());
        if (hasSubsurface) {
            opaque.subsurfaceExt(buildSubsurface(textures, diffusionProfile));
        }

        MaterialInfo.Builder materialBuilder = MaterialInfo.builder()
                .albedoTexture(textures.albedo.getHandle()).filterMode(0);
        if (textures.normal != null) materialBuilder.normalTexture(textures.normal.getHandle());
        RemixTexture emissive = key.surfaceMode() == RenderLayerUtils.SurfaceMode.EMISSIVE
                ? textures.albedo : textures.emissive;
        if (emissive != null) {
            materialBuilder.emissiveTexture(emissive.getHandle())
                    .emissiveIntensity(EMISSIVE_INTENSITY);
        }

        MaterialInfo info = materialBuilder.opaqueExt(opaque.build()).build();
        RemixMaterial material = new RemixMaterial(info, textures.ownedTextures, null);
        RemixMaterialStore.get().create(material);
        LOGGER.info("Created entity material {} (mode={}, LabPBR={}, normal={}, height={}, "
                        + "specular={}, emissive={}, sss={}, sssProfile={})",
                key.textureId(), key.surfaceMode(), textures.hasPBR,
                textures.normal != null, textures.height != null,
                textures.roughness != null || textures.metallic != null,
                emissive != null, hasSubsurface,
                hasSubsurface ? (diffusionProfile ? "diffusion" : "thin") : "none");
        return material;
    }

    static boolean shouldAttachSubsurface(boolean hasSssMask,
                                          RenderLayerUtils.SurfaceMode surfaceMode) {
        return hasSssMask
                && surfaceMode != RenderLayerUtils.SurfaceMode.TRANSLUCENT
                && surfaceMode != RenderLayerUtils.SurfaceMode.EMISSIVE;
    }

    static boolean shouldUseDiffusionProfile(RenderLayerUtils.SurfaceMode surfaceMode) {
        // Entity cutout layers are commonly a conservative model default for
        // closed character geometry and transparent overlay regions, rather
        // than evidence that the surface is physically thin. Preserve alpha
        // testing but use the solid diffusion profile for both opaque and
        // cutout entity materials.
        return surfaceMode == RenderLayerUtils.SurfaceMode.OPAQUE
                || surfaceMode == RenderLayerUtils.SurfaceMode.CUTOUT;
    }

    private static MaterialInfoOpaqueSubsurfaceEXT buildSubsurface(EntityTextureSet textures,
                                                                    boolean diffusionProfile) {
        MaterialInfoOpaqueSubsurfaceEXT.Builder builder = MaterialInfoOpaqueSubsurfaceEXT.builder()
                .transmittanceTexture(textures.albedo.getHandle());
        if (diffusionProfile) {
            // Match terrain diffusion materials: _sss supplies the RGB radius
            // texture when present, otherwise the albedo is the fallback.
            RemixTexture radiusTexture = textures.sssAlbedo != null
                    ? textures.sssAlbedo : textures.albedo;
            builder.diffusionProfile(true).radiusTexture(radiusTexture.getHandle())
                    .radiusScale(LabPBRConverter.DIFFUSION_SSS_RADIUS_SCALE)
                    .maxSampleRadius(LabPBRConverter.DIFFUSION_SSS_MAX_SAMPLE_RADIUS);
        } else {
            builder.diffusionProfile(false)
                    .singleScatteringAlbedo(0.5f, 0.5f, 0.5f)
                    .measurementDistance(THIN_SSS_MEASUREMENT_DISTANCE)
                    .volumetricAnisotropy(0.0f);
            if (textures.sssThickness != null) {
                builder.thicknessTexture(textures.sssThickness.getHandle());
            }
            if (textures.sssAlbedo != null) {
                builder.singleScatteringAlbedoTexture(textures.sssAlbedo.getHandle());
            }
        }
        return builder.build();
    }

    private RemixTexture uploadAndOwn(EntityTextureSet textures, String name, byte[] data,
                                      int width, int height, int format, int purpose) {
        RemixTexture texture = uploadPixels(name, data, width, height, format, purpose);
        textures.own(texture);
        return texture;
    }

    private static void fillFlatNormals(byte[] data) {
        for (int i = 0; i < data.length; i += 4) {
            data[i] = (byte) 128;
            data[i + 1] = (byte) 128;
            data[i + 2] = (byte) 255;
            data[i + 3] = (byte) 255;
        }
    }

    private static byte[] abgrToRGBA(NativeImage image, int w, int h) {
        byte[] out = new byte[w * h * 4];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int argb = image.getPixel(x, y);
                int abgr = (argb & 0xFF00FF00) | ((argb >> 16) & 0xFF) | ((argb & 0xFF) << 16);
                int idx = (y * w + x) * 4;
                out[idx]     = (byte) (abgr & 0xFF);
                out[idx + 1] = (byte) ((abgr >> 8) & 0xFF);
                out[idx + 2] = (byte) ((abgr >> 16) & 0xFF);
                out[idx + 3] = (byte) ((abgr >> 24) & 0xFF);
            }
        return out;
    }

    private RemixTexture uploadPixels(String name, byte[] data, int w, int h, int format, int purpose) {
        ByteBuffer buf = DirectBufferPool.acquire(data.length);
        buf.put(data).flip();
        try {
            RemixTexture tex = new RemixTexture(w, h, format, purpose, buf);
            return RemixTextureStore.get().create(tex);
        } catch (RemixException e) {
            LOGGER.error("Failed to upload texture {}: {}", name, e.getMessage());
            return null;
        }
    }

    private Transform buildWorldTransform(Vec3 pos) {
        reusableWorldTransform.setTranslation((float) pos.x, (float) pos.y, (float) pos.z);
        return reusableWorldTransform;
    }

    /**
     * Composes an entity/view-model root with one vanilla cuboid pivot matrix.
     * Both inputs use Transform's row-major 3x4 affine layout. Vertices with no
     * captured pivot ({@code transformIndex < 0}) already live under the root.
     */
    static void composeRigidTransform(Transform root,
                                      float[] transformData,
                                      int transformCount,
                                      int transformIndex,
                                      Transform out) {
        float[] r = root.matrix;
        float r0 = r[0], r1 = r[1], r2 = r[2], r3 = r[3];
        float r4 = r[4], r5 = r[5], r6 = r[6], r7 = r[7];
        float r8 = r[8], r9 = r[9], r10 = r[10], r11 = r[11];

        if (transformIndex < 0 || transformIndex >= transformCount
                || transformData == null || (transformIndex + 1) * 12 > transformData.length) {
            float[] dst = out.matrix;
            dst[0] = r0; dst[1] = r1; dst[2] = r2; dst[3] = r3;
            dst[4] = r4; dst[5] = r5; dst[6] = r6; dst[7] = r7;
            dst[8] = r8; dst[9] = r9; dst[10] = r10; dst[11] = r11;
            return;
        }

        int b = transformIndex * 12;
        float b0 = transformData[b], b1 = transformData[b + 1];
        float b2 = transformData[b + 2], b3 = transformData[b + 3];
        float b4 = transformData[b + 4], b5 = transformData[b + 5];
        float b6 = transformData[b + 6], b7 = transformData[b + 7];
        float b8 = transformData[b + 8], b9 = transformData[b + 9];
        float b10 = transformData[b + 10], b11 = transformData[b + 11];

        float[] dst = out.matrix;
        dst[0] = r0 * b0 + r1 * b4 + r2 * b8;
        dst[1] = r0 * b1 + r1 * b5 + r2 * b9;
        dst[2] = r0 * b2 + r1 * b6 + r2 * b10;
        dst[3] = r0 * b3 + r1 * b7 + r2 * b11 + r3;
        dst[4] = r4 * b0 + r5 * b4 + r6 * b8;
        dst[5] = r4 * b1 + r5 * b5 + r6 * b9;
        dst[6] = r4 * b2 + r5 * b6 + r6 * b10;
        dst[7] = r4 * b3 + r5 * b7 + r6 * b11 + r7;
        dst[8] = r8 * b0 + r9 * b4 + r10 * b8;
        dst[9] = r8 * b1 + r9 * b5 + r10 * b9;
        dst[10] = r8 * b2 + r9 * b6 + r10 * b10;
        dst[11] = r8 * b3 + r9 * b7 + r10 * b11 + r11;
    }

    /** Invalidates all resource-derived assets while retaining downloaded skin pixels. */
    public void reloadMaterials() {
        retainedEntityParts.clear(RemixEntitySubmitter::destroyRetainedPart);
        frameOpen = false;
        clearBeaconLensAssets();

        for (PooledMesh pooled : rigidMeshPool.values()) {
            if (!pooled.mesh.isDeletedFast()) pooled.mesh.destroy();
        }
        rigidMeshPool.clear();
        destroyMeshes(previousTransientMeshes);
        destroyMeshes(transientMeshes);

        for (Map.Entry<EntityMaterialKey, RemixMaterial> e : materialCache.entrySet()) {
            if (!isBorrowedAtlasMaterial(e.getKey().textureId())) {
                RemixMaterial mat = e.getValue();
                if (!mat.isDeleted()) mat.destroy();
            }
        }
        materialCache.clear();

        for (EntityTextureSet textures : textureSets.values()) textures.destroy();
        textureSets.clear();
        // Sets still being prepared read the packs being replaced; drop them and
        // everything already back. They hold only heap data, so nothing is freed here.
        textureLoader.invalidateAll();
        dynamicTextureSizes.clear();
        dynamicTextureVersions.clear();
        failedTextures.clear();
        resourceMissing.clear();
        missingMaterialSince.clear();
        warnedMissingMaterials.clear();
        labPBRVariants = null;
    }

    public void clear() {
        reloadMaterials();
        DynamicTextureCache.clear();
    }

    public void close() {
        clear();
        textureLoader.shutdown();
        if (activeSubmitter == this) {
            activeSubmitter = null;
        }
    }
}
