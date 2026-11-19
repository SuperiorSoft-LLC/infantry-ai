// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// A small, dependency-free line highlighter for diffs: comments, strings, numbers, keywords, by file extension.
// Line by line on purpose (a diff is a window into a file), so a block comment opened above a hunk is not seen.

export type TokenKind = "plain" | "comment" | "string" | "number" | "keyword" | "type";
export interface Token {
  kind: TokenKind;
  text: string;
}

interface Lang {
  line: string[];
  block?: [string, string];
  quotes: string;
  keywords: Set<string>;
}

const words = (s: string) => new Set(s.split(/\s+/).filter(Boolean));

const C_LIKE = "if else for while do return break continue switch case default new try catch finally throw class interface enum extends implements import export from as public private protected static final const let var function async await yield this super null true false typeof instanceof void in of";

const LANGS: Record<string, Lang> = {
  ts: { line: ["//"], block: ["/*", "*/"], quotes: "\"'`", keywords: words(C_LIKE + " type readonly declare namespace keyof infer satisfies undefined") },
  rs: { line: ["//"], block: ["/*", "*/"], quotes: "\"", keywords: words("fn let mut pub crate mod use struct enum impl trait for in while loop if else match return break continue where as ref move dyn self Self super const static unsafe async await type true false Some None Ok Err") },
  kt: { line: ["//"], block: ["/*", "*/"], quotes: "\"'", keywords: words(C_LIKE + " fun val object data sealed override open internal companion when is by lateinit inline suspend") },
  py: { line: ["#"], quotes: "\"'", keywords: words("def class return if elif else for while in not and or is import from as with try except finally raise lambda yield pass break continue None True False self async await") },
  sh: { line: ["#"], quotes: "\"'", keywords: words("if then else elif fi for in do done while case esac function return export local echo") },
  lua: { line: ["--"], quotes: "\"'", keywords: words("local function end if then else elseif for in do while repeat until return break nil true false and or not") },
  json: { line: [], quotes: "\"", keywords: words("true false null") },
  toml: { line: ["#"], quotes: "\"'", keywords: words("true false") },
  css: { line: [], block: ["/*", "*/"], quotes: "\"'", keywords: words("important") },
  sql: { line: ["--"], quotes: "'", keywords: words("SELECT FROM WHERE INSERT INTO VALUES UPDATE SET DELETE CREATE TABLE INDEX IF NOT EXISTS PRIMARY KEY NULL DEFAULT AND OR ORDER BY ON") },
  md: { line: [], quotes: "`", keywords: new Set() },
};

const EXT: Record<string, string> = {
  ts: "ts", tsx: "ts", js: "ts", jsx: "ts", mjs: "ts", cjs: "ts", java: "kt", kt: "kt", kts: "kt", go: "ts", c: "ts", h: "ts", cpp: "ts", cs: "ts", swift: "kt",
  rs: "rs", py: "py", sh: "sh", bash: "sh", ps1: "sh", lua: "lua", json: "json", toml: "toml", yml: "toml", yaml: "toml", css: "css", scss: "css", sql: "sql", sq: "sql", md: "md",
};

export function langOf(path: string): string | null {
  const ext = path.split(".").pop()?.toLowerCase() ?? "";
  return EXT[ext] ?? null;
}

export function tokenize(line: string, lang: string | null): Token[] {
  const l = lang ? LANGS[lang] : undefined;
  if (!l) return [{ kind: "plain", text: line }];
  const out: Token[] = [];
  const push = (kind: TokenKind, text: string) => {
    const last = out[out.length - 1];
    if (last && last.kind === kind) last.text += text;
    else out.push({ kind, text });
  };
  let i = 0;
  while (i < line.length) {
    const rest = line.slice(i);
    const lc = l.line.find((m) => rest.startsWith(m));
    if (lc) {
      push("comment", rest);
      break;
    }
    if (l.block && rest.startsWith(l.block[0])) {
      const end = rest.indexOf(l.block[1], l.block[0].length);
      const n = end < 0 ? rest.length : end + l.block[1].length;
      push("comment", rest.slice(0, n));
      i += n;
      continue;
    }
    const ch = line[i];
    if (l.quotes.includes(ch)) {
      let j = i + 1;
      while (j < line.length && line[j] !== ch) j += line[j] === "\\" ? 2 : 1;
      push("string", line.slice(i, j + 1));
      i = j + 1;
      continue;
    }
    const num = /^(0x[0-9a-fA-F_]+|\d[\d_]*(\.\d+)?)/.exec(rest);
    if (num && !/[A-Za-z_]/.test(line[i - 1] ?? "")) {
      push("number", num[0]);
      i += num[0].length;
      continue;
    }
    const word = /^[A-Za-z_$][\w$]*/.exec(rest);
    if (word) {
      const w = word[0];
      push(l.keywords.has(w) ? "keyword" : /^[A-Z][a-z]/.test(w) ? "type" : "plain", w);
      i += w.length;
      continue;
    }
    push("plain", ch);
    i++;
  }
  return out;
}

export const tokenClass: Record<TokenKind, string> = {
  plain: "",
  comment: "text-muted-foreground italic",
  string: "text-ok",
  number: "text-warn",
  keyword: "text-info font-medium",
  type: "text-chart-4",
};
