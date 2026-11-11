<!--
SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
SPDX-License-Identifier: Apache-2.0

Modified by RTX Remix for Minecraft Community Edition; see COMMUNITY.md.
-->

# RTX Remix for Minecraft

> **RTX Remix for Minecraft Community Edition (Unofficial).** An unofficial derivative of
> [NVIDIAGameWorks/rtx-remix-minecraft](https://github.com/NVIDIAGameWorks/rtx-remix-minecraft)
> at commit `a518eff`, with lighting, weather, water, night sky, and motion blur improvements.
> Not affiliated with or endorsed by NVIDIA. See [COMMUNITY.md](COMMUNITY.md) for what changed
> and how it is maintained. This branch targets Minecraft 26.2.
>
> **RTX Remix for Minecraft 社区版（非官方）**，基于上方官方仓库的衍生版本，与 NVIDIA 无关联。
> 改动与维护方式见 [COMMUNITY.md](COMMUNITY.md)。
>
> **Downloads / 下载**: select a package for your exact Minecraft version on
> [Releases](https://github.com/CuteEthanLeo/rtx-remix-minecraft-ce/releases), tagged
> `v<mod version>+mc<Minecraft version>`. Version-specific sources live on `mc/<version>` branches.
> The 26.2 source aligns with 1.21.1 ce.7. The dedicated
> [Build Minecraft 26.2 workflow](.github/workflows/build-26.2.yml) builds an installable Windows x64 JAR
> on pushes to `mc/26.2`; download it from the successful run's artifacts. Tests are excluded
> from that workflow, and game rendering remains unverified.
> 26.2 分支的 Actions 产物为可直接放入 `mods` 的 JAR，内含 Fabric API、Remix 运行时和必要资源；该工作流不运行测试或游戏验证。


This Fabric mod renders Minecraft Java Edition 26.2 through NVIDIA RTX Remix
for real-time path tracing on a GeForce RTX GPU.

## What To Expect

This is a beta. It was built as a technology showcase, but we love Minecraft
and want it to succeed as a rendering mod. We tuned it against one showcase
world, built for this project by [BlockWorks](https://www.blockworks.uk/) (see
[Showcase World And Art Pack](#showcase-world-and-art-pack)), and the places
that world visits are where it looks and runs best. We have worked hard to fix
as many issues as we could, but it ships with
[known issues](https://github.com/NVIDIAGameWorks/rtx-remix-minecraft/issues),
and there are certainly bugs yet to be discovered.

Three areas have had less attention than the rest:

- **Other mods.** It has had limited testing alongside other mods, and a
  renderer that takes over the scene the way this one does is likely to
  conflict with anything that changes rendering or the chunk pipeline.
- **A full playthrough.** Blocks, mobs, dimensions, and situations the showcase
  never reaches have had less care, and some will look wrong or run slowly.
- **Other versions.** This branch targets Minecraft 26.2. Use the matching
  `mc/<version>` branch and release package for another Minecraft version.

We hope the community helps fill it out. If something breaks,
[report it](docs/PLAYING.md#reporting-a-bug); if you can fix it, see
[Contributing](#contributing).

## Download

Download the JAR from the
[Releases page](https://github.com/CuteEthanLeo/rtx-remix-minecraft-ce/releases),
choosing a tag ending in `+mc26.2`. For branch builds, download the JAR artifact
from the successful **Build Minecraft 26.2** Actions run and extract the JAR
from GitHub's artifact ZIP.

## Install in three steps

1. Create a Minecraft 26.2 instance with Java 25 and Fabric Loader 0.20.1 or newer.
2. Copy the downloaded JAR into that instance's `mods` folder.
3. Launch the Fabric instance. The first launch extracts the bundled Remix runtime and resources.

Fabric API is included; no separate runtime download or installer is needed.
这是 Minecraft 模组 JAR，放入对应 Fabric 实例的 `mods` 目录即可使用。

The step-by-step version, with launcher advice and troubleshooting, is in
[Playing](docs/PLAYING.md).

## Requirements

- Windows 10 or 11, x64 or ARM64.
- Minecraft Java Edition 26.2, on a Microsoft account that owns the game.
- A GeForce RTX 30-series or newer GPU with NVIDIA driver 616.92 or newer.
- About 5 GB of free disk space while installing; about 3 GB stays in use.
- An internet connection to download Minecraft, Fabric Loader, and the mod.
- 16 GB of system memory is recommended.

The JAR bundles Fabric API and the RTX Remix runtime. Select Java 25 and
install Fabric Loader through your launcher.

Building and running from source has its own
[prerequisites](docs/GETTING_STARTED.md#prerequisites), including Prism
Launcher, signed in to a Microsoft account that owns Minecraft: Java Edition.

On Windows with the build prerequisites installed, run
`.\gradlew.bat build -Parch=win64`. The installable JAR is in `build/libs/`.
Omit `-Parch` to bundle both Windows x64 and ARM64 in one JAR.

## Crash Reports And Privacy

The mod collects no data of its own. The RTX Remix runtime it carries can send
basic session information and crash reports to NVIDIA. Its First Use Guide asks
about both, with both on by default, and the Permissions tab of the RTX Remix
Graphics Settings menu changes them at any time.
[Crash Reports And Privacy](docs/PLAYING.md#crash-reports-and-privacy) lists
what is sent.

## What it does

**Every block, mob, and item is lit by a path tracer.** Light bounces off walls,
through glass, and out of torches the way it does in a film renderer, so shadows,
reflections, and the colour a nearby block casts on its neighbours are all
computed rather than painted in.

**The look follows the game.** Time of day, weather, and being underwater each
bring their own lighting, and a map author can attach a look to a place in the
world. Nothing needs switching on.

**A new block.** The Copper Beacon is a directional lamp you can place or
carry, and its beam is a real light that picks out walls, rain, and anything
standing in it.

**Camera flythroughs.** Record a path by walking it, then replay it, for a video
or for an exact before-and-after comparison.

**A settings screen in the game.** Distances, framerate, sky, lighting,
exposure, and materials, all in Options, with no config files to edit, and a
button into Remix's own Graphics Settings for the quality tier.

## Showcase World And Art Pack

Two optional extras go with the mod. The developer launcher in
[`tools/`](tools/README.md) downloads both the first time it runs and launches
into the world with the pack on.

- **The showcase world**, the map the renderer was tuned against, was built for
  this project by BlockWorks: [website](https://www.blockworks.uk/),
  [YouTube](https://www.youtube.com/@BlockWorksYT),
  [X](https://x.com/BlockWorksYT), and
  [Instagram](https://www.instagram.com/blockworks/).
- **The RTX art pack**, a texture resource pack, was created by an art team at
  NVIDIA. Its normal and specular maps follow the
  [LabPBR standard](https://shaderlabs.org/wiki/LabPBR_Material_Standard), so
  other renderers and shader packs that read LabPBR may be able to use it too,
  but it has only been tested with this mod. Its `_sss` subsurface maps are
  this mod's own addition, described in
  [Resource Packs](docs/RESOURCE_PACKS.md).

## Start Here

| You want to | Read |
|---|---|
| Play with path tracing | [Playing](docs/PLAYING.md) |
| Check the known issues | [Issue tracker](https://github.com/NVIDIAGameWorks/rtx-remix-minecraft/issues) |
| Play on a server | [Multiplayer](docs/PLAYING.md#multiplayer) |
| Configure the renderer | [Settings](docs/SETTINGS.md) |
| Look up a command, a key, or a mod-added block | [In-Game Guide](docs/IN_GAME_GUIDE.md) |
| Give your own map its own lighting | [Gameplay-driven profiles](packaging/rtx-remix/mods/minecraft-logic/README.md) |
| Make a texture pack this renderer understands | [Resource Packs](docs/RESOURCE_PACKS.md) |
| Record a camera flythrough | [Camera Paths](docs/CAMERA_PATHS.md) |
| Install, repair, or uninstall in detail | [packaging/INSTALL.md](packaging/INSTALL.md) |
| Build and run from source | [Getting Started](docs/GETTING_STARTED.md) |
| Tune the developer knobs behind the renderer | [Tuning](docs/TUNING.md) |
| Diagnose a rendering or native integration problem | [Rendering Diagnostics](docs/RENDERING_DIAGNOSTICS.md) |
| Contribute a change | [CONTRIBUTING.md](CONTRIBUTING.md) |
| Report a bug | [Reporting a Bug](docs/PLAYING.md#reporting-a-bug) |
| Report a security vulnerability | [SECURITY.md](SECURITY.md) |
| See what changed | [CHANGELOG.md](CHANGELOG.md) |

## Source And Tooling Layout

| Path | Purpose |
|---|---|
| `src/` | Mod implementation and JVM tests |
| `rtxremix-java/` | Minecraft-agnostic Java facade and JNI bridge over the RTX Remix C API |
| `tools/` | Developer launcher, benchmarks, profiling, SDK staging, and development installation |
| `packman/` | Packman bootstrap and runtime package pins |
| `packaging/` | Public installer, runtime defaults, and distribution payload |
| `docs/` | Player, authoring, and source-build guides |

NVIDIA maintains some release and CI tooling outside this repository. It is
not required by the public Gradle build, the developer tools, or the installed
mod.

## Contributing

External contributions are welcome, and the gaps above are where help matters
most: compatibility with other mods, coverage of the blocks, mobs, and places a
real playthrough reaches, and performance on hardware we do not have. Fork this
repository, create a development branch, and submit a pull request. Sign off
each commit with `git commit -s` to certify it under the
[Developer Certificate of Origin](https://developercertificate.org/); there is
no Contributor License Agreement to sign. See [CONTRIBUTING.md](CONTRIBUTING.md)
for the full guidelines.

## License

This project is licensed under the Apache License, Version 2.0 (`Apache-2.0`).
See the [LICENSE](LICENSE) file. Attribution notices are in [NOTICE](NOTICE),
and third-party software distributed with this project is documented in
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md). Release archives also carry
the RTX Remix runtime, whose NVIDIA components are covered by their own license
terms; THIRD_PARTY_NOTICES.md lists them.

NOT AN OFFICIAL MINECRAFT PRODUCT. NOT APPROVED BY OR ASSOCIATED WITH MOJANG OR
MICROSOFT. Minecraft is a trademark of Mojang Synergies AB.

NVIDIA, GeForce, and RTX are trademarks and/or registered trademarks of NVIDIA
Corporation in the U.S. and other countries.

## Experimental Forge 1.12.2

The [Forge module](forge-1.12.2/README.md) provides a separate Windows x64 build.
Its guide describes the implemented rendering features and current limitations.
