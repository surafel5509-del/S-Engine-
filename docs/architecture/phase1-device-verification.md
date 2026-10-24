# Phase 1 device verification runbook

Purpose: check the Phase 1 gate from `edition.md` §6 on real hardware: *actual existing scenes render on ES 3; the app survives resize, pause/resume, and context recreation.*

This runbook is written so that a tester with any device can run it and report results in a form that can be checked. It does not claim any result: each run must be recorded with the values it actually shows.

## 0. What to install

1. Open the latest green CI run for branch `arena/b97ba415-assets2` and download the `SEngine-Android-APKs` artifact. Install the debug APK. (Release builds are signed with the debug key and behave the same.)
2. Use a device with a real GPU or a GLES 3 emulator, not a software-only emulator. Record the device model and Android version.
3. Open the app and create or open a project that contains a scene with sprites, text, and particles. The bundled templates are fine. For a 3D check, use a project with a `MeshRenderer` and a `Camera3D`.

Log access (requires USB debugging and `adb`):

```
adb logcat -s SEngine.GL SEngine:V
```

The same lines also appear in the editor console (the engine log).

## 0a. Newer Android phone (the planned device)

- Current phones normally offer OpenGL ES 3.x, so T1-T8 are the cases that matter. T9 is **NOT RUN** on such a device, because the app has no switch to force the ES 2 path.
- The `GL version` value will read something like `OpenGL ES 3.2 ...`. The app still labels the backend `OpenGL ES 3.0` and `GLES 3.0`. That is intended: the backend is the ES 3 shader and context path, and any ES 3.x driver is accepted. Record the driver's full version string, so the difference is visible in the report.
- Before starting, enable Developer options and USB debugging, and check that `adb devices` lists the phone.
- Install the CI debug APK over any earlier build. Clear app data first if you want a clean project list.

## 1. Expected log lines (checked in every run)

The app writes these lines to the `SEngine.GL` tag and to the engine console. Their exact wording comes from `SceneRenderer.kt`, `GlSurface.kt`, and the two activities.

| When | Expected line (prefix) | Meaning |
|---|---|---|
| EGL setup | `EGL config: RGB8 + depth16, …, ES3-capable config: true` | A config that can host ES 3 contexts was found. `false` means only ES 2 is available. |
| Context creation | `EGL context created: OpenGL ES 3.x requested` | ES 3 context created. `OpenGL ES 2.x requested` appears when the chosen config has no ES 3 support, or when the ES 3 attempt failed. |
| Context start | `GL context #1: GL backend: OpenGL ES 3.0 \| vendor: … \| renderer: … \| GL version: … \| GLSL ES: 300 \| max texture: …px` | The driver's own strings. Record `renderer` and `GL version` in your report. |
| Renderer ready | `Renderer ready (context #1): OpenGL ES 3.0, shader dialect GLSL_300_ES` | Built-in shaders compiled for the chosen dialect. |
| Fallback (only if it happens) | `… shaders could not be built: …` followed by `… fell back to OpenGL ES 2.0 shaders` | The ES 3 shader set failed on this driver, and the ES 2 set was used on the same context. Treat this as a bug to report, with the full line. |
| Size | `Surface 1080x2280 (context #1)` (actual size will differ) | Logged once per size per context. |
| Pause | `Activity paused: GL surface paused` | Logged by `onPause`. |
| Resume | `Activity resumed: GL surface resumed` | Logged by `onResume`. |
| New context | `GL context #2: …` and `Renderer ready (context #2): …` | The driver dropped the context and the renderer rebuilt it. Expected after some resumes. Not a failure by itself. |
| Failure | `Graphics init failed (context #N): …` | Renderer could not start. The screen shows dark red and the status line shows `GRAPHICS ERROR`. Record the full line. |

The status line in the editor and the FPS line in the Player show the active backend: `GLES 3.0`, `GLES 2.0 (fallback)`, or `GRAPHICS ERROR`.

## 2. Test cases

Run each case, then record **PASS**, **FAIL**, or **NOT RUN** with the observed lines.

### T1. First start renders the scene (ES 3 device)
1. Launch the app, open the project, and check that the viewport is not dark red.
2. Confirm the status line says `GLES 3.0`.
3. Confirm the scene shows the expected sprites, text, and particles, and the grid and gizmos in edit mode.
4. Press play, then stop. Confirm the scene returns to the edit view unchanged.

### T2. Rotation (resize) — 3 times
1. With the editor open, rotate the device to landscape and back to portrait, three times.
2. Expected: a new `Surface WxH` line after each rotation. Picture fills the viewport with no stretching beyond the aspect ratio, and no black or dark-red frames.
3. Confirm that the editor does not crash and that touch input still moves objects.

### T3. Home button and return (pause/resume)
1. Press Home for about 10 seconds, then return to the app.
2. Expected: `Activity paused` then `Activity resumed`. The scene is visible and animating (particles move).
3. Note whether a `GL context #2` line appears. Either outcome is acceptable, but the picture must match T1 afterwards.

### T4. Screen off / on
1. Turn the screen off for 30 seconds, then on.
2. Same expected results as T3.

### T5. Forced activity destruction
1. Enable Developer options → "Don't keep activities". Open the editor, press Home, and return.
2. Expected: the app restarts the editor without a crash. Log shows a fresh `GL context #1`.
3. Disable the option afterwards.

### T6. Play mode through pause and resume
1. Press play, then press the pause button, then press Home and return.
2. Expected: the game stays paused after return. Press step once: the scene advances one frame. Resume play: time continues without a large jump (the frame delta is clamped to 0.1 s).

### T7. Shader error message points at the effect line
1. Create a custom shader with a syntax error on line 3 of the effect body (for example a misspelled variable), and assign it to a sprite.
2. Expected: a line `Shader '<name>.glsl' (GLSL_300_ES) failed: fragment: … [line 3 of the effect code]`. The rest of the scene still renders.

### T8. Memory and frame time on a low-memory device (Galaxy J4 Core target)
1. Start a scene that is representative of the project, play for 10 minutes.
2. Record `adb shell dumpsys meminfo com.sengine.app` before starting and at the end. Record the `TOTAL PSS` value.
3. Record frame statistics with `adb shell dumpsys gfxinfo com.sengine.app`. Note the total frames and the 90th-percentile frame time.
4. Expected: no out-of-memory kill and no sustained drop to single-digit FPS. Numbers are recorded, not judged against a fixed target yet, because Phase 2 owns the performance gate.

### T9. ES 2 device (if available)
1. On a device that reports `ES3-capable config: false` or whose context is ES 2, confirm the status line says `GLES 2.0 (fallback)` and the scene renders.
2. Note: the app has no switch to force the ES 2 path on an ES 3 device. This case can only be run on a device that does not offer ES 3.

## 3. What to send back

For each run, record:
- Device model, Android version, and build (APK name or CI run number).
- The `GL backend` line (full), and the `Renderer ready` line.
- PASS/FAIL/NOT RUN for T1–T9 with the observed log lines for any FAIL.
- Screenshots of T1 and T2 if available.

## 4. Known limits of this runbook

- It cannot prove the ES 3 shaders compile on every driver. T1 and T7 are the best checks available without a device-specific build.
- Context loss is only observed when the driver actually drops the context. A forced loss is not available from the app.
- No automated on-device tests exist yet (`androidTest` is not present). This runbook is manual.
- The ES 2 fallback path can only be exercised on hardware that lacks ES 3.
