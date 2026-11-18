// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// All static mock data for the GUI template lives here, in one place.
import type {
  Agent,
  AttentionItem,
  BoardColumn,
  NamedRow,
  PacingProvider,
  Plugin,
  Project,
  ProviderUsage,
  Role,
  TranscriptItem,
} from "./types";

export const projects: Project[] = [
  { id: "p-infantry", name: "Infantry AI Project" },
  { id: "p-sites", name: "Infantry Sites" },
  { id: "p-calendar", name: "Calendar Plugin" },
];

export const roles: Role[] = [
  { id: "architect", label: "architect", icon: "architect" },
  { id: "assistant", label: "assistant", icon: "assistant" },
  { id: "backend", label: "backend", icon: "backend" },
  { id: "frontend", label: "frontend", icon: "frontend" },
  { id: "fullstack", label: "fullstack", icon: "fullstack" },
  { id: "pm", label: "pm", icon: "pm" },
  { id: "qa", label: "qa", icon: "qa" },
  { id: "automation", label: "automation", icon: "automation" },
];

export const agents: Agent[] = [
  {
    id: "a-architect",
    name: "Architect",
    roleId: "architect",
    sessionId: "1ea41f31",
    status: "idle",
    statusLine: "idle",
    ticketId: "f4afe954",
    ticketTitle: "PLAN RUST-PORT-1: Rust daemon + Tauri UI",
    age: "1m",
    lastLine: "finished: succeeded",
    group: "running",
    provider: "claude",
    model: "opus",
    runs: 41,
    tokensIn: "88 in",
    tokensOut: "12.1k out",
    hue: 262,
  },
  {
    id: "a-backend-1",
    name: "Backend",
    roleId: "backend",
    sessionId: "bbf7a3de",
    status: "working",
    statusLine: "working (60 tool calls, 1 compaction)",
    ticketId: "c4efec9e",
    ticketTitle: "ASK-OTHER-1: harness answers from other agents",
    progress: null,
    age: "4m",
    lastLine: "C:\\Users\\antho\\agenthq-standalone",
    group: "running",
    provider: "claude",
    model: "sonnet",
    runs: 312,
    tokensIn: "1.2k in",
    tokensOut: "48.9k out",
    hue: 172,
  },
  {
    id: "a-backend-2",
    name: "Backend",
    roleId: "backend",
    sessionId: "334b47c2",
    status: "working",
    statusLine: "working (26 tool calls)",
    ticketId: "ff5657b8",
    ticketTitle: "UI SLIDE-TAB-1: session tabs slide",
    progress: 62,
    age: "2m",
    lastLine: "Two tests left",
    group: "running",
    provider: "claude",
    model: "sonnet",
    runs: 88,
    tokensIn: "640 in",
    tokensOut: "21.4k out",
    hue: 190,
  },
  {
    id: "a-pm",
    name: "PM",
    roleId: "pm",
    sessionId: "fdfa7993",
    status: "working",
    statusLine: "[infantry] * session restarted (claude · opus)",
    age: "22s",
    lastLine: "[infantry] * session restarted",
    group: "running",
    provider: "claude",
    model: "opus",
    runs: 2497,
    tokensIn: "209 in",
    tokensOut: "33.7k out",
    hue: 220,
  },
  {
    id: "a-fullstack",
    name: "Full-Stack",
    roleId: "fullstack",
    sessionId: "af81369c",
    status: "done",
    statusLine: "finished",
    age: "13m",
    lastLine: "finished: succeeded",
    group: "quiet",
    provider: "opencode",
    model: "deepseek-v4",
    runs: 57,
    tokensIn: "3.1k in",
    tokensOut: "90.2k out",
    hue: 290,
  },
  {
    id: "a-backend-3",
    name: "Backend",
    roleId: "backend",
    sessionId: "5c0a91e2",
    status: "needs-input",
    statusLine: "needs input: which branch?",
    ticketId: "9a1d02bc",
    ticketTitle: "BUG CAL-FIX-1: Calendar timer fires twice",
    age: "31m",
    lastLine: "waiting for owner",
    group: "quiet",
    provider: "claude",
    model: "sonnet",
    runs: 19,
    tokensIn: "410 in",
    tokensOut: "7.7k out",
    hue: 150,
  },
  {
    id: "a-qa",
    name: "QA",
    roleId: "qa",
    sessionId: "77e2b0d4",
    status: "review",
    statusLine: "to review: 3 tests added",
    ticketId: "3be8a7f0",
    ticketTitle: "QA RUNNER-PING-1: ping/pong suite",
    age: "1h",
    lastLine: "report sent",
    group: "quiet",
    provider: "pi",
    model: "gpt-5",
    runs: 12,
    tokensIn: "220 in",
    tokensOut: "4.0k out",
    hue: 30,
  },
];

export const selectedAgentId = "a-pm";

/** Avatars in the title-bar strip (ids into `agents`). */
export const avatarStrip = ["a-architect", "a-backend-1", "a-backend-2", "a-fullstack", "a-backend-3", "a-pm", "a-qa"];

export const counters = { needsInput: 0, toReview: 0, working: 4, boardBadge: 3, boardShortcut: "^8" };

export const sessionTabs = [
  { id: "cli", label: "CLI", icon: "terminal", hint: "^1" },
  { id: "mail", label: "Mail", icon: "mail" },
  { id: "pods", label: "Pods", icon: "box", hint: "^2" },
  { id: "roles", label: "Roles", icon: "user", hint: "^5" },
  { id: "profiles", label: "Profiles", icon: "id-card", hint: "^9" },
  { id: "triggers", label: "Triggers", icon: "zap", hint: "^0" },
  { id: "calendar", label: "Calendar", icon: "calendar" },
  { id: "epics", label: "Epics", icon: "layers" },
  { id: "notes", label: "Notes", icon: "file-text" },
  { id: "run", label: "Run", icon: "play" },
  { id: "git", label: "Git", icon: "git-branch" },
  { id: "plugins", label: "Plugins", icon: "puzzle" },
  { id: "stats", label: "Stats", icon: "bar-chart" },
  { id: "pace", label: "Pace", icon: "trending-up" },
  { id: "services", label: "Services", icon: "server" },
] as const;

export type SessionTabId = (typeof sessionTabs)[number]["id"];

export const automationLine = "AUTOMATION · turns saved 5 · wakes 30 (owner 30) · project: 5 saved, 69 wakes";

export const transcript: TranscriptItem[] = [
  {
    kind: "thinking",
    id: "t1",
    count: 3,
    text: "The owner wants the Tauri race result recorded in memory before the plan starts. Append the decision, keep the Why line, then pass the plan ticket to the architect.",
  },
  {
    kind: "edit",
    id: "e1",
    path: "C:\\Users\\antho\\.claude\\projects\\C--Users-antho-agenthq-standalone\\memory\\rust-direction-memory-concern.md",
    added: 2,
    removed: 0,
    lines: [
      {
        newNo: 1,
        kind: "add",
        text: "**DECIDED, same night:** a race between Tauri v2 + React and Rust + Flutter. Tauri won: a 4.2 MB exe at C:\\Users\\antho\\tauri-react-hello. Flutter was killed.",
      },
      { newNo: 2, kind: "add", text: "" },
      { oldNo: 1, newNo: 3, kind: "ctx", text: "**Why:**" },
    ],
  },
  {
    kind: "comms",
    id: "c1",
    time: "11:06:49 PM",
    body: {
      paragraphs: [
        "The architect is now working on RUST-PORT-1 (f4afe954, new epic \"Rust Port\", Opus). It writes a plan only, to docs/rust-port/PLAN.md, with no code. It uses the current Kotlin app as the spec and covers:",
      ],
      bullets: [
        { lead: "Target:", text: "a Rust daemon that speaks the same API, so the current UI keeps working while the port happens, plus a Tauri v2 + React UI for desktop, mobile and web." },
        { lead: "Plugins, decided first:", text: "separate processes versus WASM, keeping grants and tiers, hot install with no restart, how plugins add React screens." },
        { lead: "Port order:", text: "module by module, including harnesses/PTY, MCP, board, Lua scripts, vault, runners, prompts and Run tab." },
        { lead: "Data migration:", text: "state, tickets, secrets and settings come over with nothing lost." },
        { lead: "Splitting the work", text: "across many workers running in parallel, with estimates in worker-days." },
        { lead: "Open questions:", text: "anything that needs your call goes in an \"Owner decisions\" section." },
      ],
      outro: "No port tickets get filed until you've approved the plan. The Kotlin app stays on bug fixes in the meantime.",
    },
  },
  { kind: "finished", id: "f1", outcome: "succeeded", exitCode: 0 },
  {
    kind: "comms",
    id: "c2",
    time: "11:13:51 PM",
    owner: true,
    body: { paragraphs: ["It needs to be built on shadcn/ui, react and tauri v2"] },
  },
  { kind: "note", id: "n1", text: "[infantry] * session restarted (claude · opus)" },
  { kind: "spinner", id: "s1", label: "Thinking...", elapsed: "22s" },
];

export const providerUsage: ProviderUsage[] = [
  {
    id: "claude",
    name: "Claude",
    signedIn: true,
    limits: [
      { label: "Current session", percent: 29, resets: "Resets Nov 18 1:00 AM", underPace: "35 pts under pace" },
      { label: "Current week", percent: 66, resets: "Resets Nov 19 2:00 PM", underPace: "11 pts under pace" },
    ],
  },
  { id: "opencode-go", name: "OpenCode Go", signedIn: false, limits: [], note: "not signed in" },
];

export const pacingRule = "Rule: pace · up to 1 ticket workers while projected <= 100% at reset · 1 from 95% used";

export const pacing: PacingProvider[] = [
  {
    id: "claude",
    name: "Claude",
    percent: 66,
    projected: "projected 85.9% at reset",
    detail: "Current week · live · 11 pts under pace · Resets Nov 19 2:00 PM",
    updated: "updated 7m ago",
    workers: 3,
    limit: "no limit",
    freeSlot: true,
    warning: "allowance raised to no limit · ends in 38h 45m (pacing rule says 1)",
    setLabel: "Type %",
  },
  {
    id: "opencode-go",
    name: "OpenCode Go",
    detail: "usage unknown (not signed in)",
    workers: 0,
    limit: "no limit",
    freeSlot: true,
    warning: "usage unknown (no_credentials): pacing off for opencode-go (fail open)",
    setLabel: "Set %",
  },
];

export const attention: AttentionItem[] = [
  { id: "at1", agentId: "a-backend-3", kind: "question", title: "Which branch for CAL-FIX-1?", detail: "calendar-variant or dev", age: "31m" },
  { id: "at2", agentId: "a-qa", kind: "review", title: "RUNNER-PING-1 ready to review", detail: "3 tests added · all green", age: "1h" },
  { id: "at3", agentId: "a-backend-1", kind: "permission", title: "Allow `gradle :agentHq:test`?", detail: "scoped test run", age: "4m" },
  { id: "at4", agentId: "a-fullstack", kind: "error", title: "Build failed on wasm target", detail: "WasmCommonMainGuardTest", age: "13m" },
];

export const prompts: NamedRow[] = [
  { id: "pr1", title: "Bug: reproduce first", subtitle: "Reproduce on dev head, then fix", meta: "used 41×" },
  { id: "pr2", title: "Scoped tests only", subtitle: "Run the tests for your change", meta: "used 33×" },
  { id: "pr3", title: "Report done", subtitle: "agenthq_script_run name=report", meta: "used 120×" },
];

export const skills: NamedRow[] = [
  { id: "sk1", title: "delegate-to-deepseek", subtitle: "Hand low-judgement work to a DeepSeek worker", badge: "project" },
  { id: "sk2", title: "release-notes", subtitle: "Draft notes from merged tickets", badge: "role" },
  { id: "sk3", title: "playwright-shots", subtitle: "Screenshot a page with Playwright", badge: "global" },
];

export const board: BoardColumn[] = [
  {
    id: "backlog",
    title: "Backlog",
    tickets: [
      { id: "1a2b3c4d", title: "Android TV media plugin", kind: "feature", roleId: "frontend", epic: "Plugins" },
      { id: "5e6f7a8b", title: "Git reviewer plugin", kind: "feature", roleId: "backend", epic: "Plugins" },
    ],
  },
  {
    id: "todo",
    title: "To Do",
    tickets: [
      { id: "9a1d02bc", title: "CAL-FIX-1: Calendar timer fires twice", kind: "bug", roleId: "backend", epic: "Calendar" },
      { id: "0c1d2e3f", title: "Hosting Basic plan card", kind: "feature", roleId: "frontend", epic: "Hosting" },
    ],
  },
  {
    id: "progress",
    title: "In Progress",
    tickets: [
      { id: "f4afe954", title: "PLAN RUST-PORT-1", kind: "plan", roleId: "architect", epic: "Rust Port", assignee: "Architect" },
      { id: "c4efec9e", title: "ASK-OTHER-1: harness answers", kind: "feature", roleId: "backend", assignee: "Backend" },
      { id: "ff5657b8", title: "UI SLIDE-TAB-1: session tabs", kind: "feature", roleId: "backend", assignee: "Backend" },
    ],
  },
  {
    id: "review",
    title: "Review",
    tickets: [{ id: "3be8a7f0", title: "RUNNER-PING-1: ping/pong suite", kind: "chore", roleId: "qa", assignee: "QA" }],
  },
  {
    id: "done",
    title: "Done",
    tickets: [
      { id: "dd7900ed", title: "Built-in harness installer", kind: "feature", roleId: "backend", epic: "Harness" },
      { id: "bd882afe", title: "Release 1.0.1", kind: "chore", roleId: "automation" },
    ],
  },
];

export const plugins: Plugin[] = [
  { id: "cal", name: "Calendar", version: "1.0.2", author: "SuperiorSoft", description: "Tasks, reminders and timers for you, a role or a session.", tier: "official", enabled: true, updateAvailable: "1.0.3" },
  { id: "sites", name: "Infantry Sites", version: "0.9.0", author: "SuperiorSoft", description: "Droplet, domain and SSH for hosted sites.", tier: "official", enabled: true },
  { id: "tts", name: "TTS Kokoro", version: "0.3.1", author: "SuperiorSoft", description: "Speak comms messages with a local Kokoro voice.", tier: "official", enabled: false },
  { id: "webtv", name: "WebTV", version: "0.1.4", author: "community", description: "Media and home controls for Android TV.", tier: "community", enabled: false },
  { id: "gitrev", name: "Git Reviewer", version: "0.2.0", author: "community", description: "Review diffs and leave comments per hunk.", tier: "community", enabled: true },
];

export const pods: NamedRow[] = [
  { id: "pod1", title: "Main pod", subtitle: "4 running · 3 quiet", meta: "This computer", tone: "green", badge: "local" },
  { id: "pod2", title: "Site runner", subtitle: "1 running", meta: "vps-runner-01", tone: "blue", badge: "remote" },
  { id: "pod3", title: "Calendar pod", subtitle: "idle", meta: "This computer", tone: "neutral", badge: "local" },
];

export const profiles: NamedRow[] = [
  { id: "pf1", title: "Opus planner", subtitle: "claude · opus · effort high", badge: "default" },
  { id: "pf2", title: "Sonnet worker", subtitle: "claude · sonnet · effort medium" },
  { id: "pf3", title: "Cheap weekend", subtitle: "opencode · deepseek-v4", badge: "weekend" },
];

export const triggers: NamedRow[] = [
  { id: "tr1", title: "mail-incoming → pm", subtitle: "on: mail.incoming · script: pm-mail-handler", tone: "green", badge: "on" },
  { id: "tr2", title: "ticket-done → report", subtitle: "on: ticket.moved(done) · script: report", tone: "green", badge: "on" },
  { id: "tr3", title: "nightly build", subtitle: "cron 0 2 * * * · script: build-nightly", tone: "neutral", badge: "off" },
];

export const epics: NamedRow[] = [
  { id: "ep1", title: "Rust Port", subtitle: "1 of 1 tickets in progress", meta: "45%", tone: "amber" },
  { id: "ep2", title: "Calendar", subtitle: "3 open · 7 done", meta: "70%", tone: "green" },
  { id: "ep3", title: "Hosting", subtitle: "5 open · 2 done", meta: "28%", tone: "blue" },
];

export const notes: NamedRow[] = [
  { id: "nt1", title: "Where project notes live", subtitle: "Project memory/notes means the app Memory", meta: "read first" },
  { id: "nt2", title: "Branch flow and assembly line", subtitle: "ticket -> dev -> staging -> main", meta: "2026-10-26" },
  { id: "nt3", title: "BLOCKS-1 design", subtitle: "JSON block trees for plugin UI", meta: "2026-10-26" },
];

export const runCommands: NamedRow[] = [
  { id: "r1", title: "gradle run", subtitle: "./gradlew :infantry:run", tone: "green", badge: "running" },
  { id: "r2", title: "daemon test", subtitle: "./gradlew :agentHq:test --tests *Runner*", tone: "neutral", badge: "idle" },
  { id: "r3", title: "vite dev", subtitle: "npm run dev -- --port 1420", tone: "neutral", badge: "idle" },
];

export const gitCommits: NamedRow[] = [
  { id: "dd7900ed5", title: "feat(agentHq): built-in harness installer", subtitle: "SuperiorSoft LLC · 2h ago", meta: "+412 −37" },
  { id: "bd882afe4", title: "chore(release): bump version to 1.0.1", subtitle: "SuperiorSoft LLC · 6h ago", meta: "+2 −2" },
  { id: "7660a51c3", title: "fix(release): make gradlew executable", subtitle: "SuperiorSoft LLC · 7h ago", meta: "+0 −0" },
];

export const services: NamedRow[] = [
  { id: "sv1", title: "Runner", subtitle: "Run agents on another machine", meta: "1 paired", tone: "green", badge: "connected" },
  { id: "sv2", title: "Orchestrator", subtitle: "Hosted daemon for your pods", tone: "neutral", badge: "soon" },
  { id: "sv3", title: "Web hosting", subtitle: "Basic plan · $12/mo", tone: "blue", badge: "available" },
];

export const stats = [
  { label: "Tickets done this week", value: "38", delta: "+9" },
  { label: "Agent hours", value: "142", delta: "+21" },
  { label: "Tokens out", value: "4.1M", delta: "+0.6M" },
  { label: "Failed runs", value: "3", delta: "−2" },
];

export const calendar = [
  { day: "Mon 16", items: ["Nightly build 02:00"] },
  { day: "Tue 17", items: ["RUST-PORT-1 plan due", "Owner test window"] },
  { day: "Wed 18", items: ["Claude session reset 01:00"] },
  { day: "Thu 19", items: ["Claude week reset 14:00"] },
  { day: "Fri 20", items: ["Weekend: DeepSeek cheap"] },
  { day: "Sat 21", items: [] },
  { day: "Sun 22", items: [] },
];

export const statusBar = {
  connected: true,
  lastFrame: "last frame now ago",
  ramFree: "RAM 3.3 GB free",
  agentsCpu: "agents CPU 8.5%",
  update: "Update available v1.6.1-nightly.20261118.81",
  hints: ["^K search", "Alt+Up/Down agents", "^J needs input", "^N new", "^R resume"],
  build: "build 1.6.0+20261117.2317.6afaed5e8",
};

export const connection = { host: "This computer", url: "ws://127.0.0.1:8797/ws", attention: 14 };
export const permissionsLine = { mode: "allowAll", agents: 14, state: "working" };
