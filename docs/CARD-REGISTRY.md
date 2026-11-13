# CARD-REGISTRY: plugin chat cards (design record, 2026-11-12)

Ticket ARCH CARD-REGISTRY-1 (69379be6). Design only; no production code. The code wins where this page and it disagree.

Goal (owner): replies stay plain markdown by default. An agent may pick a richer card (news headlines first; later a
stock ticker or a graph that appends) only when it needs one, without paying tokens for that choice on every turn.
A plugin, not core, brings each card.

## 1. Current state (dev bc3760c1)

Paths are short: `ui/` = `superiorSoftLibrary/infantryUiCore/src/commonMain/kotlin/com/superiorsoftllc/infantry/ui/`.

| What | Where | Note |
|---|---|---|
| Block model | `ui/model/ChatMarkdown.kt:26-50` | `ChatBlock` is sealed; `Custom(type, text, data: Map<String,String>)` at `:45-46` is the open slot. |
| Images | `ui/model/ChatMarkdown.kt:121-131` | `![alt](url)` is never fetched, shows alt text. Keep. |
| Fences | `ui/model/ChatMarkdown.kt:85-99, 289-290` | ```` ```lang ```` gives `Code(lang, body)`; an open fence while streaming shows as code. The built-in parser never makes a `Custom`. |
| Engine seam | `ui/model/ChatMarkdownEngine.kt:13-24, 52-58, 64-85` | `ChatMarkdownEngine.parse(text, streaming)`, crash-safe wrapper, `ChatMarkdown.engines` registry. JVM jar loading: `ui/../markdown/MarkdownEnginePluginLoader.kt` (jvmMain only). |
| Block type key | `ui/model/ChatMarkdownEngine.kt:36-45` | `chatBlockType`; a `Custom` uses its own `type`. |
| Renderers | `ui/ChatBlockRenderers.kt:47-56` | `ChatBlockRenderers.override(type, renderer)`; unknown `Custom` = plain text (`:99`). |
| Callout | `ui/ChatBlockRenderers.kt:108-119`, `ui/App.kt:274` | The only override, wired by the app, not a plugin. |
| Provided to chat | `ui/App.kt:454` (`LocalChatMarkdown`) | Read by `plugins/official/Infantry_ChatFeed/src/FeedRenderers.kt:81-84`, drawn by `ChatFeedSurface.kt:645`. |
| Board comments | `plugins/official/Infantry_Board/src/TicketCommentsPanel.kt:90,109` | Built-in engine + `DefaultChatBlockRenderer` directly: ignores overrides. |
| Generic plugin registry | `ui/plugin/TabFactory.kt:366` (`registrar.implementation`), `ui/plugin/config/ResolvedConfig.kt:120` (`Implementations`) | Declared in module.json `implementations`; already used for `chat.feed.renderers` and `agent.avatar.renderers` (`ui/AgentAvatarRenderers.kt:74`, the pattern to copy). |
| Daemon images to client | `ui/DaemonImages.kt:28-33`, `ui/TicketImages.kt:251` (`rememberImage`) | Client loads bytes from the daemon by upload id, over its own authenticated socket. |
| Chat note / chat image | `plugins/official/Infantry_Scripting/daemon/.../ChatNoteTools.kt:42-46`, `plugins/official/Infantry_Chat/daemon/.../ChatImageTool.kt` | Automation rows; out of scope, unchanged. |
| HTTP for agents | `plugins/official/Infantry_Scripting/daemon/.../FetchBridge.kt:21-53` | `http.fetch`, off by default (`scripting.fetch.enabled`), host allow-list `scripting.fetch.allow`. |
| Downloads seam | `superiorSoftLibrary/infantryDaemonApi/.../downloads/Downloads.kt` | Daemon-side capped fetch to a file. No general daemon HTTP client yet (HTTP-SEAM-1). |
| Roles | `superiorSoftLibrary/infantryDaemonApi/.../roles/RoleModel.kt:33-39` | `Roles` is read-only; roles are saved by `role_save` (Infantry_Roles). **Gap: a plugin cannot declare a role in module.json.** |
| First turn | `superiorSoftLibrary/infantryBoard/.../agents/FirstTurnPreamble.kt:36` | persona, traits, knowledge, skills on the first turn only. |

### Must not change (regression list)
1. Plain markdown rendering of every built-in block, the built-in engine as default and fallback, the engine picker.
2. Images never fetched from reply markdown (`![]()` stays alt text). Unsafe links stay text.
3. The `callout` override and the example markdown plugin (`samples/markdownEnginePlugin`).
4. A crashing engine or renderer never takes the chat down.
5. Board comments render as today (a card there degrades to a code block until CARD-4).
6. chat_note / chat_image automation rows, chat attachments, `rememberImage`.
7. Older clients: a card reply must still read sensibly (it shows as a fenced code block today; that is the fallback).

## 2. Target design

### 2.1 Wire format: a fenced block (decided)

````
```card:news
{"items":[{"title":"...","source":"Reuters","time":"2026-11-12T09:30:00Z","url":"https://...","image":"https://..."}]}
```
````

Info string: `card:<type>` with optional `key=value` attributes: `id=<cardId>` and `op=set|append|replace`
(section 2.4). Body: one JSON object, max size a named setting (`limits.chat.cardBodyBytes`, default 32 KB).

Why a fence, not a tool call that emits a card frame:

| | Fence in the reply | Tool call + card frame |
|---|---|---|
| Harness-neutral (claude, opencode, pi, any CLI) | yes, it is text | needs the MCP tool in every harness |
| Runner sessions | the same text renders the same; no runner code touched | a new frame must cross RunnerLink (RUNNER-UNIFY conflict) |
| History, reload, copy/paste, board comments | stored in the transcript as is | separate store, ordering with text |
| Older clients | readable code block | nothing, or an unknown frame |
| Streaming | half block degrades quietly (below) | atomic |
| Token cost | none beyond the JSON itself | tool schema in context every turn |

The fence wins on every axis except atomicity, which streaming handling covers.

Parsing: a pure post-parse step `cardBlocks(blocks, streaming)` in `ui/model/` runs after ANY engine (inside
`parseChatMarkdownSafely`), so plugin engines get cards for free. A `Code` block whose language starts with `card:`
becomes `ChatBlock.Custom(type = "card:<type>", text = <raw fence>, data = {card.id, card.op, card.body, card.state})`.
`card.state` is `complete`, `streaming` (fence still open) or `invalid` (body not JSON / too big).

We reuse `Custom` instead of adding a sealed subtype: a new subtype breaks every third-party exhaustive `when` over
`ChatBlock` (an SDK break). `Custom.data` is `Map<String,String>`, so the body travels as a JSON string; the card
renderer parses it.

Degrade rules: `streaming` draws a one-line placeholder ("news card…", muted), never half JSON. `invalid` or no
renderer for the type: the original fenced code block (exactly what old clients show). A renderer that throws: the
same code block (guard as `parseChatMarkdownSafely` does).

### 2.2 Card registry

One registry, the existing `Implementations`: registry id `chat.cards`, key = card type (`news`), value = a
`ChatCardRenderer`:

```kotlin
// ui/ChatCards.kt (infantryUiCore commonMain, published in the UI SDK)
data class ChatCard(val type: String, val id: String?, val body: String, val state: CardState, val updates: List<String> = emptyList())
fun interface ChatCardRenderer { @Composable fun Render(card: ChatCard, modifier: Modifier) }
```

Plugin side, same as avatars:

```kotlin
registrar.implementation("chat.cards", "news", NewsCardRenderer)
```

```json
"implementations": [{ "registry": "chat.cards", "key": "news" }],
"cards": [{ "type": "news", "summary": "Headline list: title, source, time, link, thumbnail", "spec": "cards/news.md",
            "images": { "hosts": ["*.googleusercontent.com", "*.gstatic.com"] } }]
```

`implementations` is the client binding; `cards` is the descriptor the daemon reads (agent docs, image allow-list).
`ChatBlockRenderers.rendererFor` checks `chat.cards` (via the window's `Implementations`) for `card:*` types before its
own overrides, then the default. Lookup is at draw time, so `plugins.apply` install/uninstall swaps live, as for
avatars. Per platform: one commonMain renderer serves desktop, web and Android (all compile plugins in, D6/D9); a
platform without the plugin falls back per 2.1. The `callout` override in `App.kt:274` stays as is.

Core gets only the mechanism (fence mapping, registry id, fallback). Every concrete card is a plugin.

### 2.3 Agent discovery at zero per-turn cost

Options weighed (cost per turn of a session that never uses cards):

| Option | Cost | Verdict |
|---|---|---|
| New MCP tool `cards_list` / `cards_describe` | its schema (~100-150 tokens) rides EVERY turn of EVERY session | no |
| Card list in every system prompt | ~50 tokens per card, every turn, every role | no |
| Card spec in the persona of roles that use it | first turn only, only that role; 0 for others | **yes, primary** |
| CLI `infantry card list` / `infantry card show <type>` | 0 until called; ~200 tokens once when called | **yes, on demand** |
| Existing `infantry_capability_call` (already in the tool list) | 0 extra | used for data fetch, not discovery |

Decision: a role that is about cards (News Agent) carries the card spec in its persona (first turn only, prompt-cached
after). Any other agent that wants a card runs `infantry card show <type>` once, when a situation calls for it. The
`AGENT-QUICKREF` gets one line ("rich cards: `infantry card list`"), which is read on demand, not injected. Default
replies stay plain markdown; nothing tells an agent to consider cards per turn.

Shipped (CARD-3): module.json `cards` (`CardDeclaration`), read through `PluginCatalogReader.loaded()` (DaemonApi 1.57.0) by the official daemon-only plugin `Infantry_Cards`, whose capabilities `cards.list` / `cards.show` answer `infantry card list|show` (one `infantry_capability_call` each; no new tool schema). A `spec` is a file in the module folder or the text itself.

### 2.4 Addressable cards that update (design now, ship later)

- `id=<cardId>` names a card within ONE session's transcript (no cross-session addressing).
- `op=set` (default) draws a new card. A later fence with the same `id`:
  - `op=replace`: body replaces the state;
  - `op=append`: body's `items` array is appended (keys other than `items` are merged, last wins).
- Folding is a pure function over the session's parsed lines (`foldCards(lines)`), so reload and remote sessions
  give the same picture with no new store or frame. The card draws at its FIRST occurrence with the folded state;
  later update fences draw as a thin muted line "updated <type> card ↑" (or nothing, a setting). A card renderer
  sees the merged body plus `updates` (raw bodies, for a graph that animates).
- Bounds: max updates folded per card and max items kept are named limits (`limits.chat.cardUpdates`,
  `limits.chat.cardItems`); beyond them the oldest items drop.
- v1 ships static cards only; the parser already reads `id`/`op` so transcripts written in v1 stay valid.

### 2.5 Images and thumbnails

- The client never fetches a URL from a reply. A card renderer asks for an image only through
  `rememberCardImage(type, url)`, which goes to the daemon (new `card_image` request beside the existing
  `DaemonImageSource`, `ui/DaemonImages.kt:28`).
- The daemon half of a new official plugin `Infantry_Cards` answers it: refuses unless `cards.images.enabled` (default
  true) and the URL host is in the `images.hosts` that card type declared (plus an owner setting
  `cards.images.allow` to add hosts); https only; no cookies, no referrer, fixed user agent; byte cap
  (`limits.cards.imageBytes`, default 2 MB), type sniffed (png/jpg/gif/webp), cache in the state dir keyed by URL
  hash with a size cap and TTL (named limits). It fetches through the `Downloads` seam (no ad-hoc HTTP,
  `DownloadProgressGuardTest`).
- A refused or failed image draws the card's own placeholder (source initial badge), never an error.
- Markdown `![]()` stays unfetched. Privacy: only hosts a card type declared can be hit, and only from the daemon.

### 2.6 News Agent plugin (first consumer)

`plugins/official/Infantry_News` (showcase, D3):
- UI half: `NewsCardRenderer` for `card:news`: bordered card per headline (reuse the card + border style, owner rule
  "cards + borders"), thumbnail left (2.5), title (link opens via the safe-link path), source and relative time;
  several items = a vertical list; `max` shown is a config field.
- Daemon half: capability `news.headlines {query?, topic?, max}` that reads Google News RSS
  (`news.google.com/rss`, no key) and returns items already in card JSON. Agents call it through the EXISTING
  `infantry_capability_call` / `infantry capability call`, so no new tool schema and no `http.fetch` opt-in, and it
  works for claude, opencode and pi alike. No saved sources (owner).
- Thumbnails: the RSS often has none; v1 uses `media:content` when present, else the source badge. Fetching each
  article's `og:image` is a later setting (cost: one request per item).
- Role `news-agent`: persona = "fetch with news.headlines, answer with one ```card:news block plus one plain
  sentence". Needs ROLE-PLUGIN-1.

### 2.7 Decisions expensive to reverse
1. The fence syntax `card:<type> id= op=`: it lives in every stored transcript forever. Chosen to degrade to a code block.
2. `ChatCardRenderer` / `ChatCard` in the UI SDK: third-party renderers compile against it; add fields only with defaults.
3. Fold semantics (first occurrence draws, last state wins): history renders by them.
4. module.json `cards` field name and shape (the daemon and catalog read it).

## 3. Tickets (ordered, smallest safe steps first; do not create from this page without the PM)

Each ends with an in-app screenshot in a real Windows window (owner rule) showing the stated proof.

| # | Title | Scope | Files | Risk | Weight |
|---|---|---|---|---|---|
| 1 | CARD-1 fence to card block | pure `cardBlocks` after any engine; `card:*` fence becomes `Custom`; states complete/streaming/invalid; default renderer draws placeholder or the original code block. Unit tests incl. streaming and corpus. | `ui/model/ChatMarkdown*.kt`, `ui/ChatBlockRenderers.kt`, commonTest | low | light |
| 2 | CARD-2 `chat.cards` registry | `ChatCard`, `ChatCardRenderer`, lookup through `Implementations` at draw time; renderer crash guard; docs `plugin-api.md` (SdkTypesDocumented); a sample `card:demo` renderer in `plugins/examples`. Proof: demo card in chat, plugin off = code block, live swap. | `ui/ChatCards.kt`, `ui/ChatBlockRenderers.kt`, `ui/App.kt` (provide Implementations), `docs/wiki/plugin-api.md`, `plugins/examples/` | low-med | medium |
| 3 | CARD-3 card descriptors + `infantry card list/show` | module.json `cards` (type, summary, spec, images.hosts) in the manifest model; daemon frame listing descriptors of loaded plugins; CLI; one QUICKREF line. | `infantryPlugins/.../ModuleManifest.kt`, `Infantry_Cards` daemon half (new), CLI command, `AGENT-QUICKREF.md` | med | medium |
| 4 | CARD-4 board comments use the chat renderers | `TicketCommentsPanel` reads `LocalChatMarkdown` instead of the built-in renderer directly. | `plugins/official/Infantry_Board/src/TicketCommentsPanel.kt` | low | light |
| 5 | CARD-IMG-1 safe card image proxy | `card_image` request, allow-list from descriptors + setting, Downloads seam, sniff, cache, limits; `rememberCardImage`. Security review in the done comment. | `Infantry_Cards` daemon half, `ui/DaemonImages.kt`, protocol DTO | med-high | heavy |
| 6 | ROLE-PLUGIN-1 plugin-declared roles | module.json `roles` (id, name, persona, colour); Infantry_Roles seeds on install if absent, never overwrites an owner edit, marks plugin-owned; uninstall leaves the role disabled. | `ModuleManifest.kt`, `plugins/official/Infantry_Roles/daemon/` | med | medium |
| 7 | NEWS-1 Infantry_News plugin | `news.headlines` capability (RSS parse, limits), `card:news` renderer, `news-agent` role, spec `cards/news.md`. Proof: ask the News Agent for headlines, cards with source/time/thumbnail-or-badge, link opens. | `plugins/official/Infantry_News/` (new) | med | heavy |
| 8 | CARD-UPDATE-1 addressable cards | `foldCards` over a session's lines, `op=replace/append`, limits, "updated ↑" line; sample ticker card. | `ui/model/`, `ui/ChatCards.kt`, ChatFeed line renderer | med | medium |

Order rationale: 1-2 are pure client mechanism with today's behaviour as the fallback; 3-6 are independent seams
(any order after 2); 7 needs 2, 3, 5 (thumbnails optional) and 6; 8 is for the next card kinds. None touches runner
code (RunnerLink, DaemonMain) or RUNNER-UNIFY 78f22aa2.
