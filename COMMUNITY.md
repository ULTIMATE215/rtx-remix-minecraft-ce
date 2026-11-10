# RTX Remix for Minecraft Community Edition (Unofficial)

**中文 | [English](#english)**

## 这是什么

RTX Remix for Minecraft 社区版是 NVIDIA 开源项目
[RTX Remix for Minecraft](https://github.com/NVIDIAGameWorks/rtx-remix-minecraft)
（Apache-2.0）的**非官方**衍生版本，在官方 Fabric 1.21.1 mod 的基础上改进光照、天气、水面、夜空，并加入可选的动态模糊。

- **出处**：上游仓库 `NVIDIAGameWorks/rtx-remix-minecraft`，基线提交 `a518eff`（2026-11-03）。基线也记录在
  `src/main/java/com/nvidia/rtxremix/minecraft/community/CommunityEdition.java` 里，启动日志会打印。
- **非官方声明**：本版本与 NVIDIA、Mojang、Microsoft 均无关联，也未获得其认可。NVIDIA、GeForce、RTX 是 NVIDIA
  Corporation 的商标；Minecraft 是 Mojang Synergies AB 的商标。
- **许可证**：与上游相同，Apache License 2.0（见 `LICENSE`、`NOTICE`）。
- **兼容性**：mod id 保持 `rtx-remix-renderer`，可直接替换官方 jar，两者不能同时安装。已放置的铜信标不受影响。

## 改了什么

每一项都能在游戏里单独开关（选项 → RTX Remix 设置 → 社区版 / 画面与曝光 / 材质），方便与官方效果对比。

| 问题 | 改动 | 开关 | 主要文件 |
|---|---|---|---|
| 草地、泥土油亮 | 官方把 LabPBR 光滑度换算成 `(1-s)²`，而 Remix 读取的是感知粗糙度并会再平方一次。改为规范的 `1-s` | 材质 → 精确粗糙度（默认开） | `texture/LabPBRConverter.java` |
| 雨天没氛围、像晴天 | 雨/雷暴 conf：更浓的散射雾和飘动的非均匀雨幕，曝光不再把阴天提亮；阴天天空减光，雨中白天加入柔和环境光 | 社区版 → 阴天天气 | `confs/weather/*.conf`、`community/OvercastSky.java`、`sky/SkyRenderer.java` |
| 地面不湿 | 露天朝上的方块面在雨中按 LabPBR 孔隙度变暗并泛水光，雨停后约 90 秒变干；有遮挡处保持干燥 | 社区版 → 湿润地面 | `community/WetSurfaces.java`、`texture/WetAtlas.java`、地形捕获 |
| 雨天地面没有积水 | 雨越下越久，露天的土路、石头、木板等硬质地面上汇出镜面般的水坑（世界坐标贴花，按湿度逐级扩大），地面变干时慢慢缩小；草地、沙子、雪、苔藓、树叶、耕地不积水 | 社区版 → 积水坑 | `community/Puddles.java`、`texture/PuddleMask.java`、地形捕获 |
| 雨滴落地没有涟漪 | 下雨时露天地面的薄水膜上有动画的雨滴涟漪圈（法线贴图序列帧，随雨出现、雨停消失）；开启时隐藏原版的雨滴溅射粒子 | 社区版 → 雨滴涟漪 | `community/RainRipples.java`、地形捕获 |
| （新功能）体积云 | 写实尺度的体积云（云底约 y=400）：晴天是铺到地平线、底平顶亮的积云，7 km 高空有成片、纤细的丝状卷云，闪电从云里把雷暴云照亮，雷暴时长出 1500 格高、带砧状云顶的积雨云塔，下雨转为阴天。所有云都在天空穹顶里按体积光线步进，与大气共用同一个太阳、月亮和透射 LUT，并按空气透视融进地平线；相机周围几百格内另用同一个云场生成看不见的网格，只负责真实阴影和光柱；远处云挡住太阳时整片地面变暗，星星、银河、流星被云遮住。默认开启；之前手动选过「程序化」云的存档保持原样 | 云 → 体积云 / 体积云精度 / 云量 | `community/clouds/*`、`cloud/VolumetricCloudTiles.java`、`shaders/cloud_sky.comp` |
| 雨天云发棕、夜晚云发白 | 云的反照率与次表面散射随时间和天气变化：雨中风暴灰，夜里暗蓝 | 社区版 → 云层明暗 | `community/CloudTint.java`、`cloud/CloudRenderer.java` |
| 湖水发黑、水面单调 | 水体透射距离 1 m → 3.5 m，按生物群系分 9 种水色；水面单独成页并标记为动态水（双层流动波浪，雨中更粗糙）；岸边泡沫 | 社区版 → 真实水体 / 流动波浪 / 岸边浪花 | `community/WaterLook.java`、`community/ShoreFoam.java`、`terrain/*` |
| 夜晚单薄 | 6000 颗亮度、色温各异的星星，沿银河聚集；程序化银河带（满月时变淡）；月光更强更冷；夜间薄雾让灯光有光晕；夜间环境光默认 2.5 → 1.4 | 社区版 → 星空 | `community/StarField.java`、`community/NightSky*.java`、`confs/time/*.conf` |
| 晨昏没有体积光 | 太阳高度 2°–20° 时太阳对雾的散射最高放大到 4 倍，天空环境光对雾的散射降到 0.35，光柱和阴影的反差拉开；清晨是贴地、成团飘动的晨雾，黄昏是偏暖的薄霾，都强烈向前散射 | 社区版 → 光照与体积光 → 晨昏光柱 | `community/SunShafts.java`、`confs/time/dawn.conf`、`dusk.conf` |
| 雨天体积光平淡 | 地面仍是阴天光照，但太阳对雾的散射单独补偿回晴天的 20%（雷暴 8%），从云缝中照出斜光柱；雨幕噪声加到 3 个 octave，密度曲线更陡，雨幕之间有清楚的空隙 | 社区版 → 光照与体积光 → 云隙光柱 | `community/SunShafts.java`、`confs/weather/*.conf` |
| （新功能）闪电照亮雨幕 | 每道闪电沿高度放 3 个冷白光源，对雾的散射为 4 倍，照亮周围的雨幕并投下短暂的影子；远处闪电让天空环境光闪一下 | 社区版 → 光照与体积光 → 闪电照亮雨幕 | `community/LightningFlash.java`、`light/LightManager.java` |
| 白天「傻白」 | 白天天空散射 ×16 → ×11，阳光与阴影的明暗比更合理，阴影保持偏蓝，自动曝光把整体亮度提回；Remix 默认的中性灰、各向同性雾换成偏蓝、向前散射的空气透视（新 conf 层 `community/daylight.conf`，优先级 4001.5）；阴影提亮 1.4 → 1.2；日晕增益 10.35 → 4，太阳贴片缩小一半 | 社区版 → 光照与体积光 → 写实白天 | `community/DaylightSky.java`、`sky/AtmosphericSkyGenerator.java`、`shaders/sky_atmosphere.comp` |
| 体积光发糊 | froxel 网格三档：标准 16 px/48 层、高 12 px/64 层、极高 8 px/64 层（默认）；画质预设切换后重新写入 | 社区版 → 光照与体积光 → 体积光精度 | `settings/VolumetricQualitySettings.java` |
| （新功能）动态模糊 | Remix 后处理的运动模糊，四档质量（关/标准/高/电影）、快门和最大模糊长度可调；手和方块描边不模糊；色差和暗角保持关闭 | 画面与曝光 → 镜头效果 | `settings/CameraEffectsSettings.java` |
| （新功能）简体中文 | 全部设置与提示的中文翻译 | 跟随游戏语言 | `lang/zh_cn.json` |
| （实验性）CPU 和 GPU 轮流空等，帧率上不去 | 「顶级优化」：画面内容不变，只改调度、跳过看不见的工作。流水线呈现让 CPU 在 GPU 画第 N 帧时就准备第 N+1 帧（Present 不再在 CPU 上等 GL）；干燥水坑剔除在地面干时把全透明的水坑贴花移出场景；更激进的提交重叠默认关。`/rtx topopt ab` 在游戏里交替开关测提速 | 性能 → 顶级优化（实验性，默认关） | `community/perf/*`、`RemixRenderer.java`、`gl/GlInterop.java` |

另有一项维护性改动：官方 mod 从不覆盖已存在的 `rtx-remix/` conf，所以已安装官方版的玩家拿不到新的默认 conf。社区版在
`rtx-remix-superseded-defaults.txt` 中记录了旧默认 conf 的哈希：**完全未改动**的旧默认会被升级，改过的保持原样
（`SupersededPayloadDefaults.java`）。

### 实时调参

`/rtx ce` 列出当前值；`/rtx ce shafts|rainshafts|skyfill|halo|lightning <数值>` 只在本次运行中修改晨昏光柱峰值、云隙光柱比例、白天天空散射、日晕增益和闪电亮度，方便找到合适的数值后再改成默认值。conf 文件改动后用 `/rtx conf reload` 生效。

### 尚未完成 / 已知限制

- **第三人称玩家影子**：静态审查没有找到压掉影子的代码，需要在游戏里按复现步骤确认（见下方「验证」）后再修。
- 雨滴落在**水面**（湖、河）上的环形涟漪：仍用「雨中加快、加细的双层波浪」近似。露天地面上的积水坑和雨滴涟漪已经加入（见上表「积水坑」「雨滴涟漪」）。

### Minecraft 1.21.11 分支（`mc/1.21.11`）移植状态

以下记录 `mc/1.21.11` 分支当时的状态，不代表本目录的 26.2 版本，本次也没有重新验证该分支。该分支从 1.21.1 直接移植到 1.21.11，当时通过编译和 JVM 测试，但没有游戏内渲染验证。因原版接入点变化而停用或降级的功能包括：

- 雨雪在 Remix 画面中不绘制、不捕获。
- 原版星星顶点拿不到（星空只剩社区版程序化星场）；字形页镜像不填充。
- 动画贴图上传转发停用（`SpriteContentsUploadMixin` 已移除），动画方块贴图在 Remix 中可能不动。
- 区块可见性遍历的零分配移植、远区块探测、粗粒度视锥索引和区块存储环形重定位停用，改走原版逻辑。
- 只覆写 `emitQuads` 的第三方 FRAPI 模型（如连接纹理）不再捕获自定义面；cutout / cutout_mipped 不再区分。
- 方块模型零分配加权/多部件选择、玩家名字缓存停用。
- 手持物品的火焰和烟雾、手持方块物品的图层分类停用；手持光源的按手/按持有者判断可能退化；刷怪笼展示实体的影子不再抑制。
- 物品图集粒子、拾取物品与远古守卫者粒子组不再捕获。
- 性能统计中的实体数、方块实体数显示 -1。

### Minecraft 26.2 分支（`mc/26.2`）移植状态

26.2 以 `mc/1.21.1` 的 `0.1.0-ce.7` 为功能基线适配。源码使用 Mojang 官方名，依赖为 Fabric Loader 0.20.1、Fabric API 0.161.0+26.2、Loom 1.19.3、Gradle 9.8.2 和 Java 25；开发启动器、CI 与安装器使用同一 Java 主版本。

**源码迁移已进行静态检查；`mc/26.2` 的专用 [Actions 工作流](.github/workflows/build-26.2.yml) 负责 Windows x64 编译打包，安装包见成功运行的产物。本次按要求不运行测试、JNI 加载或游戏验证。以下适配范围不代表画面、性能或第三方模组兼容性已经验证。**

- 按 26.2 的 feature renderer 回放实体命令，包括文字、名牌、拴绳、着火、移动方块和附魔光效；复用原版提取的实体状态，恢复手持物品的持有者/手别、火焰和烟雾。原版圆形影子仍由 Remix 的几何阴影替代。
- 恢复雨滴折射和带雾雪片的合成、字形页镜像、多图集动画转发，以及物品图集、拾取物品和模型粒子捕获。
- 恢复 Fabric 自定义方块/流体几何、方块破坏裂纹、多部件选择、区块可见性遍历、远区块探测、粗粒度视锥索引、存储环形重定位、邻居重标记过滤和方块实体区段扫描；补回实体、方块实体和待上传区块计数。
- 相机路径同时更新客户端时钟并合并服务器时钟任务；带入 ce.7 的同帧粒子提交、异步实体纹理准备、帧生成感知镜头效果，以及天空/体积云读回与历史帧修正。
- 资源重载和世界切换释放捕获命令、模型、纹理和粒子引用。26.2 原版已改变加权模型选择、玩家名称和服务器区块列表实现，不再保留失效的旧 mixin；可见性遍历复用节点和队列，但原版 Octree 仍会分配对象，不能称为整个路径零分配。

## 安装（本地）

1. 使用专用 Actions 工作流的安装包，或在 Windows / Java 25 环境构建：`.\gradlew.bat package -Parch=win64`，产物位于 `build\distributions\`。
2. 安装到已有的官方安装目录（会自动备份被替换的 jar 和 conf）：
   ```powershell
   .\packaging\community-install.ps1 -GameDir 'D:\PCL\.minecraft\versions\RTX' -Jar '<上一步的 jar>'
   ```
3. 恢复官方版：`.\packaging\community-install.ps1 -GameDir '<同上>' -Restore`

## 维护

- **同步上游**：
  ```powershell
  git fetch upstream
  git merge upstream/main
  ```
  合并后把 `CommunityEdition.UPSTREAM_BASE` 和本文的基线提交改成新的上游提交。
- **改了打包的 conf**：把被替换版本的哈希追加到 `src/main/resources/rtx-remix-superseded-defaults.txt`
  （去掉 `\r` 后的 SHA-256，路径相对游戏目录），否则已安装的玩家收不到新默认值。
- **文件头**：新文件只写社区版署名；修改过的上游文件保留 NVIDIA 的 SPDX 行，在其下加一行社区版署名，并注明
  `Modified by RTX Remix for Minecraft Community Edition`（Apache-2.0 第 4(b) 条）。
- 社区版新增的逻辑尽量放在 `com.nvidia.rtxremix.minecraft.community` 包里，上游文件只留少量挂钩，减少合并冲突。

## 验证

- 单元测试：`.\gradlew.bat test`（新增：`CloudTintTest`、`StarFieldTest`、`WaterLookTest`、`WetSurfacesTest`、
  `CameraEffectsSettingsTest`、`SupersededPayloadDefaultsTest`、LabPBR 两种编码；顶级优化：`TopOptimizationTest`、
  `PresentWaitPolicyTest`、`TopOptimizationAbTest`、`PuddleDecalVisibilityTest`、`SubmitOverlapGuardTest`、
  `ClientDoneTimelineTest`、`FrameTimeHistogramTest`、`ChunkManagerShownFlagsTest`）。
- 顶级优化：游戏里执行 `/rtx topopt ab`，约 40 秒后聊天栏给出关/开两组的真实帧率、1% low 和提速倍数。
- 画面：在同一机位分别用官方 jar 和社区版 jar 截图对比。建议用 `/rtx shot mark <名字>` 固定机位，覆盖晴天草地、
  雨天草地、雨天湖面、海滩、夜晚城市。
- 玩家影子复现：`/weather clear` 等 10 秒，`/time set 1000`，F5，旁边放盔甲架并
  `/summon zombie ~2 ~ ~ {NoAI:1b}` 对照；分别在云开/关时看玩家、盔甲架、僵尸有没有影子。

---

<a id="english"></a>

## English

RTX Remix for Minecraft Community Edition is an **unofficial** derivative of NVIDIA's open-source
[RTX Remix for Minecraft](https://github.com/NVIDIAGameWorks/rtx-remix-minecraft) (Apache-2.0), based on upstream
commit `a518eff` (2026-11-03). It is not affiliated with or endorsed by NVIDIA, Mojang, or Microsoft. It keeps the
mod id `rtx-remix-renderer`, so it replaces the official JAR and cannot be installed beside it.

What changed, each switchable in game (Options → RTX Remix Settings → Community Edition, Image & Exposure, Materials):

- **Accurate Roughness** (Materials, on by default): LabPBR smoothness converts to `1 - s`, the perceptual roughness
  Remix samples, instead of upstream's `(1 - s)²`, which Remix squares again and so renders surfaces too glossy.
- **Overcast Weather**: denser, drifting rain haze; no exposure lift in rain; a dimmer overcast dome and a soft
  daytime sky ambient while it rains.
- **Wet Ground**: upward faces open to the sky darken and gain a water sheen in rain by LabPBR porosity, then dry over
  about 90 seconds.
- **Puddles**: as the rain goes on, near-mirror puddles gather on open hard ground such as paths, stone, and planks
  (world-space decals that grow with wetness) and shrink as the ground dries; grass, sand, snow, moss, leaves, and
  farmland never pool.
- **Rain Ripples**: while it rains, animated raindrop rings spread over a thin film of water on open ground and stop
  with the rain; vanilla rain splash particles are hidden while this is on.
- **Volumetric Clouds** (Clouds, on by default): real-scale cloudscapes with a base near y 400: cumulus fields to
  the horizon in fair weather, 1500-block cumulonimbus towers with anvils in thunder, overcast in rain. Distant clouds
  are ray-marched into the sky dome as volumes with the atmosphere's own sun, moon, and transmittance LUT and fade
  into the horizon by aerial perspective; within a few hundred blocks the same cloud field is also meshed, hidden from
  the camera, so near clouds shadow the ground and form light shafts. Thin, fibrous cirrus sheets lie 7 km up, and
  lightning lights the storm clouds from inside. A distant cloud over the sun dims the whole view, and clouds
  hide the stars, the Milky Way, and meteors behind them. Detail (Low/High/Ultra) and Cloud Cover are adjustable; a
  settings file that chose the Procedural style keeps it.
- **Cloud Shading**: clouds turn storm grey in rain and dim blue at night instead of staying white.
- **Realistic Water, Moving Waves, Shore Foam**: longer transmittance with nine biome water colours; water pages
  classified as Remix animated water with two drifting wave layers that roughen in rain; foam strips along shores.
- **Starry Night**: 6000 stars of varied brightness and colour that crowd toward a procedural Milky Way (domain-warped
  star clouds, a great rift and ridged dust filaments, a warm bulge and bluish arms, faint nebulae, and a grain of
  unresolved stars, in a 40-degree meandering band), which a full moon washes out; shooting stars that flare and fade
  along a falling great circle in real time (export time in a Flashback export), about every seven seconds and every 1.6 seconds on a shower night (one
  night in five); cooler moonlight three times upstream's that follows the moon phase (new moon 30%); thin night
  haze; the night sky ambient rect defaults to 0 instead of 2.5, since the dome already lights the night and the rect
  counted that sky twice in its cyan mean colour (which is what turned moonlit grass green); night auto exposure may
  reach EV -4.
- **Motion Blur** (Image & Exposure → Camera Effects): off, Standard, High, Cinematic, or Ultra (8/16/32/48
  samples), with a shutter speed from 1/250 s to 1/24 s (default 1/48 s) and a maximum blur size up to 0.25 of the
  screen. The shutter is a time: Remix's `exposureFraction` is per-frame motion times a share of a frame, so it is
  recomputed every frame from the smoothed frame time and a streak is equally long at any frame rate. Noise sampling
  is on at every step, so streaks are two-sided. The hand and block outline stay sharp.
- **Flashback Exports**: while Flashback exports a replay, which renders far slower than real time, shooting stars,
  the motion-blur frame time, the lightning flash, ground wetness and conf-layer blends follow the export's timeline
  instead of the wall clock, and Remix's own frame time (`rtx.timeDeltaBetweenFrames`, water animation and eye
  adaptation) is pinned to the export frame time, so the video moves as it would in play. Flashback is read by
  reflection and is not required.
- **Glowing Block Lights**: glowstone, sea lanterns, froglights, shroomlights, jack o'lanterns, lit redstone lamps
  and copper bulbs get a rect light on each exposed face (only the carved face for a jack o'lantern); lanterns, soul
  lanterns, and end rods get a sphere or cylinder. Before, they lit the scene only through emissive texels, which
  Remix does not sample as lights. Each is calibrated to a torch's emitted power at its light level and has its own
  intensity control.
- **Auto PBR** (Materials, on by default): for block textures without a LabPBR sidecar, a normal map is derived from
  the albedo's luminance (darker texels sit lower) and roughness and metalness come from the block's name
  (`texture/AutoPbr`): gold, iron, netherite, raw-metal, copper (bare and exposed), anvils, chains, and iron bars are
  metals; diamond, emerald, amethyst, ice, obsidian, quartz, polished stone, concrete, and glazed terracotta are
  glossy dielectrics; wood, soil, wool, and foliage stay rough. Darker texels are slightly rougher. An installed PBR
  pack's sidecars always win.
- **Linear AO**: LabPBR ambient occlusion multiplies the decoded albedo, not its sRGB bytes, which had applied AO
  raised to about 2.2.
- **Foliage energy**: the baseline leaves and grass `_sss` single-scattering albedo is halved on load. At its authored
  green 0.94, light bounced between blades and through leaves almost without loss, so foliage near a torch glowed
  fluorescent green.
- **Light & Haze** (Community Edition):
  - **Golden-Hour Shafts**: between about 2 and 20 degrees of sun elevation the sun scatters up to four times more
    into the haze and the sky ambient less, over a patchy dawn mist and a warm dusk haze, so trees and hills cast
    shafts.
  - **Cloud-Gap Shafts**: in rain the ground stays overcast, but the haze gets back a share of the clear-sky sun, so
    it shines through cloud gaps; rain curtains drift with clearer gaps between them.
  - **Lightning Glow**: bolts light the rain haze around them and throw brief, cold shadows; distant flashes lift
    the sky ambient.
  - **Natural Daylight**: one physical atmosphere (`sky/AtmosphereModel`, the Earth parameters of Hillaire 2020 with
    ozone, Mie absorption, and a multiple-scattering LUT built once) for both the sky dome and the sun and moon
    lights. Upstream lit the sky with empirical multipliers (16 by day, 70 by night) and dimmed the sun with a
    separate airmass formula three times stronger than the shader's air, so the sky gave half the sun's light at noon
    and over four times it at a low sun, and the noon sun was orange. Now the sky is lit by the distant light's own
    irradiance (pi times its radiance), the sky-to-sun ratio is the real one at every hour, the zenith is deep blue
    instead of cyan, the noon sun is near white, and the dome no longer paints a sun disc that was sampled as light a
    second time. The sun is a small round limb-darkened disc with a tight glow, tinted by the same transmittance; a
    blue, forward-scattering aerial perspective (`confs/community/daylight.conf`) replaces Remix's neutral default
    medium. Off restores the upstream sky, sun, and airmass.
  - **Haze Detail**: froxel grid Standard (16 px, 48 slices), High (12 px, 64), or Ultra (8 px, 64, the default).
  - `/rtx ce` tunes the shaft, sky-fill (1 = the physical sky brightness), halo (1 = physical), and lightning values
    for the session.
- **Simplified Chinese** translation of every setting.
- **Top Optimization** (Performance, experimental, off by default): frame-rate techniques that leave the image
  unchanged. Pipelined Present lets Present wait for GL's composite on the GPU, so the CPU builds frame N+1 while the
  GPU traces frame N; it blocks as before whenever a screen or Remix menu is open and for four frames after any resize,
  world change, or re-import. Dry Puddle Cull gives puddle decals their own pages and hides them while their mask is
  empty. Submit Overlap, off by default, skips the render thread's wait for the runtime's queue submission behind a
  per-frame self-check. Misbehaving techniques switch themselves off for the session. `/rtx topopt ab` measures the
  speedup in game, and the performance report logs the real wall-clock frame rate (`community/perf/*`).
- Shipped confs that an install still has in their untouched upstream form are upgraded on extraction; edited ones
  are kept (`SupersededPayloadDefaults`).

Known gaps: the third-person player shadow still needs an in-game reproduction before a fix; raindrop rings on open
water (lakes, rivers) are still approximated by faster, finer wave layers (puddles and ripples on open ground are
implemented, see above).

Maintenance: merge `upstream/main`, then update `CommunityEdition.UPSTREAM_BASE`; append the hash of every replaced
shipped conf to `rtx-remix-superseded-defaults.txt`; keep NVIDIA's SPDX line on modified files and add the Community
Edition line and modification notice below it.
