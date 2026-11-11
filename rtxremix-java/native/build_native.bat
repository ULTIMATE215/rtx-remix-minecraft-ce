:: SPDX-FileCopyrightText: Copyright (c) 2026 NVIDIA CORPORATION & AFFILIATES. All rights reserved.
:: SPDX-FileCopyrightText: Copyright (c) 2026 RTX Remix for Minecraft Community Edition contributors
:: SPDX-License-Identifier: Apache-2.0
::
:: Modified by RTX Remix for Minecraft Community Edition; see COMMUNITY.md.

@echo off
setlocal enabledelayedexpansion

REM ═══════════════════════════════════════════════════════════════════════
REM  build_native.bat: Compile remixbridge.dll using MSVC
REM  Usage: build_native.bat [x64|arm64] [test]   (default: x64)
REM  Requires: Visual Studio with C++ workload, JAVA_HOME set
REM
REM  Output goes to build/native/{win64|arm64}/ under the repository root.
REM  Runtime directories under remix-runtime/ can be Packman junctions into
REM  the shared cache. Never write bridge outputs there. Consumers layer the
REM  bridge over the runtime when staging or packaging.
REM ═══════════════════════════════════════════════════════════════════════

REM ── Target architecture ───────────────────────────────────────────────
set "ARCH=%~1"
if "%ARCH%"=="" set "ARCH=x64"

REM ── Map ARCH to the vcvarsall target ───────────────────────────────
set "VCVARS_EXTRA_ARGS="
if /I "%ARCH%"=="x64" (
    set "VCARCH=x64"
) else if /I "%ARCH%"=="arm64" (
    set "VCARCH=x64_arm64"
) else (
    echo ERROR: Unknown architecture: %ARCH%. Use x64 or arm64.
    exit /b 1
)

REM ── Find Visual Studio via vswhere ──────────────────────────────────
set "VSWHERE=%ProgramFiles(x86)%\Microsoft Visual Studio\Installer\vswhere.exe"
if not exist "%VSWHERE%" (
    echo ERROR: vswhere.exe not found. Is Visual Studio installed?
    exit /b 1
)

for /f "usebackq tokens=*" %%i in (`"%VSWHERE%" -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath`) do set "VS_PATH=%%i"
if not defined VS_PATH (
    echo ERROR: No Visual Studio installation found.
    exit /b 1
)

REM Select a complete ARM64 toolset. Side-by-side older toolsets may have
REM only x64 libraries even when their ARM64 compiler is present.
if /I "%ARCH%"=="arm64" (
    set "RTX_ARM64_TOOLSET="
    for /f "delims=" %%t in ('dir /b /ad /o-n "%VS_PATH%\VC\Tools\MSVC" 2^>nul') do (
        if not defined RTX_ARM64_TOOLSET if exist "%VS_PATH%\VC\Tools\MSVC\%%t\bin\Hostx64\arm64\cl.exe" if exist "%VS_PATH%\VC\Tools\MSVC\%%t\lib\arm64\libcpmt.lib" if exist "%VS_PATH%\VC\Tools\MSVC\%%t\lib\arm64\libcmt.lib" set "RTX_ARM64_TOOLSET=%%t"
    )
    if not defined RTX_ARM64_TOOLSET (
        echo ERROR: No complete MSVC ARM64 toolset found. Install C++ ARM64 build tools in Visual Studio Installer.
        exit /b 1
    )
    set "VCVARS_EXTRA_ARGS=-vcvars_ver=!RTX_ARM64_TOOLSET!"
    echo Using MSVC !RTX_ARM64_TOOLSET! for ARM64.
)

REM ── Set up MSVC environment for target arch ─────────────────────────
call "%VS_PATH%\VC\Auxiliary\Build\vcvarsall.bat" %VCARCH% %VCVARS_EXTRA_ARGS% >nul 2>&1
if errorlevel 1 (
    echo ERROR: vcvarsall.bat failed. Ensure the Desktop C++ workload and target architecture tools are installed.
    exit /b 1
)

REM ── Check JAVA_HOME ─────────────────────────────────────────────────
if not defined JAVA_HOME (
    echo ERROR: JAVA_HOME is not set.
    exit /b 1
)
if not exist "%JAVA_HOME%\include\jni.h" (
    echo ERROR: jni.h not found in %JAVA_HOME%\include
    exit /b 1
)

REM ── Remix C header (override with REMIX_INCLUDE env var) ────────────
if not defined REMIX_INCLUDE set "REMIX_INCLUDE=%~dp0include"
if not exist "%REMIX_INCLUDE%\remix_c.h" (
    echo ERROR: remix_c.h not found in %REMIX_INCLUDE%
    echo Set REMIX_INCLUDE to the directory containing remix_c.h
    exit /b 1
)

REM ── Output: build/native/{win64|arm64}/ (per-arch, repo-local) ──────
if /I "%ARCH%"=="x64" (
    set "OUT_ARCH=win64"
) else (
    set "OUT_ARCH=arm64"
)
set "OUT_DIR=%~dp0..\..\build\native\%OUT_ARCH%"
if not exist "%OUT_DIR%" mkdir "%OUT_DIR%"

if /I "%~2"=="test" goto testExports

echo Compiling remixbridge.dll for %OUT_ARCH% ...
cl /nologo /O2 /Zi /LD /EHsc /std:c++17 ^
   /I"%JAVA_HOME%\include" /I"%JAVA_HOME%\include\win32" /I"%REMIX_INCLUDE%" /I"%~dp0include" ^
   "%~dp0remixbridge.cpp" "%~dp0vulkan_exports.cpp" ^
   /Fe"%OUT_DIR%\remixbridge.dll" ^
   /Fo"%OUT_DIR%\\" ^
   /Fd"%OUT_DIR%\remixbridge.pdb" ^
   /link /nologo /DEBUG /DEF:"%~dp0vulkan_exports.def" dbghelp.lib

if errorlevel 1 (
    echo ERROR: Compilation failed.
    exit /b 1
)

echo remixbridge.dll built successfully: %OUT_DIR%\remixbridge.dll
exit /b 0

:testExports
cl /nologo /O2 /EHsc /std:c++17 ^
   /I"%JAVA_HOME%\include" /I"%JAVA_HOME%\include\win32" /I"%~dp0include" ^
   "%~dp0vulkan_exports_test.cpp" ^
   /Fe"%OUT_DIR%\vulkan_exports_test.exe" ^
   /Fo"%OUT_DIR%\vulkan_exports_test.obj"
if errorlevel 1 exit /b 1
"%OUT_DIR%\vulkan_exports_test.exe" "%OUT_DIR%\remixbridge.dll"
exit /b %errorlevel%
