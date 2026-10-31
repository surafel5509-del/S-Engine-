# S Engine Studio scripting

Scripts are project assets attached through the **Script** component. Each component receives its own JavaScript scope. The runtime is Mozilla Rhino 1.7.13 in interpreted mode, with an ES6-subset language configuration; it is not Node.js or a browser.

## Lifecycle

```js
var speed = 5;
function start() {}
function fixedUpdate(dt) {}  // fixed 1/60-second steps, bounded catch-up
function update(dt) {}       // variable render-frame delta, in seconds
function lateUpdate(dt) {}   // after physics/animation
function onEnable() {}
function onDisable() {}
function onCollision(other) {}
function onTrigger(other) {}
function onTriggerExit(other) {}
function onTap() {}
function onAnimationEvent(name) {}
function onDestroy() {}
function onStop() {}
```

Use `dt` for frame-rate-independent movement. Do not block callbacks with long loops or synchronous work. Ordinary callbacks have a 35 ms budget; initialization/start has a 250 ms budget. A script that exceeds a budget or throws is disabled and logged. Fix the source and restart Play to retry.

## Objects and components

`self`, `transform` and `gameObject` refer to the same object API.

- Identity/hierarchy: `id`, `name`, `tag`, `active`, `order`, `parent`, `child(name)`, `destroy()`.
- 2D: `x`, `y`, `rotation`, `scaleX`, `scaleY`, `worldX`, `worldY`, `setPosition`, `move`, `rotate`.
- 3D: `z`, `rotX`, `rotY`, `rotZ`, `scaleZ`, `worldZ`, three-component transform methods, `forward()` and `distanceTo3(other)`.
- Physics: `vx`, `vy`, `vz`, `grounded`, `setVelocity`, `applyImpulse`, `addForce`, angular velocity/torque and collision/trigger callbacks.
- Components: `hasComponent(type)`, `setComponentEnabled(type, enabled)`.
- Rendering: `color`, `visible`, `flipX`, `setTexture`, `setMeshColor`, `setShaderParam`, particles and text.

`addForce` retains the existing engine's impulse-style semantics; it is not a continuous SI-force integrator. Use the physics inspector for mass, gravity, constraints and body type.

## Materials

```js
self.setMaterial('BrushedSteel.mat');
self.setMaterialProperty('metallic', 0.9);
self.setMaterialProperty('roughness', 0.25);
self.setMaterialProperty('emissiveStrength', 2.0);
```

These overrides belong to the object for the current play session. They do not edit the shared `.mat` file. Use the Material Editor to publish reusable changes.

## Skeletal animation

```js
self.crossFade('Courier_Run.skanim', 0.16);
self.setBoneRotation('Head', 0, 25, 0);       // local Euler degrees
self.setBonePosition('Root', 0, 1.0, 0);      // local translation
var world = self.getBonePosition('Head');    // [x,y,z], or null
self.clearBoneOverrides();
```

Bone setters return `false` when the object has no initialized skeleton or the named joint does not exist. Overrides are applied after clip sampling/blending and are transient. They do not mutate the rig's rest pose or saved clip. Sprite animation still uses `play('Run.anim')`, `stopAnimation()` and `isAnimationFinished()`.

## Modules

```js
// Math.js
module.exports = {
    clamp01: function (x) { return Math.max(0, Math.min(1, x)); }
};

// Player.js
var math = require('./Math.js');
function start() { log(math.clamp01(1.2)); }
```

Paths are relative to the importing asset and must remain inside `assets/`. Modules are cached per play session. Module exports are shared; component instance globals are not. Circular dependencies fail with a diagnostic rather than recursing indefinitely. Node packages, native modules and host filesystem access are unavailable.

## Events and timers

```js
var subscription;
var timer;
function start() {
    subscription = events.on('score:changed', function (score) {
        scene.find('HUD').text = 'Score: ' + score;
    });
    timer = every(1, function () { events.emit('tick', time.time); });
}
function onStop() {
    events.off(subscription);
    cancelTimer(timer);
}
```

`events.once` subscribes for one delivery. `timers.after`, `timers.every` and `timers.cancel` are equivalent to the global helpers. Subscriptions/timers are owned by the script instance and cleaned up when it is destroyed or Play ends. Disabled instances do not receive callbacks. Events are synchronous and bounded; they are not a network/message-queue service.

## Scene, input, audio and saves

- `scene.find(name)`, `findAll(tag)`, `count(tag)`, `spawn`, `load`, `reload`, `shake`, 3D `raycast`.
- `input.axisX/axisY`, `a/b`, `aDown/bDown`, touch and tilt fields.
- `audio.play(asset)`, `beep()`, `stopAll()`.
- `storage.get(key, fallback)` / `storage.set(key, value)` for project-scoped strings, numbers and booleans.
- `time.time`, `time.frame`, `time.fps`.
- `log`, `warn`, `error`, `random`, `randomInt`, `clamp`, `lerp`, `moveTowards`, `damp`, `assert`.

Inactive scene objects can serve as prefabs: `scene.spawn` clones one and activates the copy. Spawning is capped at 256 objects per frame.

## Inspector parameters

The Script component's Params field accepts values such as:

```text
speed=6, jump=10, label="Hello, world", enabled=true
```

Parameters become variables in that component's scope. Names must be valid identifiers and cannot replace engine API globals. At most 64 parameters are accepted.

## Diagnostics and safety boundary

**Check** parses without executing code and reports file, line and column. Save rejects syntax errors. Runtime errors identify the script and object in the editor console. The runtime blocks Java package access and uses a narrow host-class allowlist, 512 KB source limit, recursion limit, instruction/time guards and event/timer/spawn limits.

These measures reduce accidental damage and runaway scripts. They are not a formally verified sandbox for hostile code. Only run projects and modules you trust.

## Visual blueprints

Existing `.bp` assets compile to the same JavaScript runtime and component API. The blueprint editor remains available for supported movement, rotation, input, trigger and scene operations; it is not a replacement for arbitrary code or an animator state-machine graph.