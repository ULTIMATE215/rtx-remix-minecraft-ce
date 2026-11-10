# SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
# SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
# SPDX-License-Identifier: Apache-2.0
#
# Modified by RTX Remix for Minecraft Community Edition; see COMMUNITY.md.

import json
import re
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from rtx_tools import prism
from rtx_tools.benchmark import util

REPO_ROOT = util.PROJECT_DIR


def gradle_mod_version() -> str:
    return gradle_property("mod_version")


def gradle_property(name: str) -> str:
    text = (REPO_ROOT / "gradle.properties").read_text(encoding="utf-8")
    for line in text.splitlines():
        key, separator, value = line.partition("=")
        if separator and key.strip() == name and value.strip():
            return value.strip()
    raise AssertionError(f"gradle.properties has no non-empty {name}")


def gradle_property_expression(expression: str) -> str:
    match = re.fullmatch(r"providers\.gradleProperty\(['\"]([^'\"]+)['\"]\)\.get\(\)", expression)
    if not match:
        raise AssertionError(f"Unsupported Gradle version expression: {expression}")
    return gradle_property(match.group(1))


def gradle_dependency_version(group: str, artifact: str) -> str:
    text = (REPO_ROOT / "build.gradle").read_text(encoding="utf-8")
    match = re.search(rf'"{re.escape(group)}:{re.escape(artifact)}:([^\"]+)"', text)
    if not match:
        raise AssertionError(f"build.gradle has no {group}:{artifact} dependency")
    version = match.group(1)
    variable = re.fullmatch(r"\$\{(\w+)\}", version)
    if variable:
        assignment = re.search(rf"(?m)^\s*def\s+{re.escape(variable.group(1))}\s*=\s*(.+?)\s*$", text)
        if not assignment:
            raise AssertionError(f"build.gradle has no assignment for {variable.group(1)}")
        expression = assignment.group(1)
        literal = re.fullmatch(r"(['\"])([^'\"]+)\1", expression)
        return literal.group(2) if literal else gradle_property_expression(expression)
    interpolation = re.fullmatch(r"\$\{(.+)\}", version)
    if interpolation:
        return gradle_property_expression(interpolation.group(1))
    if "$" in version:
        raise AssertionError(f"Unsupported Gradle version expression: {version}")
    return version


def powershell_literal(path: Path, variable: str) -> str:
    text = path.read_text(encoding="utf-8")
    match = re.search(rf"\${re.escape(variable)}\s*=\s*'([^']+)'", text)
    if not match:
        raise AssertionError(f"{path} has no ${variable} string assignment")
    return match.group(1)


class GradleVersionParsingTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.root_patch = patch(f"{__name__}.REPO_ROOT", self.root)
        self.root_patch.start()
        self.addCleanup(self.root_patch.stop)

    def write_build(self, build: str, properties: str = "") -> None:
        (self.root / "build.gradle").write_text(build, encoding="utf-8")
        (self.root / "gradle.properties").write_text(properties, encoding="utf-8")

    def test_literal_and_variable_literal_versions_are_supported(self):
        self.write_build(
            'def loaderVersion = "0.20.1"\nmodImplementation "net.fabricmc:fabric-loader:${loaderVersion}"'
        )
        self.assertEqual("0.20.1", gradle_dependency_version("net.fabricmc", "fabric-loader"))
        self.write_build('minecraft "com.mojang:minecraft:1.21.2"')
        self.assertEqual("1.21.2", gradle_dependency_version("com.mojang", "minecraft"))

    def test_property_backed_variable_is_resolved_from_gradle_properties(self):
        self.write_build(
            "def minecraftVersion = providers.gradleProperty('minecraft_version').get()\n"
            'minecraft "com.mojang:minecraft:${minecraftVersion}"',
            "minecraft_version=1.21.2\n",
        )
        self.assertEqual("1.21.2", gradle_dependency_version("com.mojang", "minecraft"))

    def test_inline_property_is_resolved_from_gradle_properties(self):
        self.write_build(
            "def api = \"net.fabricmc.fabric-api:fabric-api:${providers.gradleProperty('fabric_api_version').get()}\"",
            "fabric_api_version=0.106.1+1.21.2\n",
        )
        self.assertEqual("0.106.1+1.21.2", gradle_dependency_version("net.fabricmc.fabric-api", "fabric-api"))

    def test_missing_and_empty_properties_fail(self):
        for properties in ("", "minecraft_version=\nfabric_loader_version=0.20.1\n"):
            with self.subTest(properties=properties):
                self.write_build(
                    "def minecraftVersion = providers.gradleProperty('minecraft_version').get()\n"
                    'minecraft "com.mojang:minecraft:${minecraftVersion}"',
                    properties,
                )
                with self.assertRaisesRegex(AssertionError, "no non-empty minecraft_version"):
                    gradle_dependency_version("com.mojang", "minecraft")

    def test_unknown_version_expression_fails(self):
        self.write_build(
            'def minecraftVersion = computeVersion()\nminecraft "com.mojang:minecraft:${minecraftVersion}"'
        )
        with self.assertRaisesRegex(AssertionError, "Unsupported Gradle version expression"):
            gradle_dependency_version("com.mojang", "minecraft")

    def test_resolved_version_drift_still_fails_the_metadata_contract(self):
        self.write_build(
            "def minecraftVersion = providers.gradleProperty('minecraft_version').get()\n"
            'minecraft "com.mojang:minecraft:${minecraftVersion}"\n'
            'modImplementation "net.fabricmc:fabric-loader:0.20.1"\n'
            'modImplementation "net.fabricmc.fabric-api:fabric-api:0.106.1+1.21.2"',
            "minecraft_version=1.21.2\n",
        )
        resources = self.root / "src" / "main" / "resources"
        resources.mkdir(parents=True)
        metadata = {
            "license": "Apache-2.0",
            "depends": {"minecraft": "1.21.1", "fabricloader": ">=0.20.1", "fabric-api": ">=0.106.1+1.21.2"},
        }
        (resources / "fabric.mod.json").write_text(json.dumps(metadata), encoding="utf-8")
        with self.assertRaisesRegex(AssertionError, "1.21.2"):
            VersionConsistencyTests().test_fabric_metadata_matches_repository_contract()


class VersionConsistencyTests(unittest.TestCase):
    def test_fabric_test_loader_matches_runtime_loader(self):
        self.assertEqual(
            gradle_dependency_version("net.fabricmc", "fabric-loader"),
            gradle_dependency_version("net.fabricmc", "fabric-loader-junit"),
        )

    def test_packaging_version_defaults_match_gradle(self):
        expected = gradle_mod_version()
        self.assertEqual(
            expected,
            powershell_literal(REPO_ROOT / "packaging" / "installer.ps1", "script:APP_VERSION"),
        )

    def test_runtime_dependency_versions_match_gradle(self):
        self.assertEqual(int(gradle_property("release_java_version")), prism.GAME_JAVA_MAJOR)
        expected = {
            "minecraft": gradle_dependency_version("com.mojang", "minecraft"),
            "fabric_loader": gradle_dependency_version("net.fabricmc", "fabric-loader"),
            "fabric_api": gradle_dependency_version("net.fabricmc.fabric-api", "fabric-api"),
        }
        installer = REPO_ROOT / "packaging" / "installer.ps1"

        for path, variables in (
            (
                installer,
                {
                    "minecraft": "script:MC_VERSION",
                    "fabric_loader": "script:FABRIC_LOADER_VER",
                    "fabric_api": "script:FABRIC_API_VERSION",
                },
            ),
        ):
            with self.subTest(path=path):
                for component, variable in variables.items():
                    self.assertEqual(expected[component], powershell_literal(path, variable))

    def test_runtime_code_does_not_hardcode_mod_version(self):
        benchmark_controller = (
            REPO_ROOT
            / "src"
            / "main"
            / "java"
            / "com"
            / "nvidia"
            / "rtxremix"
            / "minecraft"
            / "benchmark"
            / "BenchmarkController.java"
        ).read_text(encoding="utf-8")
        mod_entrypoint = (
            REPO_ROOT / "src" / "main" / "java" / "com" / "nvidia" / "rtxremix" / "minecraft" / "RtxRemixMod.java"
        ).read_text(encoding="utf-8")
        self.assertNotRegex(benchmark_controller, r'modVersion",\s*"\d+\.\d+\.\d+"')
        self.assertNotRegex(mod_entrypoint, r"Renderer v\d+\.\d+\.\d+")

    def test_fabric_metadata_matches_repository_contract(self):
        metadata = json.loads(
            (REPO_ROOT / "src" / "main" / "resources" / "fabric.mod.json").read_text(encoding="utf-8")
        )
        minecraft_version = gradle_dependency_version("com.mojang", "minecraft")
        fabric_loader_version = gradle_dependency_version("net.fabricmc", "fabric-loader")
        fabric_api_version = gradle_dependency_version("net.fabricmc.fabric-api", "fabric-api")

        self.assertEqual("Apache-2.0", metadata["license"])
        self.assertEqual(minecraft_version, metadata["depends"]["minecraft"])
        self.assertEqual(f">={fabric_loader_version}", metadata["depends"]["fabricloader"])
        self.assertEqual(f">={fabric_api_version}", metadata["depends"]["fabric-api"])
        self.assertEqual(f">={gradle_property('release_java_version')}", metadata["depends"]["java"])
        self.assertNotIn("fabric-api", metadata.get("suggests", {}))

        icon = metadata.get("icon")
        if icon:
            self.assertTrue((REPO_ROOT / "src" / "main" / "resources" / icon).is_file())

        sources = metadata.get("contact", {}).get("sources", "")
        self.assertNotIn("rtxremix-java", sources)

    def test_readme_license_matches_repository_license(self):
        license_text = (REPO_ROOT / "LICENSE").read_text(encoding="utf-8")
        readme = (REPO_ROOT / "README.md").read_text(encoding="utf-8")

        self.assertIn("Apache License", license_text)
        self.assertIn("Apache-2.0", readme)
        self.assertNotRegex(readme, r"(?m)^MIT\s+[\u2014-]\s+See LICENSE file$")


if __name__ == "__main__":
    unittest.main()
