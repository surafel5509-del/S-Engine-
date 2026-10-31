# S Engine Studio 3.0

An on-device 2D/3D game engine and editor for Android, written in Kotlin. Create a genuinely empty project or start from a playable game, edit scenes and assets, write JavaScript, author metallic/roughness materials, import glTF models, animate a skeleton, and press Play.

**This is an actual Android engine—not a desktop mockup.** It retains the existing scene editor, physics, sprite tools, visual blueprints, audio, input, project ZIP workflow and standalone game export. The Studio release adds the production asset/runtime systems described below.

## Install

Download **SEngine-Android-APKs** from the latest successful [Android build](https://github.com/surafel5509-del/S-Engine-/actions/workflows/android.yml). The artifact contains:

- `app-debug.apk` — development build.
- `app-release.apk` — release variant, currently signed with the debug key for convenient testing.

Android 8.0/API 26 or newer is required. Allow installation from the browser/file manager used to open the APK. Replace the release signing configuration before publishing to an app store. Installing a Studio APK over an older S Engine build requires a compatible signing key and a sufficiently high version code; export important projects first.

## New-project catalog

The old template catalog has been retired. Existing saved projects are not deleted or rewritten merely because their template is no longer offered.

| Project | What it contains |
|---|---|
| **Empty 2D** | An empty Main scene and 2D project metadata; no objects or starter assets. Add your own camera and content. |
| **Empty 3D** | An empty Main scene and 3D project metadata; no objects or starter assets. The editor/player provide a default view until a camera is added. |
| **Neon Relay** | A 2D precision platformer with coyote time, buffered/variable jumps, checkpoints, hazards, five energy cells and a saved best time. |
| **Orbit Salvage** | A 2D survival shooter with dual-axis flight, projectiles, debris waves, hull damage, scoring and retry flow. |
| **Sentinel Protocol** | A third-person 3D recovery mission using a weighted robot, idle/run skeletal clips, PBR materials, physics and extraction triggers. |
| **Animation & Material Lab** | An editable robot rig and material spheres for testing animation, surface maps, roughness, metalness and emission. |

The bundled robot and brick maps are original, deterministic generated content. The supplied Unreal/rig screenshots were design references; no Unreal assets are included.

## Materials and models

### Reusable PBR materials

Use **Assets → + Material**, or long-press a `.mat` asset and choose **Edit material**. The live sphere preview supports:

- Base color and base-color texture.
- Metallic/roughness factors and a packed metallic-roughness texture (**G = roughness, B = metallic**).
- Tangent-space normal mapping and normal strength.
- Occlusion map (**R**) and strength.
- Emissive color, map and strength.
- UV tiling/offset.
- Opaque, alpha-mask and alpha-blend modes; cutoff and double-sided rendering.
- Unlit rendering.

Assign a material in the MeshRenderer inspector. Runtime script overrides affect one object and do not rewrite the shared material asset. The renderer uses a GGX/specular BRDF, Fresnel, direct directional/point lighting, ambient, normal/occlusion maps and tone mapping.

**Scope:** this is a mobile direct-light PBR pipeline, not an Unreal Material Editor clone. There is no arbitrary material node graph, image-based lighting, shadow-map pipeline, ray tracing, screen-space refraction or frosted-glass transmission. Alpha blending is supported; physically based glass is not claimed.

### Model import

Use **Assets → Import Model** for `.glb`, `.gltf` or legacy `.obj`. glTF is recommended for multi-material models and animation. Imported content is converted into portable project assets:

- `.smodel`: mesh parts, material references, optional skeleton and clip references.
- `.mat`: reusable material data.
- `.skanim`: skeletal clips.
- PNG/JPEG/WebP texture assets.

The glTF 2.0 importer supports indexed/strided/normalized accessors, embedded data URIs, GLB binary chunks, project-relative external resources, node transforms, shared skins, inverse binds, four vertex weights, PBR texture factors and LINEAR/STEP animation. It rejects unsupported required extensions rather than silently producing incorrect content.

Current limits: 32 MB input, 24 MB converted model, 256 parts, 250,000 expanded vertices and 128 joints. One shared skeleton per imported model. Sparse accessors, morph targets, cubic-spline animation, Draco/mesh compression and KTX2/Basis textures are not supported; bake or export compatible data first. FBX is not supported. External glTF resources must already be imported into the same project; GLB is the simplest interchange format.

## Skeletal animation

Add a **SkeletalAnimator** beside MeshRenderer, or open **Assets → Skeleton**. The editor can generate the original courier rig when a project has no skeletal model. Select joints, enter local Euler rotations, insert rotation/position keys, scrub the timeline, add events, preview and save `.skanim` clips.

Runtime features include:

- Parent-child joint hierarchies, inverse-bind matrices and four-weight linear-blend skinning.
- Quaternion rotation interpolation, translation/scale tracks and LINEAR/STEP sampling.
- Looping, playback speed, restart/crossfade and animation events.
- Persistent clips using the same sampler in editor preview and player mode.
- Scripted local bone rotation/position overrides and reset-to-rest behavior.
- GPU skinning on ES 3 for up to 32 joints; CPU skinning on ES 2 or larger rigs.
- Optional skeleton overlay in the 3D editor.

This is a clip/joint editor, not a mesh-authoring suite: it does not paint weights, create IK constraints, retarget arbitrary rigs or provide an animation state-machine graph. Use crossfades from scripts for locomotion transitions.

## JavaScript scripting

The built-in editor provides syntax highlighting, undo/redo, quick keys, an API reference, **Check** diagnostics and line/column navigation. Syntax errors are rejected on save. Parsing does not execute the script.

```js
// Courier.js — attach to an object with MeshRenderer + SkeletalAnimator.
var speed = 4;
var moving = false;

function start() {
    events.on('mission:complete', function () {
        self.crossFade('Courier_Idle.skanim', 0.2);
    });
}

function update(dt) {
    var next = Math.abs(input.axisY) > 0.1;
    if (next !== moving) {
        moving = next;
        self.crossFade(moving ? 'Courier_Run.skanim' : 'Courier_Idle.skanim', 0.16);
    }
    var forward = self.forward();
    self.vx = forward[0] * input.axisY * speed;
    self.vz = forward[2] * input.axisY * speed;
}

function onAnimationEvent(name) {
    if (name === 'Footstep') audio.beep();
}
```

Additional capabilities:

- `fixedUpdate(dt)`, `lateUpdate(dt)`, `onEnable`, `onDisable`, `onDestroy`, `onStop` and animation events.
- Isolated component scopes; inspector parameters become script variables.
- Project-contained CommonJS-style `require('./Module.js')` and `module.exports`.
- Instance-owned `events.on/once/off/emit` and `timers.after/every/cancel`.
- Existing transform, physics, scene, input, audio, particle, animation and storage APIs.
- Per-object material assignment/property overrides and skeletal controls.

Rhino runs in interpreted mode with a host-class allowlist, source-size limits, recursion/callback budgets and spawn limits. A failing or looping script is disabled and reported instead of stopping the entire engine. This is a safety boundary for ordinary project scripts, not a claim of formal verification. See [the scripting guide](docs/scripting.md).

## Rendering and existing tools

- OpenGL ES 3.0 primary path with a functional ES 2.0 fallback; the console reports the actual driver/backend.
- 2D sprites, text, particles, custom effects, lighting, camera rigs and post effects.
- 3D primitives, imported models, PBR materials, lights, fog and editor gizmos.
- Fixed-step 2D/3D physics, colliders, triggers, raycasts and joints.
- Hierarchy/inspector, undo/redo, multi-scene editing and non-destructive play mode.
- Sprite Studio, sprite-sheet animation, visual blueprints, asset packs and texture import settings.
- On-device AI Game Studio with user-configured providers and a bounded, validated operation system; see [AI Game Studio](docs/ai-game-builder.md).

## Projects and compatibility

Projects contain plain JSON scenes and an `assets/` directory. Studio writes schema version 3 metadata and preserves older scene/component JSON and unknown fields. Sprite `.anim` files remain separate from skeletal `.skanim` files. Project/scene/asset document replacements use a flushed temporary file and atomic rename.

```text
My Game/
  project.json
  textures.json
  scenes/Main.scene.json
  assets/Player.js
  assets/Robot.smodel
  assets/Robot_Idle.skanim
  assets/Alloy.mat
  assets/Alloy_Normal.png
```

Use **Export Project (.zip)** for backups. The launcher starts empty on a fresh installation; it does not seed the retired templates. Legacy template builders remain only under test fixtures for compatibility regression tests.

## Build and verification

Requirements: JDK 17, Android SDK API 34 and build tools 34.0.0.

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug assembleRelease
```

CI installs the Khronos GLSL validator and requires offline shader compilation/linking as part of the test gate. The suite covers legacy project fixtures, physics/script simulations, the new starter catalog, material persistence, skeleton math/skinning, clip blending, glTF import/rollback, atomic replacement, script isolation and shader sources.

Offline GLSL validation is also available locally when `glslangValidator` is installed:

```bash
SENGINE_REQUIRE_GLSLANG=1 GLSLANG_VALIDATOR=/usr/bin/glslangValidator ./gradlew testDebugUnitTest
```

Automated shader validation is not a substitute for device testing. GPU driver behavior, touch ergonomics, sustained performance, thermal behavior and visual quality still need testing on target phones/tablets. No claim is made that every ES 2/ES 3 device has been tested.

## Source map

```text
app/src/main/java/com/sengine/
  engine/core/       components, scenes, serialization and component registry
  engine/anim/       sprite animation, skeletons, clips and skeletal sampling
  engine/render/     models, meshes, CPU skinning, textures and scene rendering
    api/             backend interfaces and shared PBR shader functions
    gles2/, gles3/   dialect-specific shader sources
  engine/script/     Rhino runtime, diagnostics, modules, events and timers
  project/           projects, materials, templates, glTF import and atomic files
  ui/                launcher, editor, material/skeletal/script tools and player
  aibuilder/         validated AI plans and bounded project operations
  export/            standalone game export
```

## License

MIT.