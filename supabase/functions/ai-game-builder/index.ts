const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, apikey, content-type",
  "Access-Control-Allow-Methods": "POST, OPTIONS",
  "Content-Type": "application/json; charset=utf-8",
};

const MAX_BODY_BYTES = 32_000;
const MAX_PROMPT_CHARS = 8_000;
const MAX_CONTEXT_CHARS = 9_000;
const MAX_PROJECT_CHARS = 14_000;
const MAX_RESPONSE_CHARS = 96_000;

function reply(status: number, body: Record<string, unknown>): Response {
  return new Response(JSON.stringify(body), { status, headers: corsHeaders });
}

function safeText(value: unknown, max: number): string | null {
  return typeof value === "string" && value.trim().length > 0 && value.length <= max ? value.trim() : null;
}

function redactSecrets(value: string): string {
  return value
    .replace(/\bsk-or-v1-[A-Za-z0-9_-]{16,}\b/gi, "[REDACTED]")
    .replace(/\bBearer\s+[A-Za-z0-9._~+/-]{16,}={0,2}/gi, "Bearer [REDACTED]")
    .replace(/\b(api[_ -]?key|password|passwd|secret|access[_ -]?token|refresh[_ -]?token)\s*[:=]\s*[^\s,;]{6,}/gi, "$1=[REDACTED]");
}

function compactJson(value: unknown, maxChars: number): string | null {
  if (value === undefined) return "null";
  let encoded: string;
  try { encoded = JSON.stringify(value); } catch { return null; }
  return encoded.length <= maxChars ? encoded : null;
}

async function readBodyLimited(request: Request): Promise<string | null> {
  if (!request.body) return "";
  const reader = request.body.getReader();
  const chunks: Uint8Array[] = [];
  let total = 0;
  while (true) {
    const { done, value } = await reader.read();
    if (done) break;
    total += value.byteLength;
    if (total > MAX_BODY_BYTES) {
      await reader.cancel();
      return null;
    }
    chunks.push(value);
  }
  const joined = new Uint8Array(total);
  let offset = 0;
  for (const chunk of chunks) { joined.set(chunk, offset); offset += chunk.byteLength; }
  return new TextDecoder().decode(joined);
}

function systemInstructions(projectSnapshot: unknown, context: unknown, preferredTemplate: string | null): string {
  return `You are the safe planning assistant for S Engine, an Android game engine. Return exactly one JSON object conforming to GameSpec schema version 1. Do not return markdown, shell commands, arbitrary file paths, JavaScript source, GLSL, or instructions to execute code.

The Android client will independently validate the entire response before applying anything. The real engine supports 2D and 3D scenes, multiple scenes, sprites/text, cameras, 2D/3D rigidbodies and colliders, particles, audio references, animation, lights, existing JavaScript/Blueprint assets, and project templates. Never claim support for an operation outside the allowlist below. Existing project files are the source of truth; use only the supplied fresh project snapshot and existing asset names. No model-generated script or unrestricted source editing is allowed.

GameSpec shape:
{"schemaVersion":1,"action":"create|edit|explain","projectName":"simple project name","genre":"short genre","dimension":"2d|3d","templateId":"installed template id or empty for edit/explain","summary":"clear user-facing summary and limitations","mechanics":["short mechanic"],"operations":[...]}.

Installed template IDs (choose one for create): empty-2d, platformer-demo, space-shooter, physics-sandbox, 3d-demo, animated-platformer, blueprint-demo, hill-climb-racer. A template's real gameplay and assets are already installed with the template; do not invent assets. If a requested feature is not supported safely, explain that in summary instead of fabricating it.

If the supplied project snapshot reports truncation, treat omitted scenes, entities, and assets as unknown; do not invent targets or asset names. If the requested edit needs omitted details, return action=explain and ask for one exact name.

The only operation types are:
1. {"type":"set_transform","scene":"start or exact existing scene name","objectName":"exact existing entity","x":0,"y":0,"z":0,"rotation":0,"rotX":0,"rotY":0,"scaleX":1,"scaleY":1,"scaleZ":1} (include only fields to change)
2. {"type":"set_component_property","scene":"start or exact scene","objectName":"exact entity","componentType":"exact supported type","property":"exact editable inspector property","value":"scalar"} (value must have the correct JSON scalar type)
3. {"type":"set_script_parameter","scene":"...","objectName":"exact entity","scriptAsset":"existing asset if known","key":"speed|jump|jumpHeight|health|maxHealth|damage|fireRate|range|gravity","value":5}. This changes only a numeric existing parameter; never change source code.
4. {"type":"add_component","scene":"...","objectName":"exact entity","component":{"type":"supported component","properties":{"inspector property":"scalar"}}}
5. {"type":"add_entity","scene":"...","name":"simple name","x":0,"y":0,"z":0,"rotation":0,"scaleX":1,"scaleY":1,"scaleZ":1,"tag":"Untagged","components":[{"type":"SpriteRenderer","properties":{"Color":"#FFFFFFFF"}}]}
6. {"type":"add_hud_text","scene":"...","name":"simple name","text":"static editable label","x":0,"y":3.5,"size":0.45,"color":"#FFFFFFFF","order":100}. This is a static TextRenderer; it does not create gameplay logic.
7. {"type":"set_scene_gravity","scene":"...","gravityX":0,"gravityY":-9.81,"gravityZ":0,"gravity3D":-9.81} (include only changed values).
8. {"type":"rename_entity","scene":"...","objectName":"exact entity","newName":"simple name"}. A client check may reject renames that have script references.

Use at most 20 operations, with small changes. Do not add duplicate entities/components. For edits, target one scene only, use exact names from the snapshot, set action=edit and projectName to the selected project name. For explanations, use action=explain and operations=[]. For new projects, use action=create and a real installed template ID. If a user asks a follow-up question whose answer is needed to avoid destructive ambiguity, use action=explain and ask one concise question in summary; otherwise use reasonable defaults.

Fresh project snapshot: ${compactJson(projectSnapshot, MAX_PROJECT_CHARS) ?? "null"}
Recent conversation and project/build memory: ${compactJson(context, MAX_CONTEXT_CHARS) ?? "{}"}
Preferred template selection: ${preferredTemplate ?? "auto"}

Editable component names and property examples:
SpriteRenderer: Shape (Square|Circle|Triangle), Color (#RRGGBB/#AARRGGBB), Texture (existing texture asset), Flip X, Flip Y, Pivot X, Pivot Y, Shader (existing .glsl asset), Shader Param, Screen Space UI.
TextRenderer: Text, Screen Space UI, Size, Color, Align (Left|Center|Right), Bold.
Camera: Size, Background, Follow Target, Offset X, Offset Y, Follow Smoothing, Post FX, FX Intensity, FX Shader.
Rigidbody2D: Body Type (Dynamic|Kinematic|Static), Mass, Gravity Scale, Linear Drag, Bounciness, Friction, Freeze Rotation, Angular Drag, Inertia Scale, Velocity X, Velocity Y, Angular Velocity.
Collider2D: Shape (Box|Circle), Width, Height, Radius, Offset X, Offset Y, Is Trigger.
Camera3D: Field of View, Near, Far, Sky Top, Sky Horizon, Follow Target, Offset X, Offset Y, Offset Z, Look At Target, Follow Smoothing, Post FX, FX Intensity, FX Shader.
MeshRenderer: Mesh (Cube|Sphere|Plane|Cylinder|Cone|Torus|Capsule|Pyramid|Custom Model), Model (.obj), Color, Texture, Tiling, Specular, Shininess, Emission, Unlit, Shader, Shader Param.
Rigidbody3D, Collider3D, ParticleEmitter, AudioSource, Animator, and Light use only the matching inspector properties present in the snapshot/engine. Asset properties must reference listed existing assets. Do not write Params or a Script source body.
`;
}

function extractContent(data: unknown): { content: string; model: string; usage: unknown } | null {
  if (!data || typeof data !== "object") return null;
  const root = data as Record<string, unknown>;
  const choices = Array.isArray(root.choices) ? root.choices : [];
  const first = choices[0] as Record<string, unknown> | undefined;
  const message = first?.message as Record<string, unknown> | undefined;
  let content = typeof root.content === "string" ? root.content : message?.content;
  if (Array.isArray(content)) {
    content = content.map((part) => {
      if (part && typeof part === "object" && (part as Record<string, unknown>).type === "text") {
        return String((part as Record<string, unknown>).text ?? "");
      }
      return "";
    }).join("");
  }
  if (typeof content !== "string" || content.trim().length === 0 || content.length > MAX_RESPONSE_CHARS) return null;
  return {
    content,
    model: typeof root.model === "string" ? root.model : "",
    usage: root.usage ?? null,
  };
}

Deno.serve(async (request: Request) => {
  if (request.method === "OPTIONS") return new Response("ok", { headers: corsHeaders });
  if (request.method !== "POST") return reply(405, { error: { code: "method_not_allowed", message: "Use POST." } });

  const contentLength = Number(request.headers.get("content-length") ?? "0");
  if (contentLength > MAX_BODY_BYTES) return reply(413, { error: { code: "request_too_large", message: "Request exceeds the 32 KB limit." } });

  const supabaseUrl = Deno.env.get("SUPABASE_URL")?.replace(/\/$/, "");
  const anonKey = Deno.env.get("SUPABASE_ANON_KEY");
  const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY");
  const openRouterKey = Deno.env.get("OPENROUTER_API_KEY");
  if (!supabaseUrl || !anonKey || !serviceRoleKey || !openRouterKey) {
    return reply(503, { error: { code: "server_not_configured", message: "The secure AI backend is not fully configured. Ask the project administrator to deploy its secrets." } });
  }

  const authHeader = request.headers.get("authorization") ?? "";
  if (!/^Bearer\s+\S+$/i.test(authHeader)) return reply(401, { error: { code: "unauthorized", message: "A signed-in Supabase user is required." } });
  const userResponse = await fetch(`${supabaseUrl}/auth/v1/user`, {
    method: "GET",
    headers: { apikey: anonKey, authorization: authHeader, accept: "application/json" },
    signal: AbortSignal.timeout(10_000),
  }).catch(() => null);
  if (!userResponse?.ok) return reply(401, { error: { code: "unauthorized", message: "Supabase could not verify this session. Sign in again." } });
  const user = await userResponse.json().catch(() => null) as { id?: string } | null;
  if (!user?.id || !/^[0-9a-f-]{36}$/i.test(user.id)) return reply(401, { error: { code: "unauthorized", message: "The authenticated user identity is invalid." } });

  const raw = await readBodyLimited(request).catch(() => null);
  if (raw === null) return reply(413, { error: { code: "request_too_large", message: "Request exceeds the 32 KB limit." } });
  let payload: Record<string, unknown>;
  try { payload = JSON.parse(raw) as Record<string, unknown>; }
  catch { return reply(400, { error: { code: "invalid_json", message: "Request body must be JSON." } }); }

  if (payload.schemaVersion !== 1) return reply(400, { error: { code: "unsupported_request_version", message: "Client request schema version 1 is required." } });
  const rawPrompt = safeText(payload.prompt, MAX_PROMPT_CHARS);
  const model = safeText(payload.model, 120);
  if (!rawPrompt || !model || !/^[A-Za-z0-9._:/-]{3,120}$/.test(model)) {
    return reply(400, { error: { code: "invalid_request", message: "Prompt or model is missing or malformed." } });
  }
  const prompt = redactSecrets(rawPrompt);
  const contextText = compactJson(payload.context ?? {}, MAX_CONTEXT_CHARS);
  const projectText = compactJson(payload.projectSnapshot ?? null, MAX_PROJECT_CHARS);
  const preferredTemplate = typeof payload.preferredTemplateId === "string" && /^[a-z0-9-]{1,48}$/.test(payload.preferredTemplateId)
    ? payload.preferredTemplateId
    : null;
  if (contextText === null || projectText === null) return reply(400, { error: { code: "context_too_large", message: "Project state or memory exceeds the context limit." } });

  // One atomic per-user hourly quota. Only this server-side function can call the service-role RPC.
  const quotaResponse = await fetch(`${supabaseUrl}/rest/v1/rpc/claim_ai_builder_request`, {
    method: "POST",
    headers: {
      apikey: serviceRoleKey,
      authorization: `Bearer ${serviceRoleKey}`,
      "content-type": "application/json",
      accept: "application/json",
    },
    body: JSON.stringify({ p_owner_id: user.id, p_limit: 30 }),
    signal: AbortSignal.timeout(10_000),
  }).catch(() => null);
  if (!quotaResponse?.ok) return reply(503, { error: { code: "quota_service_unavailable", message: "Usage limits could not be verified. Try again later." } });
  const quota = await quotaResponse.json().catch(() => false);
  if (quota !== true) return reply(429, { error: { code: "rate_limited", message: "This account reached its limit of 30 AI requests per hour." } });

  const userPayload = JSON.stringify({
    prompt,
    context: JSON.parse(contextText),
    projectSnapshot: payload.projectSnapshot ?? null,
    preferredTemplateId: preferredTemplate,
  });
  const messages = [
    { role: "system", content: systemInstructions(payload.projectSnapshot ?? null, JSON.parse(contextText), preferredTemplate) },
    { role: "user", content: userPayload },
  ];
  const providerRequest = (jsonMode: boolean) => fetch("https://openrouter.ai/api/v1/chat/completions", {
    method: "POST",
    headers: {
      authorization: `Bearer ${openRouterKey}`,
      "content-type": "application/json",
      "x-title": "S Engine AI Game Builder",
    },
    body: JSON.stringify({
      model,
      messages,
      temperature: 0.2,
      max_tokens: 3_000,
      ...(jsonMode ? { response_format: { type: "json_object" } } : {}),
    }),
    signal: AbortSignal.timeout(45_000),
  });

  let modelResponse: Response;
  try {
    modelResponse = await providerRequest(true);
    if (modelResponse.status === 400) {
      const diagnostic = await modelResponse.clone().text().catch(() => "");
      if (/response_format|json.?mode|structured.?output/i.test(diagnostic)) modelResponse = await providerRequest(false);
    }
  } catch {
    return reply(504, { error: { code: "provider_timeout", message: "OpenRouter did not respond before the 45-second timeout. Retry or choose a faster model." } });
  }
  if (!modelResponse.ok) {
    const status = modelResponse.status;
    if (status === 429) return reply(429, { error: { code: "provider_rate_limited", message: "OpenRouter rate limited this request. Wait and retry." } });
    if (status === 401 || status === 403) return reply(503, { error: { code: "provider_not_configured", message: "The server-side OpenRouter credential was rejected. Ask the administrator to rotate and configure it." } });
    if (status === 400 || status === 404) return reply(422, { error: { code: "model_request_rejected", message: "OpenRouter rejected this model or request. Verify model availability and choose a compatible model." } });
    return reply(502, { error: { code: "provider_unavailable", message: `OpenRouter returned HTTP ${status}. Retry later.` } });
  }

  let providerData: unknown;
  try {
    const rawResponse = await modelResponse.text();
    if (rawResponse.length > 300_000) return reply(502, { error: { code: "provider_response_too_large", message: "OpenRouter response exceeded the size limit." } });
    providerData = JSON.parse(rawResponse);
  } catch {
    return reply(502, { error: { code: "provider_malformed_response", message: "OpenRouter returned malformed JSON." } });
  }
  const result = extractContent(providerData);
  if (!result) return reply(502, { error: { code: "provider_empty_response", message: "OpenRouter returned no usable assistant content." } });
  return reply(200, {
    content: result.content,
    model: result.model || model,
    usage: result.usage,
    provider: "openrouter",
  });
});
