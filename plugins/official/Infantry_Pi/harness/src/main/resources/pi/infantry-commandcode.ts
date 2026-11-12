/**
 * Infantry AI Command Code provider for pi (RUNNER-GREEN-RUN-1). Written by the Infantry AI daemon and loaded with `-e` on every pi run.
 *
 * pi has no built-in Command Code provider, so a `commandcode/*` model was "not found" even with the key lent. Command Code speaks the
 * OpenAI chat-completions protocol at the same endpoint opencode's commandcode provider uses. With `CMD_API_KEY` set (the key the app
 * lends, Infantry_Pi credential `commandcode`) this registers provider `commandcode` with the models the service itself lists at
 * `<base>/models`: no model list is kept here. Models that only speak `/messages` are left out (they need another protocol).
 * No key, or the catalog cannot be read: nothing is registered and pi runs as before (the reason goes to stderr).
 * No imports on purpose: the file loads from any directory.
 */
const BASE_URL = "https://api.commandcode.ai/provider/v1";
const KEY_ENV = "CMD_API_KEY";
// pi's own defaults for a models.json entry that gives no limits (seen as 128K / 16.4K in `pi --list-models`).
const DEFAULT_CONTEXT = 128000;
const DEFAULT_MAX_TOKENS = 16384;

type CatalogModel = { id: string; name?: string; context_length?: number; max_output_tokens?: number; supported_endpoints?: string[] };

export default async function (pi: any) {
	const key = process.env[KEY_ENV];
	if (!key || !key.trim()) return;
	let listed: CatalogModel[] = [];
	try {
		const res = await fetch(`${BASE_URL}/models`, { headers: { Authorization: `Bearer ${key.trim()}` }, signal: AbortSignal.timeout(15000) });
		if (!res.ok) throw new Error(`HTTP ${res.status}`);
		const body: any = await res.json();
		listed = Array.isArray(body?.data) ? body.data : Array.isArray(body) ? body : [];
	} catch (e: any) {
		console.error(`[infantry] Command Code: could not read the model list from ${BASE_URL}/models (${e?.message ?? e}); commandcode/* models are unavailable`);
		return;
	}
	const models = listed
		.filter((m) => m && typeof m.id === "string" && (!m.supported_endpoints || m.supported_endpoints.includes("/chat/completions")))
		.map((m) => ({
			id: m.id,
			name: m.name || m.id,
			reasoning: false,
			input: ["text"],
			cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
			contextWindow: m.context_length || DEFAULT_CONTEXT,
			maxTokens: m.max_output_tokens || DEFAULT_MAX_TOKENS,
		}));
	if (models.length === 0) {
		console.error("[infantry] Command Code: the model list holds no chat-completions model; commandcode/* models are unavailable");
		return;
	}
	pi.registerProvider("commandcode", { name: "Command Code", baseUrl: BASE_URL, apiKey: `$${KEY_ENV}`, api: "openai-completions", models });
}
