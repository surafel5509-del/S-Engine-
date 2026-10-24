# AI Game Studio

S Engine's AI Game Studio builds real, editable S Engine projects on-device. It connects directly to a model provider selected by the user; there is no S Engine AI backend, Supabase dependency, sign-in, project upload, or bundled provider secret.

## Providers and setup

1. Open **AI Builder → Settings**.
2. Choose **OpenRouter**, **OpenAI / ChatGPT API**, **Google Gemini**, **Claude (Anthropic)**, or **Other OpenAI-compatible**.
3. Confirm or enter the provider's model ID. For a custom compatible provider, add its HTTPS API base URL.
4. Paste the provider API key and tap **Done**. Keys are encrypted with Android Keystore and stored outside game projects. Leave the key field blank to retain the saved key. Use **Forget this provider's saved API key** to remove it.
5. Enter a game brief and choose **Plan**, **Build game**, or **Agent**.

Provider APIs, model IDs, access, billing and rate limits are controlled by each provider. A valid key and an available model are required for online builds. A ChatGPT consumer subscription is separate from OpenAI API access; use an OpenAI API key and billing-enabled API account for the OpenAI / ChatGPT API option. The app sends the prompt, bounded project metadata, and—when device memory is enabled—bounded prior conversation context directly to that selected provider over HTTPS. OpenAI-compatible custom endpoints should be used only when trusted. Provider requests use cancellation, response/request-size limits, timeouts and no redirects. API keys are never logged or included in request bodies.

**Local Template mode** makes no model or network calls. It uses S Engine's installed templates and the deliberately narrow local planner.

## Building a game

- **Plan** asks for a structured plan and shows its summary, selected template, operations, validation and GameSpec JSON. It does not change the project until you apply the plan.
- **Build game** plans, validates, applies one bounded transaction, saves/reloads the project and runs the headless S Engine startup check, then opens the editor.
- **Agent** runs a cancellable autonomous loop (default three passes, configurable from one to five). It can plan, build, inspect a fresh project snapshot and request a safe polish pass. A validation/build failure can be repaired within the pass limit; failed edit batches are rolled back. Every successful pass is committed to the project separately, and prior supported changes can be undone from the build report.

For a new game, choose a close installed template or leave template selection on Auto. The AI is instructed to make a playable first scene, select a supported control/gameplay loop, and use appropriate physics, lighting, UI and feedback. Available templates include Platformer Demo, Space Shooter, 3D Demo, Hill Climb Racer, Lumen Ascent, Aurora Station, Physics Sandbox, Animated Platformer and Blueprint Demo. Existing projects can be selected for safe edits.

The model returns a versioned GameSpec—not executable tool calls. The app independently validates template IDs, component names/properties, asset references, paths, dimensions, numeric ranges and operation count before applying anything. Edits are transactional and backed up locally. The headless smoke check exercises the actual engine simulation but is not a substitute for visual playtesting on an Android device.

## Generated assets and safety boundary

AI builds can use the following bounded, locally generated assets:

- **Pixel-art textures/sprites:** transparent RGBA PNGs drawn from a recipe palette of up to 16 colors, at most 64×64 pixels per recipe.
- **Sound effects:** synthesized mono PCM WAV effects, 80–3,000 ms, using sine, square, triangle, saw or noise waveforms. These are simple effects, not music, speech or recordings.
- **Animation clips:** `.anim` metadata for existing or earlier-generated sprite sheets.
- **Shaders:** a fixed set of vetted presets (pulse, grayscale, sepia, vignette, pixelate, invert, chromatic); arbitrary GLSL is not generated.
- **Visual blueprints:** fixed, vetted controller/movement/rotator recipes using S Engine nodes; arbitrary JavaScript or Blueprint code is not generated.

Generated filenames must be simple asset names, generated assets are never allowed to overwrite existing files, and recipes are limited to eight generated assets per plan. Components can use installed S Engine physics, 2D/3D rendering, lights, particles, audio, animation and controls. Existing templates supply larger gameplay systems and their pre-authored scripts/assets.

This is a capable mobile game-builder workflow, not a claim that a single text prompt produces a finished commercial/Play Store release. The current tool boundary does **not** generate high-resolution diffusion art, 3D models, long-form audio/music, unrestricted scripts, arbitrary shaders, arbitrary filesystem changes, multiplayer/backend services or store-ready signing assets. Choose a close template, inspect the plan, iterate with the agent, then playtest and polish in the editor.

## Local memory and privacy

Conversation history, design decisions, build summaries and rollback snapshots are stored in app-private `noBackupFilesDir` storage, separate from game projects. Saving this device-only history and undo data can be disabled or deleted in **AI Builder → Memory**. When disabled, prompts are not added to history and successful edits do not retain a persistent undo snapshot; failed operation batches still roll back. The project snapshot sent to a provider contains bounded scene/object/component/asset metadata; it omits absolute paths and script source. Sensitive-looking key/token/password patterns are redacted before memory storage and forwarding. Local memories and generated-project files are not uploaded to an S Engine service.

## APK build

Every push and pull request runs the Android unit tests and assembles debug and release APKs in `.github/workflows/android.yml`. Download them from the GitHub Actions run's **Artifacts → SEngine-Android-APKs**. The release artifact remains debug-key signed for convenient testing; replace the signing configuration before Play Store publication.

On a machine with JDK 17 and Android SDK API 34:

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

A real provider request requires a user-supplied key and was not part of the automated offline tests. Review the provider's own terms, billing and privacy policy before sending prompts or project metadata.
