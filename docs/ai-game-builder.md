# AI Game Builder for S Engine

Status: implemented local-first vertical slice with three provider modes: Local Demo, Direct OpenRouter, and authenticated Secure Supabase. Direct OpenRouter needs a key entered by the device user at runtime; no provider secret is bundled in the app. No Supabase function or migration has been deployed from this repository session.

## What is implemented

The Builder is available from **Projects → AI Builder** and from the in-editor **AI** toolbar/menu. It uses the existing S Engine project manager, templates, `Project`, `Scene`, `SceneSerializer`, component registry, project asset directory, editor activity, player activity, and simulation engine. It does not create a parallel game-project format.

### Local creation and editing

- The installed template picker is sourced from `Templates.all`; template IDs are stable names such as `platformer-demo`, `space-shooter`, `3d-demo`, and `hill-climb-racer`.
- Local Demo mode is the default and is explicitly labeled. It makes no network/model request. It chooses a real installed template from prompt keywords and creates a real project through a hidden staging directory. The project is only published under its final name after scene validation and an S Engine headless startup smoke test pass. Existing project names are never overwritten.
- Demo mode can make a small set of deterministic edits to an existing project: change a numeric `jump`/`speed` parameter on an existing script without rewriting source, adjust camera framing, enlarge the existing Player/Car, or add a static screen-space `TextRenderer` label. Unsupported local edits are explained instead of faked.
- AI plans are shown before application. Apply uses a strict GameSpec and a bounded allowlist of typed operations. Edits are limited to one scene per transaction and up to 20 operations. There is no shell execution, arbitrary path writing, model-generated JavaScript, or arbitrary `Script.Params` string editing.
- Project edits are written through `Project.saveScene`, reloaded through `Project.loadScene`, checked for graph/assets/script issues, and restored from the original serialized scene on failure. A successful single-scene edit can be undone from the Builder; snapshots are app-private and remain on device.
- On successful application, the actual project opens in `EditorActivity`; the existing Editor toolbar has an AI entry and its scene reloads validated file changes when resumed. The Builder also links to the existing full-screen Player.

### Versioned plan and validation

`app/src/main/java/com/sengine/aibuilder/spec/` defines GameSpec v1, a strict bounded JSON codec, and validation. `GameSceneValidator` checks the S Engine scene JSON, entity IDs, parent graph, unique names, transforms, existing asset references, safe script names, Rhino syntax, and Blueprint compilation. The runtime smoke test uses the existing `Engine` and runs two simulation frames; it does **not** prove that a device/GPU rendered the game. For newly created projects, it executes only the installed template scripts, with script storage redirected to a temporary directory and audio disabled. For edits and rollback of existing projects, it syntax-checks scripts but disables script execution during the smoke test to avoid running user-authored code automatically.

Supported operations are: add entity with allowlisted components, set transform, add a supported component, set a validated inspector property, update an allowlisted numeric parameter on an existing script, guarded entity rename, set scene gravity, and add static HUD text. Component property types are checked against the real `Component.props()` API. Asset values must be safe file names that exist in the selected project and match their `AssetKind`.

The GameSpec has its own `schemaVersion` and currently accepts version 1 only. S Engine's existing `project.json` and scene JSON formats are unchanged, so old project files remain readable without migration.

### AI provider boundary

`AiProvider` is provider-neutral and offers two network paths:

- **Direct OpenRouter:** `Android app → OpenRouter`, using an OpenRouter API key entered by the user at runtime. The app encrypts that key with Android Keystore and sends it only as an HTTPS authorization header to OpenRouter. It skips Supabase Auth, Edge Functions, database sync, and cloud memory. There is no key embedded in source or the APK. The selected model ID, sanitized prompt, bounded retrieved context, and fresh project snapshot go to OpenRouter. Requests have timeouts, cancellation, response-size bounds, JSON-mode fallback when rejected, and actionable HTTP-status messages.
- **Secure Supabase:** `Android app → authenticated Supabase Edge Function → OpenRouter`. The Android app contains no bundled OpenRouter credential. The Edge Function verifies the Supabase session, checks request and context sizes, enforces an atomic 30-requests-per-user-per-hour quota, calls OpenRouter with a 45-second timeout, asks for JSON output when supported, and retries once without JSON mode only when the provider rejects that feature.

To avoid a misleading offline fallback, choosing either network mode and encountering a network/provider failure produces an error; it does not silently switch to Demo mode. Streaming is not implemented.

### Memory and privacy

Conversation history, project decisions, and build events live separately from the game project in the app-private `noBackupFilesDir/ai-builder/memory` store (excluded from Android automatic backup). The project’s current files/scenes are always inspected again for each request; saved memory is context, not a source of truth. Context is bounded to the last eight messages, six design decisions, four build/repair events, and an 8 KB JSON package. Project snapshots include bounded scene/entity/component/asset metadata and no absolute paths or script source.

Memory is enabled by default and can be disabled or deleted in **AI Builder → Memory**. Existing local data remains until explicitly deleted. Common provider-key, bearer-token, password, and API-key assignment forms are redacted before prompt storage/forwarding. Supabase session tokens are AES-GCM encrypted with an Android Keystore key; passwords are never persisted. Rollback scene snapshots are device-only and are not synced.

In Direct OpenRouter and Local Demo modes, memory storage is device-only and no Supabase requests are made. With memory enabled, bounded retrieved memory and project context are still included in network-mode prompts sent to the selected AI provider; disable memory to omit prior history. Only when Secure AI is selected and a Supabase user is signed in are history/build records best-effort synced to `ai_memory_records`, private per-user PostgREST storage protected by RLS. App-local project files are **not** uploaded to Supabase. Records synced in a previous Secure AI session remain in Supabase until deleted while using Secure mode; Direct-mode deletion is local only. Optional embeddings/pgvector are not included: bounded structured retrieval is sufficient for the current slice and no embedding provider is configured.

## Supabase deployment

No live Supabase project has been configured or modified for this build. Credentials were inspected for the user-selected UDS project, but no schema, migration, or function was deployed there. The migration and function below are deployment artifacts; they are not evidence of a successful deployment.

1. Create/select a Supabase project you control and configure Email Auth. Keep the project URL and publishable/anon key for the Android client. Never use a service-role key in the app.
2. Apply the migration from the repository root:

   ```bash
   supabase db push
   ```

   It creates `ai_memory_records` with user ownership, RLS policies, indexes, an update timestamp trigger, a private usage-counter table, and an atomic service-role-only quota RPC.
3. Configure the server-side OpenRouter secret. Use the real secret in your terminal/secret manager only; do not put it in source control or chat:

   ```bash
   supabase secrets set OPENROUTER_API_KEY=YOUR_ROTATED_OPENROUTER_KEY
   ```

   Supabase supplies its project URL, anon key, and service-role key to Edge Functions as server environment variables. The service-role key is used only inside the function for the quota RPC.
4. Deploy the authenticated function:

   ```bash
   supabase functions deploy ai-game-builder
   ```

   `supabase/config.toml` sets `verify_jwt = true`; the function also verifies the bearer session with Supabase Auth before using the provider secret.
5. In S Engine, open AI Settings, enter the HTTPS Supabase project URL, the **publishable/anon key**, and an OpenRouter model ID. Sign in or create an account with Supabase Auth, leave Direct OpenRouter and Local Demo unchecked, and save. The app calls only `/functions/v1/ai-game-builder` for model requests.
6. If email confirmation is enabled, confirm the account before sign-in. Tune Supabase Auth redirect/email settings according to your deployment. Do not enable public unauthenticated function access.

Supabase deployment is optional: to bypass Supabase entirely, open AI Settings, enter the selected OpenRouter model and your own newly rotated OpenRouter API key in the Direct OpenRouter field, select **Direct OpenRouter**, and save. The key is encrypted on-device and sent only to OpenRouter over HTTPS; Direct mode keeps memory local. Never paste the key into source code, logs, or chat. The previously disclosed key should be revoked and replaced.

The Edge Function has no database access to game project files and does not persist prompts by itself. Conversation sync goes through the RLS-protected table using the signed-in user's access token. It rejects oversized requests, validates the caller, and uses a service-role-only RPC for a 30/hour quota. This is a first-release limit, not a billing or abuse-prevention substitute for provider-side budgets/monitoring.

## App usage

1. Open S Engine and tap **AI Builder** (or open an existing project and tap the **AI** toolbar button).
2. Choose **Create new game** or an existing project. For new games, optionally select one of the installed templates or leave it on Auto.
3. Enter a prompt, tap **Plan request**, inspect the summary/operations/validation/GameSpec JSON, then tap **Build & open editor** or **Apply & open editor**.
4. The app validates, saves, reloads, and runs the headless smoke check. The existing editor opens; press Play to run the project through S Engine. The smoke check is not a visual/device test.
5. Return to the Builder for another edit. Use Memory to inspect, disable, or delete saved history. Use **Undo AI edit** for the most recent supported single-scene edit where a local snapshot was recorded.

Without a Supabase configuration/account, Local Demo remains available for offline template creation and supported safe edits. Direct OpenRouter also works without Supabase once the device user enters an OpenRouter key. Existing project editing in the editor, import/export, and runtime do not require AI configuration.

## Verified engine integration and current limitations

Audited source-level APIs: `ProjectManager`, `Project`, `Templates`, `Scene`, `SceneSerializer`, `GameObject`, `ComponentRegistry`, each component's `props()`, project asset lists, Rhino `ScriptComponent` runtime, `Engine.tick`, `EditorActivity`, and `PlayerActivity`. S Engine supports 2D and 3D rendering/simulation, but this Builder intentionally does not synthesize unrestricted game logic or external image/model/audio assets.

Known gaps:

- The local Demo planner is intentionally narrow and keyword-based; it is not a language model and will explain unsupported edits.
- Secure AI requires a deployed Supabase project, an Auth account, and a server-side OpenRouter secret. It has not been exercised against live credentials in this environment. Direct OpenRouter still requires the device user to provide a valid key, have provider billing enabled, and select an available model; a live provider request has not been performed here.
- Supabase is used for private AI memory and the protected provider boundary, not cloud game-project storage/collaboration or project version hosting.
- No pgvector or semantic embedding search, streamed model responses, AI script generation, generated 3D assets, arbitrary removal operations, or multi-scene edit transaction is implemented.
- Scene validation and two-frame startup do not replace the existing Play flow or a real Android/GPU rendering test. Device testing is still required for visual gameplay, input feel, and long-running stability.
- Supabase Auth/browser redirect configuration, RLS behavior against a deployed project, provider usage/billing, and actual OpenRouter model compatibility require a configured environment to verify.

## Tests and build commands

Added automated source tests cover GameSpec parsing/version/dimensions/allowlist failures, OpenRouter envelope parsing/malformed responses, memory isolation/redaction/merge/rollback, real template loading, actual `Project`/`SceneSerializer` edits, rollback on a failed operation batch, and a headless runtime startup.

Run on a machine with JDK 17 and Android SDK API 34:

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
```

The current coding sandbox has no Java/JDK, no Android SDK, and cannot execute Gradle. Those commands were therefore **not run here**. The Edge Function TypeScript passed a strict no-emit TypeScript 7 check with a minimal local declaration for Deno's `env` and `serve` APIs; this is not a Deno runtime test or deployment. The SQL migration parsed successfully with `pglast` but was not applied to a database. A device test, live Supabase test, and live OpenRouter test were also **not run**.
