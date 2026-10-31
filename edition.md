# S Engine — Edition & Engineering Master Plan

> **Purpose:** This file is the source-of-truth implementation brief for AI coding agents and contributors working on S Engine. Read it before changing code. It defines the target architecture, OpenGL ES 3.0 migration, compatibility rules, delivery phases, and acceptance criteria.
>
> **Current target branch:** `arena/69b25c4c-assets2`  
> **Project:** Existing Kotlin Android game engine/editor in this repository.  
> **Current baseline (verify before editing):** Android min SDK 26, OpenGL ES 2.0 renderer, Kotlin, Rhino JavaScript runtime, custom scene/components, editor, physics, sprite animation, audio, particles and project ZIP import/export.

---

## 1. Mission

Evolve the existing S Engine into a reliable, modular, extensible 2D/3D Android game engine with a real in-app editor. Improve the actual working project incrementally; do not replace it with a demo, mock UI, empty scaffolding, or a new unrelated project.

The immediate milestone is **OpenGL ES 3.0 as the primary renderer baseline**, while preserving current game projects and existing working features. Design clean interfaces so higher capabilities (including ES 3.1/3.2 where available) can be added later without requiring them for the baseline.

### Non-negotiable rules

1. Inspect the actual repository, branch, Gradle configuration, manifest, renderer, shaders, assets, scene model, and tests before editing.
2. Preserve existing features and public scripting APIs unless a migration is necessary and documented.
3. Do not claim a feature is implemented unless real runtime code, integration, and tests support it.
4. No fake buttons, static/mock editor screens, placeholder implementations presented as finished, or hard-coded demo-only behavior.
5. Do not perform a wholesale rewrite. Keep changes small, buildable, reviewable, and staged.
6. Do not introduce Unity, Godot, LibGDX, or another game engine as a dependency. Prefer Android SDK and existing project dependencies. Any new library must be justified, maintained, license-compatible, and compatible with minSdk 26.
7. Keep existing project JSON and asset formats backward-compatible. Add explicit migrations only when unavoidable; never silently discard user data.
8. Use Kotlin for Android-side code unless the existing project proves a different language is already required. Do not introduce JNI/C++ as part of this renderer milestone without an explicit architectural reason and a separately reviewed plan.
9. Do not commit secrets, signing keys, local paths, generated build outputs, or large binaries.
10. If a requirement is ambiguous, inspect the code and document the decision rather than inventing repository facts.

## 2. Supported platform and graphics policy

- Android minimum: API 26 (Android 8.0); verify the real Gradle and manifest values before changing them.
- Primary renderer target: **OpenGL ES 3.0**.
- Compatibility renderer: retain ES 2.0 only if it is already functional and keeping it is practical. If it is retained, select the renderer at runtime and avoid calling ES 3-only APIs from the ES 2 path.
- Never assume every API-26 device supports ES 3.0/3.2 just because of its Android version. GPU, driver, EGL config, and context creation matter.
- Detect capabilities at runtime using EGL context creation and queried GL version/extensions. Log the vendor, renderer, GL version, GLSL version, and selected backend in a diagnostic screen/log.
- If ES 3.0 context creation fails, attempt the explicitly supported ES 2 fallback if available; otherwise show a useful error with device/driver details rather than crashing silently.
- Do not declare ES 3.0 as a hard required manifest feature until the product decision explicitly accepts filtering out devices without ES 3.0. Prefer a truthful manifest declaration and runtime capability check.
- Use GLSL ES 3.00 shaders for the ES 3 renderer (`#version 300 es`) and keep ES 2 shaders separate if fallback remains. Do not try to compile one shader source as both versions through string hacks.
- Treat the Samsung Galaxy J4 Core as a low-memory validation target (1 GB RAM class): avoid unbounded caches, large default render targets, unnecessary allocations in the frame loop, and oversized demo assets.

## 3. Target architecture

Keep the current package/layout when sensible. The following is a **logical module map**, not an instruction to move every file immediately. First map existing classes to these responsibilities and only refactor when it improves maintainability.

```text
app/
  src/main/
    AndroidManifest.xml
    java/com/sengine/
      app/                 Application setup, lifecycle and navigation
      core/                Engine facade, configuration, lifecycle, errors
      platform/android/    EGL/context, Surface lifecycle, input adapters
      loop/                Fixed/update/render scheduling and frame timing
      math/                Vectors, matrices, transforms, bounds
      scene/               Scene, node/GameObject, components, hierarchy
      serialization/       Scene/project JSON, versioning and migrations
      render/
        api/               Renderer interfaces, capabilities, render commands
        gles3/             ES 3.0 backend, shader programs, buffers, state
        gles2/             Optional existing ES 2 compatibility backend
        resources/         Texture, mesh, material, shader and GPU resource cache
        2d/                Sprite, text, tile and 2D batching
        3d/                Mesh submission, camera, depth/culling, lighting
        debug/             Grid, gizmos, bounds and diagnostic overlays
      assets/              Import, validation, metadata, references and cache
      input/               Touch, keyboard, gamepad and coordinate mapping
      physics/             Existing 2D/3D physics interfaces and implementation
      animation/            Sprite animation now; skeletal animation later
      audio/               Existing audio facade and lifecycle
      particles/            Existing particle runtime and renderer integration
      scripting/            Rhino runtime, API bindings, lifecycle and errors
      project/              Project manager, templates, ZIP import/export
      editor/               Editor state and commands
        viewport/
        hierarchy/
        inspector/
        assets/
        animation/
        materials/
        console/
      ui/                   Android screens and reusable UI
    assets/
    res/
  src/test/                 Unit tests for pure logic
  src/androidTest/          Device/instrumentation tests where configured
  .github/workflows/        Build/test CI
docs/
  architecture/
  decisions/
```

### Dependency direction

- UI/editor depends on engine APIs; renderer internals must not depend on Android editor screens.
- Scene data must not hold live OpenGL object IDs.
- Scene serialization must be independent of GPU resources and the current EGL context.
- Render commands consume scene/camera/material/mesh data; they must not mutate editor UI state.
- Platform lifecycle owns EGL and surface events. GPU resources must be recreated after context loss.
- Scripts use a stable, documented engine API rather than direct renderer internals.
- Keep responsibilities cohesive; avoid giant god classes and circular dependencies.

## 4. OpenGL ES 3.0 renderer requirements

### 4.1 Context and lifecycle

- Request an ES 3 context explicitly; validate the actual created context.
- Handle surface created/changed/destroyed, pause/resume, context loss, and recreation.
- Recreate shader programs, buffers, textures, and other GPU resources when the context is lost.
- Ensure GL calls run on the GL thread. Marshal resource uploads/deletes safely.
- Avoid leaks and stale GPU handles; resource wrappers must track validity and release ownership.

### 4.2 Render pipeline

Implement and verify in incremental steps:

1. Renderer interface and capability report.
2. Working ES 3 context and clear operation.
3. Existing scene output preserved on ES 3.
4. Shader/program compilation and actionable compile/link logs.
5. Vertex/index buffers and VAOs where appropriate.
6. Texture upload, filtering/wrapping, alpha handling, and cache.
7. 2D sprite rendering and batching without changing scene ordering semantics.
8. Text, particles, editor grid and gizmos.
9. 3D mesh path with model/view/projection transforms and depth testing.
10. Basic material parameters and lighting only after the above are stable.
11. Profiling counters: draw calls, visible objects, triangles, texture switches, frame time, cache size.

### 4.3 Renderer correctness

- Define and document coordinate systems, matrix layout, winding, depth range, alpha blend mode, color space assumptions, and texture-origin conventions.
- Validate shader attribute/uniform locations and report failures.
- Avoid querying expensive GL state every draw; cache state carefully and invalidate it correctly.
- Use sensible viewport dimensions and update projection on resize/rotation.
- Use explicit depth, blend, cull, scissor, and framebuffer state instead of relying on accidental previous state.
- Handle non-power-of-two textures according to ES 3 rules and actual sampling requirements.
- Avoid per-frame object/string/buffer allocations where possible.
- Keep debug rendering separable from game rendering.

## 5. Engine and editor behavior to preserve

Before and after each milestone, verify existing functionality including:

- Project create/open/rename/duplicate/delete and ZIP import/export.
- Scene save/load and all current scene/project JSON fields.
- Hierarchy parent/child, reorder, visibility, duplicate/delete and selection.
- Inspector edits, component add/remove/reorder and reset.
- Undo/redo for editor changes.
- Move/rotate/scale gizmos, snapping, pan/zoom, viewport resize and touch coordinate mapping.
- Play/pause/step/stop and restoration of edit-time scene state.
- Existing 2D sprites, text, sorting, pivots, UV animation and custom shaders.
- 2D and 3D physics behavior already implemented.
- Rhino script lifecycle callbacks, collision/trigger callbacks and existing API methods.
- Audio, particles, input/controller support, templates and player mode.
- Sprite-sheet animation editor and existing animation clip data.

Fix regressions rather than deleting the feature that exposed them.

## 6. Roadmap (complete one phase at a time)

### Phase 0 — Audit and baseline
- Map actual source files and architecture; inspect build configuration and CI.
- Build the current branch before changes; record failures that already exist.
- List existing renderer classes, shaders, GL calls, and all features using them.
- Record the current scene/project schema and add representative fixtures if absent.
- Deliver a short audit report and implementation checklist.

**Gate:** baseline status is known; no speculative architecture changes have been made.

### Phase 1 — ES 3.0 migration foundation
- Add renderer backend/capability interfaces with minimal disruption.
- Add ES 3 EGL/context selection and runtime capability diagnostics.
- Introduce GLSL ES 3.00 shader equivalents.
- Port existing clear/triangle or debug primitive only as an internal diagnostic, then port actual existing scene rendering; do not ship a triangle demo as the product.
- Preserve ES 2 fallback if practical and testable.
- Add resource/context-loss handling.

**Gate:** actual existing scenes render on ES 3; app survives resize, pause/resume, and context recreation.

### Phase 2 — ES 3 2D feature parity and performance
- Port sprites, text, particles, grid and gizmos.
- Validate alpha, ordering, UVs, pivots, and shader behavior.
- Add batching only with visual equivalence tests.
- Measure frame time and memory on low-end hardware.

**Gate:** no known regression in existing 2D/editor functionality.

### Phase 3 — Stable 3D rendering foundation
- Mesh/vertex/index formats, transforms, camera, depth testing, face culling, normals and bounds.
- Asset validation and useful errors for unsupported/corrupt assets.
- Basic material model; avoid claiming PBR until metallic/roughness, normals, color-space handling and lighting are actually implemented and tested.

**Gate:** a real imported or generated mesh renders with correct transforms, depth and material settings.

### Phase 4 — Asset and material pipeline
- Asset browser metadata, validation, references, import progress and errors.
- Prioritize glTF 2.0/GLB for 3D import. Add FBX only if a reliable, license-compatible parser is justified; do not pretend FBX support exists otherwise.
- Material editor with base color, metallic, roughness, normal map, occlusion/emissive where supported.
- Cache assets safely and invalidate on file changes.

**Gate:** imported assets survive project save/reopen/export/import.

### Phase 5 — Animation
- Keep sprite-sheet animation functional.
- Design keyframe/curve timeline data and playback APIs independently from UI.
- Add skeletal animation only after model/skeleton import is real: joints, skin weights, inverse bind matrices, interpolation, animation clips and blending.
- Later add animation state machine, transitions, parameters, events and retargeting if justified.
- No fake rigging controls without actual skeletal data and runtime evaluation.

**Gate:** saved clips replay deterministically and work in player mode.

### Phase 6 — Editor workflow and scripting
- Improve hierarchy/inspector/property validation and undo/redo commands.
- Material and animation editors operate on real serialized data.
- Visual scripting is a later project; first document and stabilize Rhino scripting APIs, errors, logs and lifecycle.
- Add keyboard shortcuts/accessibility only where the Android device/input supports them.

### Phase 7 — Reliability, export and release
- Regression suite, low-memory tests, project migration tests and CI.
- Verify project ZIP security (path traversal, file size limits, corrupt archives).
- Verify APK debug/release build and document signing limitations.
- Document supported devices, known limitations, and reproducible build steps.

## 7. Data and serialization rules

- Add explicit `schemaVersion` to project/scene data if absent, with backward-compatible defaults.
- Unknown optional fields should be handled safely; malformed required data should produce a clear error.
- Never serialize GL IDs, runtime pointers, Android View instances, or transient editor selection unless intentionally designed.
- Use stable IDs/references for assets and scene objects where practical.
- Migrations must be deterministic, tested with old fixtures, and must not overwrite source files until the migrated project can be saved safely.
- Keep project export/import round-trippable.

## 8. Testing and quality gates

Run the commands actually supported by the repository; likely baseline command:

```bash
./gradlew test
./gradlew assembleDebug
```

Run instrumentation/device tests when configured and available. Do not claim tests passed unless their command completed successfully. If the environment lacks Android SDK, emulator or device access, state exactly what could not be tested.

Minimum checks:
- Compile/build after each phase.
- Unit tests for matrix math, transforms, serialization, migrations, viewport mapping and renderer selection logic.
- Instrumented tests for EGL/context creation and lifecycle where possible.
- Visual smoke tests on ES 3 device/emulator and fallback path if retained.
- Regression checks for project save/load, play/stop, sprites, particles, scripts and input.
- No unhandled GL compile/link errors, crashes, unbounded allocations or silent asset failures.

## 9. Implementation workflow for AI agents

For every task:

1. Read this file and inspect the current branch and working tree.
2. Identify exact source files and call sites before modifying them.
3. State a concise plan with acceptance criteria.
4. Make the smallest coherent implementation; do not merely add TODOs or stubs.
5. Build and test as much as the environment permits.
6. Review the diff for unrelated changes, compatibility breaks, resource leaks and fake implementations.
7. Update this file only when architecture, phase status or verified constraints materially change.
8. Summarize files changed, behavior delivered, tests run and failures/limitations.
9. Keep the current branch; do not force-push, rewrite history, or merge into another branch unless explicitly requested.

## 10. Definition of done

A task is complete only when:
- The requested behavior exists in working code, not only in UI or documentation.
- The current app builds, or any build failure is clearly isolated and explained.
- Existing behavior is preserved or migration is documented and tested.
- Errors are actionable and resources are lifecycle-safe.
- Acceptance criteria are verified, and unsupported claims are not made.
- The final response lists concrete changes, test commands/results, and known limitations.

---

# Copy-ready master prompt for an AI coding agent

You are the senior Android graphics/engine engineer responsible for the existing S Engine repository. Read `edition.md` completely before editing. Work on branch `arena/69b25c4c-assets2` in the existing repository only.

Your mission is to evolve S Engine into a real, maintainable Android 2D/3D game engine/editor, starting with a safe migration of the current renderer from OpenGL ES 2.0 to **OpenGL ES 3.0**. Do not create a new demo app, do not replace the existing editor with mock UI, and do not rewrite working systems wholesale.

First audit the actual source tree, Gradle files, manifest, current GL surface/context setup, renderer and shaders, scene/component model, asset formats, serialization, tests and CI. Build the baseline and report any pre-existing failures. Treat all claims in documentation as hypotheses until verified against code.

Implement Phase 0 and Phase 1 from this file in small, compile-ready steps. Introduce runtime graphics capability detection and a clear ES 3.0 context-selection path. Keep an ES 2.0 fallback only if it can remain genuinely functional; never mix ES 3-only APIs into the ES 2 path. Handle context loss and surface lifecycle correctly. Port the real existing scene renderer and shader behavior—not just a triangle—to ES 3.0, preserving sprites, ordering, texture alpha, pivots, text, particles, grid and gizmos as the relevant phase progresses. Do not claim full feature parity until tested.

Respect minSdk 26 and low-memory devices such as the Samsung Galaxy J4 Core. Android version alone does not prove GPU support. Log the actual GL version, renderer/vendor and selected backend; fail gracefully with diagnostics if context creation fails. Keep project/scene JSON backward-compatible, preserve the Rhino scripting API, physics, audio, input, templates, project ZIP workflows, and play/pause/stop behavior.

Follow the architecture, dependency direction, testing requirements and phase gates in `edition.md`. Avoid introducing a third-party game engine, unnecessary dependencies, JNI/C++, large generated assets, secrets or unrelated refactors. Do not add empty classes, placeholder buttons, mock features or TODO-only implementations as substitutes for working code.

After each coherent change, run the relevant Gradle tests and `./gradlew assembleDebug` when available. Inspect the final diff. Never claim a build/test passed unless it actually ran successfully. If device/emulator testing is unavailable, state that limitation clearly and provide exact manual verification steps.

Deliver:
1. A brief repository audit and source-file map.
2. A staged implementation plan and acceptance criteria.
3. Real code changes for the phase being executed.
4. Test/build commands and truthful results.
5. A list of changed files, remaining risks and the next phase.

Do not attempt to implement the entire long-term roadmap in one enormous patch. Complete the current phase properly, preserve the branch and history, and stop at a buildable checkpoint.


---

## Studio 3.0 implementation checkpoint (2026-10-31)

This checkpoint supersedes the earlier branch-specific prompt below for the current Studio work. The active implementation remains the existing Android/Kotlin engine; no mock desktop/web replacement was introduced.

Implemented and integrated: schema-3 project metadata with legacy fixture coverage; truly empty 2D/3D projects and a replaced starter catalog; reusable metallic/roughness materials and a live editor; glTF/GLB conversion with bounded input and rollback; portable multi-part models; skeleton/clip sampling, crossfades/events and CPU/ES3 GPU skinning; a joint/keyframe editor; expanded script lifecycle, modules, events/timers, diagnostics and runtime guards. Original generated starter art/rigs are included. The old template builders are test fixtures only.

Verification: the core/legacy regression suite and Studio systems tests run locally; CI builds debug/release APKs and now requires offline Khronos GLSL compilation/linking. Device/GPU visual and performance testing remains a separate gate. Atomic document replacement no longer deletes the previous file before rename.

Known boundaries: direct-light PBR without IBL/shadow maps/refraction; no material node graph; no IK/retargeting/weight painting/state-machine graph; one shared skeleton per glTF model; no FBX/Draco/KTX2/morph/cubic animation. These are documented rather than represented by nonfunctional controls. See README.md and docs/scripting.md for workflows and exact limits.

The historical roadmap below is retained as architecture guidance, not a claim that every long-term phase is complete.
