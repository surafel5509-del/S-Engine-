# S Engine Ultra Edition

**S Engine Ultra** is a professional-grade 2D/3D game engine and complete visual editor suite running on Android — built from scratch in Kotlin with OpenGL ES 2.0/3.0 rendering. A full Unity-class engine with **22,400+ lines of code**, **85 source files**, and **50+ built-in component types**.

> Custom renderer, physics engine, scripting runtime, visual editors, skeleton animation, timeline, material system, visual scripting, pathfinding, inventory, dialogue, ECS, procedural generation, 2D lighting, and **3 full professional game templates**.

---

## 🏆 Ultra Edition — What's Included

### 🎯 50+ Built-in Components

| Category | Components |
|---|---|
| **Rendering 2D** | SpriteRenderer, TextRenderer, Animator, ParticleEmitter, Camera, ParticleSystemV2 |
| **Rendering 3D** | MeshRenderer, Camera3D, Light |
| **UI System** | UICanvas, RectTransform, UIImage, UIText, UIButton, UISlider, UIScrollRect, UIPanel, UIProgressBar, UIToggle, UIDropdown, UIInputField, UILayoutGroup |
| **Skeleton Animation** | SkeletonRenderer, SkeletonAnimator |
| **Physics 2D** | Rigidbody2D, Collider2D |
| **Physics 3D** | Rigidbody3D, Collider3D |
| **Physics Joints** | SpringJoint2D, DistanceJoint2D, HingeJoint2D, WheelJoint2D, TargetJoint2D, RopeJoint2D, RelativeJoint2D |
| **Camera System** | VirtualCamera, CameraPath |
| **2D Lighting** | Light2D, DayNightCycle |
| **Pathfinding & AI** | NavAgent, NavSurface |
| **Tilemap** | Tilemap |
| **Prefabs** | PrefabLink |
| **Audio** | AudioSource, AudioMixer |
| **Scripting** | Script, Blueprint |
| **Materials** | TextureImporter, Material |

---

## 🚀 Professional Systems

### 1. 🗺️ A* Pathfinding & AI
- Full **A* pathfinding** on configurable navigation grids
- 8-directional movement with diagonal cost calculation
- **AI behaviors**: Wander, Pursue, Flee, Arrive
- NavAgent component with auto-repathing, stopping distance, acceleration
- Path smoothing and obstacle avoidance
- Grid-based NavSurface with walkability/cost editing

### 2. 📦 Prefab System
- Reusable game object templates (like Unity Prefabs)
- **PrefabLibrary** for project-wide template management
- Instantiation with position/parent support
- Property override tracking
- Category and tag-based organization
- Variant prefabs (prefab of prefabs)

### 3. ✨ Advanced Particle System V2
- **Sub-emitters** (birth, death, collision, trigger)
- **Trail system** with configurable width/color
- **Force zones**: Point, Directional, Vortex, Noise
- **Collision** with surfaces and tags
- **Color gradient** and **curve-driven** size/rotation
- Multiple emission shapes: Point, Circle, Rectangle, Cone, Edge
- 3 blend modes: Alpha, Additive, Multiply
- Pre-warming, burst emission, looping

### 4. 🗺️ 2D Tilemap System
- Multi-layer tilemap with sorting
- Tile palettes with animation support
- **Auto-tiling** based on neighbors
- Procedural dungeon generation (rooms + corridors)
- Procedural noise terrain (FBM with octaves)
- Collision and pathfinding cost integration
- Configurable cell size and anchors

### 5. 🔗 Physics Joints (7 Types)
- **Spring Joint** — Hooke's law with damping
- **Distance Joint** — Fixed/min/max distance
- **Hinge Joint** — Rotation with limits and motor
- **Wheel Joint** — Vehicle suspension with spring
- **Target Joint** — Pull toward target position
- **Rope Joint** — Maximum distance constraint
- **Relative Joint** — Lock relative position/rotation
- All joints support break force

### 6. 🎥 Cinemachine-Style Camera System
- **Virtual cameras** with priorities
- **Camera blending** with eased transitions
- **Screen composition**: dead zone, soft zone, bias
- **Look-ahead** target prediction
- **Camera confiner** (keep within bounds)
- **Noise/shake** with amplitude/frequency
- **Camera impulse** system (event-driven shake)
- **Camera paths** with smooth waypoint interpolation
- SmoothDamp helper for Unity-like smooth following

### 7. 🎵 Audio Mixer
- Multi-channel mixing: Master, Music, SFX, Ambient, UI
- Per-channel volume, pitch, pan
- Mute/solo support
- Channel hierarchy (parent buses)
- Audio effects: Reverb, LowPass, HighPass, Echo, Distortion, Compressor
- **Spatial audio** with logarithmic/linear rolloff
- Doppler effect support
- Audio snapshots for quick transitions

### 8. 🌍 Localization System
- Multi-language string tables
- Parameter substitution: `"Hello {name}!"`
- Plural rules (zero, one, few, many)
- Runtime language switching
- JSON import/export

### 9. 💾 Save System
- Multiple save slots with metadata
- Play time tracking, timestamp, scene name
- **XOR encryption** for save data protection
- Auto-save interval support
- JSON serialization with type preservation
- Play time formatting (HH:MM:SS)

### 10. 🎬 Scene Manager
- **Additive scene loading**
- Scene transitions with effects (Fade, Slide, Wipe, Dissolve)
- **Persistent objects** across scene changes
- Scene stacking and unloading
- Transition progress tracking

### 11. 💬 Dialogue System
- **Branching dialogue trees** with multiple speakers
- **Choice-based** branching with conditions
- **Condition evaluation**: equals, not equals, greater, less, has, not has
- **Commands**: set variables, give items, play animations, play sounds
- "Once" choices (hide after selected)
- Runtime dialogue player with state management
- Portrait support

### 12. 🎒 Inventory & Item System
- **Item definitions** with stats, rarity, categories
- **Stackable** and non-stackable items
- Equipment system with 8 slots (Head, Body, Hands, Feet, Weapon, Offhand, Accessory)
- Item categories: Weapon, Armor, Consumable, Material, Quest, Misc
- 5 rarity tiers: Common, Uncommon, Rare, Epic, Legendary
- **Crafting recipes** with ingredient requirements
- Gold/currency tracking
- Durability system
- Weight system for encumbrance

### 13. 🏗️ Procedural Generation
- **Perlin noise** with FBM (Fractal Brownian Motion)
- Heightmap generation with configurable octaves/lacunarity
- **Poisson disk sampling** for natural distribution
- **BSP dungeon generation** (rooms + corridors)
- **L-systems** for vegetation/organic structures
- Seed-based deterministic generation

### 14. 💡 2D Dynamic Lighting
- Point, Directional, and Spot lights
- Light falloff with inner/outer radius
- **Normal-mapped** 2D lighting
- Light masks/cookies
- 3 blend modes: Additive, Multiply, Overlay
- **Flicker effect** with configurable speed/range
- **Day/Night cycle** with ambient color transitions
- Shadow casting support
- Light probes for baked GI
- Manager for efficient multi-light queries

### 15. 🧬 Entity Component System (ECS)
- Data-oriented design for high-performance gameplay
- Component stores with O(1) lookup
- Tag system for entity grouping
- Multi-component queries
- Built-in ECS components: Transform, Velocity, Health, Damage, Sprite, AI State
- Complements the existing GameObject hierarchy

---

## 🎮 Professional Game Templates

### 2D Platformer Adventure
Full professional platformer with:
- Character controller (run, jump, double-jump, wall-jump, dash, coyote time)
- Walker/flyer enemies with patrol AI
- Coins, health pickups, checkpoints, level end portal
- Parallax backgrounds, moving platforms
- Professional HUD (health bar, coin counter, score)

### 3D Endless Runner
Professional 3D runner with:
- 3-lane switching system
- Procedural obstacles, coin collection
- Power-ups (shield, magnet, score multiplier)
- Dynamic camera, fog, lighting
- Particle trails, environment decoration
- Score/distance tracking with speed progression

### Top-Down RPG ⭐ (NEW)
Professional top-down RPG with:
- 8-directional player movement with combat
- NPC dialogue system with branching choices
- Inventory system with equipment slots
- Enemy AI with patrol, chase, and attack states
- Quest tracking, XP/leveling system
- Treasure chests with loot tables
- Day/Night cycle with dynamic lighting
- Procedural dungeon entrance
- Professional RPG HUD (health bar, XP bar, gold, level, quest log)
- Save/Load support

---

## 🛠️ Professional Editor Suite

| Editor | Description |
|---|---|
| **Scene Editor** | Full viewport with hierarchy, inspector, gizmos, grid, play controls |
| **Script Editor** | Syntax-highlighted JavaScript editor with API reference |
| **Animation Editor** | Sprite-sheet clip editor with frame selection, pivot editing |
| **UI Editor** | Visual canvas editor with drag-drop, anchor presets, element tree |
| **Skeleton Editor** | Bone hierarchy editor with IK handles, keyframe timeline |
| **Timeline Editor** | Multi-track animation with curve editor and state machine |
| **Material Editor** | PBR material properties, texture import, sprite slicing |
| **Blueprint Editor** | Visual scripting with 50+ node types and code generation |
| **Asset Store** | Browse and import game assets |
| **Build** | Export project as standalone APK |

---

## 📊 Final Stats

| Metric | Value |
|--------|-------|
| **Total Kotlin files** | 85 |
| **Total lines of code** | 22,409 |
| **Built-in components** | 50+ |
| **Professional systems** | 20+ |
| **Game templates** | 11 |
| **Editor activities** | 12+ |
| **Asset types** | 13 |

### Line Counts by System

| System | Lines |
|--------|-------|
| Tilemap System | 380 |
| Particle System V2 | 360 |
| Pathfinding & AI | 343 |
| Procedural Generation | 308 |
| Inventory System | 244 |
| Physics Joints | 230 |
| Camera System | 230 |
| 2D Lighting | 224 |
| Dialogue System | 205 |
| Audio Mixer | 187 |
| ECS | 188 |
| Prefab System | 150 |
| Save System | 146 |
| Scene Manager | 118 |
| Localization | 88 |

---

## Scripting API

```js
// Full JavaScript (ES6) via Mozilla Rhino
function start() { log("Hello from " + self.name); }
function update(dt) {
    self.vx = input.axisX * speed;
    if (input.aDown && self.grounded) { self.vy = jump; audio.beep(); }
}
function onTrigger(other) {
    if (other.tag == "Coin") { coins++; other.destroy(); }
}
```

### API: self, input, scene, audio, storage, time, helpers

---

## Building

Requirements: JDK 17, Android SDK (API 34), Android Studio Hedgehog+.

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Minimum Android: 8.0 (API 26). Requires OpenGL ES 2.0.

---

## License

MIT

---

## 🤖 AI Game Builder System

### Overview

S Engine now includes a **complete AI Game Builder** system with support for multiple AI providers. Generate scripts, design levels, write dialogue, fix bugs, and build entire games using natural language.

### Supported AI Providers

| Provider | Models | Best For |
|----------|--------|----------|
| **OpenAI (ChatGPT)** | GPT-4 Turbo, GPT-4, GPT-3.5 | Best overall quality, complex reasoning |
| **Google Gemini** | Gemini Pro, Gemini 1.5 Pro | Good balance of speed and quality |
| **OpenRouter** | 100+ models (GPT-4, Claude, Llama, etc.) | Access to many models via one API |
| **Groq** | Llama 2 70B, Mixtral, Gemma | Ultra-fast inference, free tier |

### AI Features

#### 🗨️ AI Chat Assistant
- Conversational interface for game development help
- Context-aware responses using S Engine API knowledge
- Quick actions for common tasks
- Full chat history with scrolling

#### 🎮 Full Game Generation
- **Quick Generate** — Rapid prototyping from description
- **Full Game** — Complete game with design doc, scene, scripts, and assets
- Genre-specific templates (Platformer, RPG, Shooter, Puzzle, etc.)
- Difficulty scaling (Simple, Medium, Complex)

#### 📝 Script Generation
- Generate player controllers, enemy AI, UI scripts
- Object-type templates (Player, Enemy, NPC, Item, Platform)
- Complete, working code with comments
- S Engine API-aware generation

#### 🔧 Code Analysis & Fixing
- **Analyze** — Code quality review, bug detection, performance analysis
- **Fix** — Describe the bug, get fixed code with explanations
- **Optimize** — Performance optimization with explanations

#### 🗺️ Level Design
- AI-powered level layout generation
- Genre and theme-specific designs
- Enemy placement, collectibles, progression
- Complete with coordinates and specifications

#### 💬 Dialogue Generation
- NPC dialogue with personality
- Branching conversations
- Quest dialogue
- Multiple lines with proper formatting

#### 📖 Code Explanation
- Paste code or describe a concept
- Get detailed explanations
- Learn S Engine API and game dev concepts

### AI Settings

Configure in the **AI Settings** screen:
- API keys for each provider (stored locally, encrypted)
- Model selection per provider
- Temperature (creativity level: 0.0-2.0)
- Max tokens (response length)
- Direct links to get API keys

### Getting API Keys

| Provider | Where to Get | Free Tier |
|----------|-------------|-----------|
| OpenAI | [platform.openai.com/api-keys](https://platform.openai.com/api-keys) | No |
| Gemini | [aistudio.google.com/app/apikey](https://aistudio.google.com/app/apikey) | Yes |
| OpenRouter | [openrouter.ai/keys](https://openrouter.ai/keys) | Yes (some models) |
| Groq | [console.groq.com/keys](https://console.groq.com/keys) | Yes |

### AI Architecture

```
engine/ai/
├── AISettings.kt         API key management, provider configuration
├── AIProvider.kt         Provider interface, base implementation
├── OpenAIProvider.kt     ChatGPT/GPT-4 integration
├── GeminiProvider.kt     Google Gemini integration
├── OpenRouterProvider.kt OpenRouter multi-model access
├── GroqProvider.kt       Groq ultra-fast inference
├── AIAssistant.kt        Main orchestration, chat, task management
├── AIGameBuilder.kt      Full game generation pipeline
└── AIPrompts.kt          Pre-built prompt templates

ui/
├── AIAssistantActivity.kt   Chat interface with quick actions
├── AISettingsActivity.kt    API key & settings configuration
└── AIGameBuilderActivity.kt Game generation interface
```

### AI Integration with Engine

The AI system integrates directly with S Engine:
- **Scene parsing** — AI-generated JSON becomes real game objects
- **Script creation** — AI code is saved as project scripts
- **Asset management** — AI-generated asset lists track needed resources
- **Design documents** — Full game design saved to project
- **Context awareness** — AI knows S Engine API and conventions

### Privacy & Security

- API keys stored **only on device** in encrypted settings
- Requests sent **directly** to AI providers (no中间 server)
- No conversation data stored by S Engine
- Full transparency — open-source AI integration code

