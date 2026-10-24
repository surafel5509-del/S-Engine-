# S Engine

**S Engine** is a 2D game engine **and** full visual editor that runs entirely on an Android phone or tablet – think "a small Unity in your pocket". Create a project, build scenes with a hierarchy / inspector / gizmos, write JavaScript behaviours in the built-in code editor, press **Play** to test immediately, then run your game full-screen.

> Written from scratch in Kotlin. OpenGL ES 3.0 renderer (ES 2.0 fallback), custom physics, Mozilla Rhino JavaScript runtime. No NDK, no external game frameworks.

---

## Features

| Area | What you get |
|---|---|
| **Project manager** | Create from templates, open, play, rename, duplicate, delete, export / import projects as `.zip` |
| **AI Game Builder** | Offline template planner plus reviewable, validated scene edits; optional direct OpenRouter mode with a user-supplied encrypted on-device key, or authenticated Supabase Edge Function → OpenRouter mode. No AI-generated source is executed; template scripts run only during creation smoke checks, not when editing existing projects. See [setup and limits](docs/ai-game-builder.md). |
| **Scene editor** | Viewport with grid, pan (drag) & pinch-zoom, tap-to-select, **Move / Rotate / Scale gizmos** with axis handles, snapping, frame selected |
| **Hierarchy** | Parent/child tree, collapse, visibility toggle, rename / duplicate / delete / reorder / create child / unparent |
| **Inspector** | Edit name, tag, sorting order, parent, transform (drag labels to scrub values), every component property, color picker, asset pickers, add / remove / reorder / reset components |
| **Undo / Redo** | Snapshot-based history for every edit |
| **Play mode** | Play / Pause / Step frame inside the editor; scene is restored when you stop (like Unity) |
| **Rendering** | Textured sprites with flip, UV animation, editable normalized pivots, text, custom shaders, and sorting |
| **Physics 2D** | Fixed-step rigidbodies, oriented boxes & circles, gravity, friction/restitution, contact-point impulses, angular inertia/torque, optional rotation lock, triggers and grounded detection |
| **Physics 3D** | Box/sphere rigidbodies, gravity, restitution/friction, raycasts, contact-point angular impulses and editable inertia/rotation lock |
| **Particles** | Continuous or burst emitters with color/size curves, speed & size variance, gravity, drag, rotation/spin, textures and additive blending |
| **Scripting** | JavaScript (ES6 subset via Rhino) with `start`, `update(dt)`, `onCollision`, `onTrigger`, `onTap`… plus timers, spawning, messaging, torque and impulses |
| **Code editor** | Syntax highlighting, auto-indent, undo/redo, quick-symbol keyboard row, built-in API reference |
| **Assets & animation** | Import images/sounds, edit sprite-sheet clips, set frame rate/order/loop and per-clip pivot in the Sprite & Animation Editor |
| **Audio** | `AudioSource` component + `audio.play()` / `audio.beep()` |
| **Input & controllers** | Classic joystick/action buttons, drive pedals, optional calibrated phone-tilt steering/balance, touch, hardware keyboard & gamepad |
| **Scenes** | Multiple scenes per project, start scene, `scene.load("Level2")` |
| **Player** | Full-screen runtime ("Build & Run") with landscape / portrait setting |

### Built-in components
`SpriteRenderer`, `TextRenderer`, `Camera` (size, background, follow target with smoothing), `Rigidbody2D`, `Collider2D`, `Script`, `ParticleEmitter`, `AudioSource`.

### Templates
* **Empty 2D** – camera + square
* **Platformer Demo** *(2D)* – run, jump, moving platform, collectible coins and score UI
* **Space Shooter** *(2D)* – enemy waves, bullets, explosions, scoring and game-over flow
* **Physics Sandbox** *(2D)* – drop rolling crates and bouncing balls into a ramp-and-wall arena
* **3D Demo** – third-person movement, lights, models, pickups, raycasts and a tumbling angular crate
* **Hill Climb Racer** *(2D)* – spring-suspended 4×4, generated road, distinct Country/Desert/Frost/Moon stages, hazards, fuel, coins, garage upgrades, three rotating challenges and accelerometer balance controls. It is available from **New Project** and ships as the sample driving game.

---

## Scripting example

```js
// Player.js  – params on the Script component: "speed=6, jump=11"
var coins = 0;

function update(dt) {
    self.vx = input.axisX * speed;              // joystick / A-D keys
    if (input.aDown && self.grounded) {         // A button / Space
        self.vy = jump;
        audio.beep();
    }
    if (self.y < -12) scene.reload();
}

function onTrigger(other) {
    if (other.tag == "Coin") {
        coins++;
        var fx = scene.spawn("CoinFX", other.worldX, other.worldY);
        fx.burst(24);
        after(1.5, function () { fx.destroy(); });
        other.destroy();
        scene.find("ScoreText").text = "Coins: " + coins;
    }
}
```

### API summary

* **Lifecycle:** `start()`, `update(dt)`, `onCollision(other)`, `onTrigger(other)`, `onTriggerExit(other)`, `onTap()`, `onDestroy()`, `onStop()`
* **self / transform:** `name tag active order x y rotation scaleX scaleY worldX worldY vx vy grounded angularVelocity`, `setPosition() move() rotate() addForce() applyImpulse() addTorque() setVelocity() destroy() child() parent distanceTo() overlaps() send() burst() setEmitting() setTexture() hasComponent() setComponentEnabled()`; 3D bodies also expose `angularVelocityX/Y/Z` and `addTorque(x,y,z)`
* **input:** `axisX axisY a b aDown bDown upgrade upgradeDown tiltX touching tapped touchX touchY`
* **scene:** `find(name) findAll(tag) count(tag) spawn(name, x, y) load(name) reload() camera gravityX gravityY`
* **save data:** `storage.get(key, fallback)` / `storage.set(key, value)` for project-scoped numbers, booleans and strings
* **time:** `time.time time.frame time.fps` — **audio:** `play(file) beep() stopAll()`
* **helpers:** `log() warn() error() after(sec, fn) every(sec, fn) random() randomInt() clamp() lerp()`

Inactive objects make great **prefab templates** – `scene.spawn("Enemy", x, y)` clones them and activates the copy.

---

## Download

Every push and pull request runs the Android tests and builds both installable APK variants. Download them from **Actions → Build S Engine APK → latest successful run → Artifacts → `SEngine-Android-APKs`**. The artifact contains `app-debug.apk` and `app-release.apk` (the release variant is currently debug-key signed for convenient testing; replace the signing config before publishing).

Enable "Install unknown apps" for your browser / file manager, then open the APK.

## Building

Requirements: JDK 17 and the Android SDK (API 34). Android Studio Hedgehog or newer works out of the box.

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Every push is also built by GitHub Actions (`.github/workflows/android.yml`); download the APK from the run's **Artifacts** section.

Minimum Android version: 8.0 (API 26). Requires OpenGL ES 2.0 at minimum. The renderer uses OpenGL ES 3.0 when the device driver supports it and falls back to ES 2.0 otherwise; the Editor and Player show the active backend (for example `GLES 3.0` or `GLES 2.0 (fallback)`). The manifest keeps ES 2.0 as the hard requirement, so ES 3.0 support is checked at runtime, not by install filtering.

## Project layout

```
app/src/main/java/com/sengine/
├── aibuilder/              versioned GameSpec, bounded tools, validation, provider and memory services
├── engine/
│   ├── Engine.kt            main loop, play/pause/stop, camera follow, particles
│   ├── Input.kt, AudioSystem.kt
│   ├── core/                GameObject, Component, Prop system, components, Scene + JSON serializer
│   ├── math/Affine.kt       2D transforms
│   ├── physics/             fixed-step 2D & 3D physics with angular contact impulses
│   ├── anim/                sprite-sheet clips, normalized pivots and runtime animation
│   ├── render/              OpenGL ES renderer (ES 3.0 primary, ES 2.0 fallback), textures/text/particles, editor grid & gizmos
│   └── script/              Rhino JavaScript runtime + script API
├── project/                 project storage, zip import/export, templates
└── ui/                      Projects screen, Editor (hierarchy, inspector, viewport, assets, console),
                             Script editor, full-screen Player, joystick, color picker
```

Projects are stored in app-private storage as plain JSON scenes plus an `assets/` folder:

```
<project>/project.json
<project>/scenes/Main.scene.json
<project>/assets/Player.js, hero.png, jump.wav …
```

## License

MIT
