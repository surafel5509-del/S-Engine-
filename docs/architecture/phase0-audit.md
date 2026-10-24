# S Engine — Phase 0 audit and baseline

Scope: `edition.md` §6 Phase 0 (audit and baseline), with the Phase 1 foundation work that follows it on this branch.
Every statement below was checked against the source tree, Gradle files, manifest, or CI logs at the time of writing.

## 1. Branch and baseline

| Item | Status |
|---|---|
| Working branch | `arena/b97ba415-assets2` (branched from `45a6d0b7346b6f98b9d4c0e73caf79fe9a76a069`). `edition.md` and the original brief name `arena/69b25c4c-assets2`; that branch is not present in this sandbox, so this branch is used instead. |
| Baseline CI | GitHub Actions run `38864079363` on `arena/69b25c4c-assets2` at `45a6d0b`: **success** (job "Test and build Android APKs", all steps green, artifact `SEngine-Android-APKs`). |
| Local Gradle/Android build | **Not run.** The sandbox has no Android SDK, and Maven/Google/Gradle/Adoptium hosts are unreachable from it. The build is verified by CI only. |
| Pre-existing failures | None found in the baseline CI run. |

## 2. Build configuration

- Gradle wrapper 8.7, Android Gradle Plugin 8.3.2, Kotlin 1.9.23, JVM target 17 (`build.gradle.kts`, `gradle/wrapper`).
- `app/build.gradle.kts`: `compileSdk 34`, `minSdk 26`, `targetSdk 34`, `applicationId com.sengine.app`.
- Dependencies: Mozilla Rhino 1.7.13 (1.7.14 is known to break on Android, so it stays pinned); unit tests use JUnit 4.13.2 and org.json 20231013.
- `unitTests.isReturnDefaultValues = true`. Release build is signed with the debug key.
- `settings.gradle.kts` uses `repositoriesMode FAIL_ON_PROJECT_REPOS`.

## 3. Manifest

- `<uses-feature android:glEsVersion="0x00020000" android:required="true" />`. The app therefore requires OpenGL ES 2.0 at install time. ES 3.0 is **not** a manifest requirement (see `edition.md` §4 and the product constraints); it is detected at runtime.
- Activities: `ProjectsActivity` (launcher), `EditorActivity`, `ScriptEditorActivity`, `PlayerActivity`, `BuildActivity`, `AssetStoreActivity`, `AnimationEditorActivity`, `BlueprintEditorActivity`. One `FileProvider`.
- Exported games (`export/ApkBuilder.kt`) repackage this app's own APK and patch its manifest, so manifest GL changes propagate to exported games.

## 4. Source map (main, Kotlin, lines from `wc -l`)

| Area | Files (lines) | Notes |
|---|---|---|
| Engine core | `engine/Engine.kt` (340), `engine/Input.kt`, `engine/AudioSystem.kt` | `Engine.tick(dt)` runs on the GL thread under `engine.lock`; commands go through `post()`. Log ring buffer of 300; profiler `renderMs`, `drawCalls`. |
| Scene and components | `engine/core/Scene.kt` (188), `core/Components.kt` (466), `core/GameObject.kt`, `core/Scene` JSON serializer | Scene JSON has no `schemaVersion` (see §7). |
| Rendering | `engine/render/SceneRenderer.kt` (583), `Renderer2D.kt` (180), `Renderer3D.kt` (167), `PostProcessor.kt`, `Textures.kt`, `Shaders.kt`, `GL.kt`, `Meshes.kt` (246), `View2D.kt`, `View3D.kt`, `EditorState.kt` | Renderer is GLES 2 (`android.opengl.GLES20`) before this branch. |
| Shaders | Inline GLSL 1.00 strings in `Renderer2D.kt`, `Renderer3D.kt`, `Shaders.kt`; user effects are `.glsl` assets | 10 built-in programs (sprite, mesh, post, line, sky) plus 8 post effects. |
| Physics | `engine/physics/PhysicsWorld.kt` (374), `PhysicsWorld3D.kt` (352) | Fixed-step 2D and 3D. No GL. |
| Scripting | `engine/script/Api.kt` (309), `ScriptSystem.kt` (255) | Rhino JavaScript API. No GL. |
| Animation | `engine/anim/AnimationClip.kt` (115) | Sprite-sheet clips. No GL. |
| Blueprint | `engine/blueprint/Blueprint.kt` (297) | No GL. |
| Project and assets | `project/Project.kt` (121), `ProjectManager.kt`, `AssetLibrary.kt` (1083), `Templates.kt` (477), `HillClimbTemplate.kt` (780) | Shader templates in `AssetLibrary.kt` (about line 791) and `NEW_SHADER` in `Templates.kt` (line 24). |
| Export | `export/ApkBuilder.kt` (96), `ApkSignerV2.kt`, `AxmlPatcher.kt`, `export/GameRuntime.kt` | Standalone games detect `assets/game/build.json` and extract to `filesDir/game`. |
| UI | `ui/EditorActivity.kt` (909), `PlayerActivity.kt` (131), `ScriptEditorActivity.kt`, `InspectorPanel.kt`, `ViewportController.kt`, `Viewport3DController.kt`, others | Only `EditorActivity` and `PlayerActivity` create a `GLSurfaceView`. |

Total Kotlin under `app/src`: about 14,200 lines including tests.

## 5. GL surface inventory (before this branch)

Count of `GLES20.` / `GLSurfaceView` / `GL10` references per file at HEAD:

| File | Refs | Role |
|---|---|---|
| `render/Renderer3D.kt` | 55 | Mesh, sky, lights, depth state |
| `render/Shaders.kt` | 41 | Program build and uniform/attrib lookup |
| `render/PostProcessor.kt` | 37 | FBO, RGBA texture, DEPTH_COMPONENT16 renderbuffer |
| `render/Renderer2D.kt` | 34 | Quads, lines, circles, wire boxes, OBB |
| `render/GL.kt` | 26 | Compile/link helpers |
| `render/SceneRenderer.kt` | 15 | Frame loop, clears, blend changes |
| `render/Textures.kt` | 10 | Texture upload, `GLUtils` for text bitmaps |
| `ui/EditorActivity.kt`, `ui/PlayerActivity.kt` | 0 direct calls | `setEGLContextClientVersion(2)` in both (the only ES 2 pin in the app) |

Known GL-related risks found in the audit (not yet addressed unless noted in §8):
- `GL.compile` returned a program id even when linking failed, so shader errors were silent.
- `ShaderLibrary.source()` reads the `.glsl` asset from disk on every sprite/mesh/post lookup, even when the file has not changed. Cached programs are reused by timestamp, but the file is still read each time. (Pre-existing; not changed in this phase.)
- Image texture cache is unbounded; text bitmaps are 72 px and the text cache is capped at 128 entries. *(Image and text cache bounding was addressed in Phase 2 slice 2; see section 11.)*
- Ad-hoc GL state toggles across passes; per-draw uniform updates; immediate-mode vertex pointers from heap buffers.
- `PostProcessor` has no context-loss handling.

## 6. Tests

- `app/src/test/java/com/sengine/`: `ApkExportTest.kt`, `EngineSimulationTest.kt` (205 lines), `EngineV2Test.kt` (234), `HillClimbTemplateTest.kt` (123). 21 `@Test` methods in total.
- No renderer or GL test existed before this branch. No `androidTest` source set exists, so there are no on-device tests.
- CI runs `./gradlew testDebugUnitTest`, then `assembleDebug assembleRelease`, and uploads the APKs.

## 7. Scene and project schema

- `project.json`: `name`, `engine` ("S Engine 1.0"), `startScene`, `created`, `orientation`, `controlScheme`. **No `schemaVersion`.**
- Scene JSON: `name`, `gravity*`, `ambient`, `fog*`, `nextId`, `objects`. **No `schemaVersion`.**
- `edition.md` §7 requires a schema version and a migration when the schema changes. Adding the field is deferred until a schema change actually needs a migration, so that no unversioned data is rewritten without a reason.
- **Fixtures (added):** `app/src/test/resources/fixtures/project/` contains a complete project folder in the current format:
  - `project.json`.
  - `scenes/Main.scene.json`: a 2D scene with a camera (custom post shader), a sprite with a hierarchy child, a static ground with a custom shader, particles, text, audio, script, animator, and a disabled object.
  - `scenes/Arena3D.scene.json`: a 3D scene with fog, a directional light, sphere and plane meshes, and 3D physics.
  - `scenes/Legacy.scene.json`: a minimal old-style scene that omits `gravity3D`, `ambient`, `fog*`, and `nextId`.
  - `SceneFixtureTest` (10 tests) checks the loader's values, that a save reproduces each fixture's structure, that a second load/save cycle is stable, and that missing legacy fields get defaults and are written in the current format on save.
  - Numbers are compared with a tolerance because the saver widens floats to doubles.

### Findings from the fixture work

1. **Unknown data was silently dropped (fixed).** `SceneSerializer` used to skip any component whose type this build did not know, and the next save removed it. Unknown components, object-level keys, and scene-level keys are now kept verbatim on the model (`GameObject.unknownComponents`, `GameObject.unknownFields`, `Scene.unknownFields`) and written back on save. Duplicating an object copies them too. Known fields keep priority, and scenes with no unknown data save exactly as before (the fixture tests check this). Limits: unknown components are not shown in the inspector, and they are written after the known components of the same object, so their position in the list is not kept. Covered by `UnknownDataPreservationTest`.
2. **Float noise in saved files.** The saver writes `Float` values as `Double` (`0.4f` becomes `0.4000000059604645`). Loading is unaffected, but saved files and diffs are noisy. Formatting through `Float.toString()` would fix it. This is cosmetic and is not changed here.
3. **No `schemaVersion`** in `project.json` or scene JSON (see above). The legacy fixture shows that missing fields currently act as the schema-version mechanism.

## 8. Phase 1 status on this branch

Implemented (Gradle compiles and unit tests run in CI, not in the sandbox; **no device run yet**):

- `render/api/GraphicsApi.kt`: `GraphicsBackend` (GLES30, GLES20), `ShaderDialect`, `GlCapabilities` (version/GLSL parsing, `describe`), `GraphicsBackendSelector`, `GraphicsInitException`.
- `render/api/ShaderSourceSet.kt`: one interface per dialect; `ShaderDiagnostics` maps driver `0:<line>` errors back to the user's effect code.
- `render/gles2/Gles2Shaders.kt`: GLSL ES 1.00 sources, moved verbatim from HEAD (checked byte-for-byte).
- `render/gles3/Gles3Shaders.kt`: GLSL ES 3.00 sources (`#version 300 es`, `in`/`out`, explicit `fragColor`). A `texture2D` macro shim keeps the user-effect ABI unchanged.
- `platform/android/GlSurface.kt`: ES 3 first EGL config chooser and context factory, ES 2 fallback, `GlInfo.query()`, `GraphicsStatus`.
- `render/GL.kt`: `tryCompile` returns stage-tagged errors (`vertex:`, `fragment:`, `link:`) and never returns a program id for a failed link; `compileOrThrow`; `drainErrors`.
- `render/Shaders.kt`: `ShaderLibrary.init(set)`; user shaders report errors that point at the effect line.
- `render/SceneRenderer.kt`: selects the backend from the driver's strings, builds the matching shader set, and reports the result via `GraphicsStatus`, `Log` (tag `SEngine.GL`) and the engine log. If a built-in ES 3 set fails to build, it falls back to the ES 2 set on the same ES 3 context. If init fails entirely, frames show a dark-red screen with the error in the log.
- `ui/EditorActivity.kt`, `ui/PlayerActivity.kt`: `setEGLContextClientVersion(2)` replaced by `GlSurface.install`; the stats line shows the active backend.

Not yet done in Phase 1 (honest gaps):

- `render/Textures.kt` and `render/PostProcessor.kt` still call `GLES20` directly. This is valid on an ES 3 context, but ES 3-only features (VAOs, UBOs, instancing) are not used anywhere yet.
- Context loss is handled by re-running init on `onSurfaceCreated`: textures, post targets, shaders and renderer buffers are rebuilt, and stale handles from the old context are forgotten, not deleted. Lifecycle is now observable: each context is numbered (`GL context #N`), each surface size change is logged, and pause and resume are logged (see `docs/architecture/phase1-device-verification.md`). There is still no device-side verification of resume or context recreation.
- No user-facing error dialog for init failure; the failure is shown in the log and status line.
- The Phase 1 gate ("actual existing scenes render on ES 3; app survives resize, pause/resume and context recreation") **has not been verified on a device or emulator.**

## 9. Phase 0 checklist

- [x] Source tree, Gradle files, manifest, CI config and GL call sites mapped (§2–§5).
- [x] Baseline CI on the `69b25c4c` branch recorded as green (§1).
- [x] Build verified by CI: `Test and build Android APKs` succeeded on `2aa8649` (unit tests and debug/release APKs). Local Gradle build remains blocked by the sandbox (no Android SDK; Maven/Google hosts unreachable).
- [x] Renderer classes, shaders and GL call counts listed (§5).
- [x] Scene and project schema recorded (§7).
- [x] Representative scene and project fixtures added with `SceneFixtureTest` (§7).
- [x] Unknown-data loss fixed: unknown components and keys are preserved on load/save (§7, finding 1).
- [x] Audit report and implementation checklist (this document).

## 10. Verification log for this branch

- Unit tests `GraphicsApiTest` (9 tests) and `ShaderSourcesTest` (13 tests) were written under `app/src/test/java/com/sengine/render/`.
- Because Gradle and the Android SDK are unavailable in the sandbox, these tests were compiled with Kotlin 2.4.21 (JVM 17) and run through a local JUnit-compatible harness (the same `@Test` and `Assert` semantics, no Gradle). Result: **22 of 22 passed.** Mutation checks (a renamed ES 3 attribute, an ES 3 token leaking into ES 2, and a varying type mismatch) each made the suite fail, so the lint tests detect the errors they target.
- The harness is not part of the repository.
- **CI (authoritative build check):**
  - Run `38865877370` on `04c9185` (first push): **failure** in `:app:compileDebugKotlin`, three unresolved `EGL14.EGL_OPENGL_ES3_BIT` references in `GlSurface.kt`. The constant is not in `EGL14`. Fixed by declaring the EGL_KHR_create_context value `0x40` locally.
  - Run `38866267838` on `2aa8649`: **success.** Steps `Run unit tests` (`testDebugUnitTest`, which includes the new `render` tests) and `Assemble debug and release APKs` both succeeded, and the APK artifact was uploaded. The per-test count was not retrieved, because the CI log and artifact storage were unreachable from the sandbox; the step result is the evidence.
  - Lesson: the sandbox can only type-check pure-Kotlin code. Android-facing code must be validated by CI until a local Android SDK is available.
- **ES 3.00 compile check (sandbox, reference compiler):** all 21 ES 3 programs in `Gles3Shaders.kt` (10 built-in programs, the 3-program post/sprite/mesh variants, and the 8 built-in post effects from `Shaders.kt` substituted into the ES 3 post template) were compiled with glslang 0.0.15 (the Khronos reference front end, SPIR-V path). Result: **21 of 21 passed**. Negative control (an undeclared identifier) was rejected with `undeclared identifier`, so the checker is not accepting everything.
  - Method: each source was checked as `#version 310 es` with `layout(location)` and `layout(binding)` added, because the SPIR-V path requires them. Those are the only changes. The ES 2 sources were not checked.
  - Limits: this is a reference compiler, not a mobile driver. It does not prove the shaders run on Adreno, Mali or PowerVR drivers. The tool lives in `/tmp` and is not part of the repository.
- `ShaderDiagnostics` mapping tests and the ES 3 shader lint tests validate the static properties of the sources. They do **not** prove that the GLSL compiles on a real driver; that requires an on-device run.

## 11. Phase 2 status (2D parity and performance)

Phase 1's device gate is still open, so Phase 2 is started but **not complete**. edition.md asks for one phase at a time; the Phase 2 work below does not change any GL behaviour and does not claim the Phase 2 gate.

Done (slice 1, "pin the 2D rules before batching"):

- `render/Draw2DRules.kt` (no Android or GL imports): draw order (`order`, then scene index; inactive, destroyed and inactive-parent objects excluded), pivot offset, UV and flip mapping (`quadUvInto`, no allocation), text line count, height, width and alignment origin, particle `t`, size and colour lerp.
- `Renderer2D.quadModel` and `SceneRenderer` (sprite, text, particles, draw order) now call these functions. The formulas are the same as before; each one was checked against the original inline code.
- `app/src/test/java/com/sengine/render/Draw2DRulesTest.kt`: 17 tests covering ordering, pivots, UV flips, text alignment, particle interpolation and colour truncation.

Verification:

- Local run (sandbox only): the real `Draw2DRules.kt`, `Draw2DRulesTest.kt` and the `GameObject`/`Component`/`Prop`/`Affine`/`Mat4` sources were compiled with kotlinc 2.4.21 (JVM 17). A minimal `org.json` stub and a JUnit-compatible runner were used, because Maven is unreachable. Result: **17 of 17 passed**.
- Mutation checks: flipping the pivot sign made 2 tests fail; reversing the index tie-break made 2 tests fail.
- `Renderer2D.kt` and `SceneRenderer.kt` cannot be compiled in the sandbox (no Android SDK). They are checked only by CI, and the CI result is the authoritative build check.

Still open for Phase 2 (needs a device or is not yet started):

- Batching: not started. It must reproduce `Draw2DRules` results. Visual equivalence needs an on-device comparison.
- Visual validation of text, particles, grid and gizmos on ES 3. Needs a device.
- Frame time and memory on the low-memory target (Samsung Galaxy J4 Core, 1 GB). Needs a device.
- Per-frame allocation: `sortedObjects()` builds new lists each frame, and `drawScreenUI` calls it again. Not changed in this slice.
- 72 px text bitmaps are fixed size and not DPI-aware. The texture budget value (64 MB) is a guess until measured on the low-memory target.

### Slice 2: bounded GPU texture memory

Done:

- `render/TextureBudget.kt` (no GL or Android imports): a byte budget with LRU order. Textures used in the current frame are pinned and never chosen for eviction. `plan()` is read-only and reports when the budget is still exceeded because everything resident is pinned.
- `render/Textures.kt`: image and text textures are both tracked. When an upload would exceed the budget, the least recently used unpinned textures are deleted with `glDeleteTextures`. An evicted image reloads from disk on next use. The old fixed cap of 128 text entries is replaced by the byte budget. Each image file is stat-checked at most once per frame, not once per draw. Warnings go to the engine log (level 1).
- `SceneRenderer` calls `textures.beginFrame()` once per frame and publishes `engine.textureBytes`. The editor profiler line shows `tex N KB`.
- Default budget: 64 MB, not measured. Tune it on the Galaxy J4 Core.

Why eviction is safe: rendering is immediate-mode, so a deleted texture is never referenced by a later draw in the same frame. Pinning is kept anyway, because batching would need it. Batching must keep this rule.

Verification:

- Local run (sandbox): `TextureBudgetTest` (9 tests) compiled with kotlinc 2.4.21 and run on JVM 17 through the same minimal runner used for slice 1. Result: **9 of 9 passed**.
- Mutation checks: removing the pin check fails 2 tests. Making `plan()` read the LRU list (which reorders it) fails 4 tests.
- `Textures.kt` and `SceneRenderer.kt` cannot be compiled in the sandbox (no Android SDK). CI is the build check. GL upload, eviction and reload have not been run on a device.
- Not verified: actual GPU memory use, eviction hitches, and whether 64 MB is right for the 1 GB target.

