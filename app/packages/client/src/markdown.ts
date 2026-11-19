// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
// The chat's markdown, as blocks and inline spans (ChatMarkdown.kt's subset): headings h1-h3, fenced code with a
// language, bullet and numbered lists, block quotes, tables, paragraphs; inline code, bold, italic and links.
// Pure: the shell draws the result. Raw HTML is never produced, so agent text cannot inject markup.

export type Span =
  | { kind: "text"; text: string }
  | { kind: "code"; text: string }
  | { kind: "bold"; spans: Span[] }
  | { kind: "italic"; spans: Span[] }
  | { kind: "link"; href: string; spans: Span[] };

export type Block =
  | { kind: "heading"; level: 1 | 2 | 3; spans: Span[] }
  /** [lang] is the whole info string (`ts`, `card:news id=a1`); [open] marks a fence still unclosed (streaming). */
  | { kind: "code"; lang: string; text: string; open?: true }
  | { kind: "list"; ordered: boolean; start: number; items: Span[][] }
  | { kind: "quote"; blocks: Block[] }
  | { kind: "table"; align: ("left" | "center" | "right")[]; head: Span[][]; rows: Span[][][] }
  | { kind: "paragraph"; spans: Span[] };

const SAFE_LINK = /^(https?:|mailto:)/i;

/** Inline spans of one run of text. Unclosed markers stay literal. */
export function parseInline(text: string): Span[] {
  const out: Span[] = [];
  let buf = "";
  const flush = () => {
    if (buf) out.push({ kind: "text", text: buf });
    buf = "";
  };
  let i = 0;
  while (i < text.length) {
    const c = text[i];
    if (c === "\\" && i + 1 < text.length && /[\\`*_[\]()]/.test(text[i + 1])) {
      buf += text[i + 1];
      i += 2;
      continue;
    }
    if (c === "`") {
      const end = text.indexOf("`", i + 1);
      if (end > i) {
        flush();
        out.push({ kind: "code", text: text.slice(i + 1, end) });
        i = end + 1;
        continue;
      }
    }
    if ((c === "*" || c === "_") && text[i + 1] === c) {
      const end = text.indexOf(c + c, i + 2);
      if (end > i + 2) {
        flush();
        out.push({ kind: "bold", spans: parseInline(text.slice(i + 2, end)) });
        i = end + 2;
        continue;
      }
    }
    if ((c === "*" || c === "_") && text[i + 1] !== " " && (c === "*" || i === 0 || /\W/.test(text[i - 1]))) {
      const end = text.indexOf(c, i + 1);
      if (end > i + 1 && text[end - 1] !== " ") {
        flush();
        out.push({ kind: "italic", spans: parseInline(text.slice(i + 1, end)) });
        i = end + 1;
        continue;
      }
    }
    if (c === "[") {
      const close = text.indexOf("](", i + 1);
      const end = close > i ? text.indexOf(")", close + 2) : -1;
      if (close > i && end > close) {
        const href = text.slice(close + 2, end).trim();
        if (SAFE_LINK.test(href)) {
          flush();
          out.push({ kind: "link", href, spans: parseInline(text.slice(i + 1, close)) });
          i = end + 1;
          continue;
        }
      }
    }
    buf += c;
    i += 1;
  }
  flush();
  return out;
}

const FENCE = /^\s*(```|~~~)\s*([^`~]*?)\s*$/;
const HEADING = /^(#{1,6})\s+(.*?)\s*#*\s*$/;
const BULLET = /^\s*[-*+]\s+(.*)$/;
const NUMBERED = /^\s*(\d{1,9})[.)]\s+(.*)$/;
const QUOTE = /^\s*>\s?(.*)$/;
const TABLE_RULE = /^\s*\|?\s*:?-+:?\s*(\|\s*:?-+:?\s*)*\|?\s*$/;

const cells = (line: string) =>
  line
    .trim()
    .replace(/^\|/, "")
    .replace(/\|$/, "")
    .split("|")
    .map((c) => c.trim());

/** The blocks of one message. */
export function parseMarkdown(text: string): Block[] {
  const lines = text.replace(/\r\n?/g, "\n").split("\n");
  const blocks: Block[] = [];
  let para: string[] = [];
  const endPara = () => {
    if (para.length) blocks.push({ kind: "paragraph", spans: parseInline(para.join("\n")) });
    para = [];
  };
  let i = 0;
  while (i < lines.length) {
    const line = lines[i];
    const fence = FENCE.exec(line);
    if (fence) {
      endPara();
      const body: string[] = [];
      i += 1;
      while (i < lines.length && !lines[i].trim().startsWith(fence[1])) body.push(lines[i++]);
      const code: Block = { kind: "code", lang: fence[2], text: body.join("\n") };
      if (i >= lines.length) code.open = true;
      blocks.push(code);
      i += 1;
      continue;
    }
    if (!line.trim()) {
      endPara();
      i += 1;
      continue;
    }
    const h = HEADING.exec(line);
    if (h) {
      endPara();
      blocks.push({ kind: "heading", level: Math.min(h[1].length, 3) as 1 | 2 | 3, spans: parseInline(h[2]) });
      i += 1;
      continue;
    }
    if (line.includes("|") && i + 1 < lines.length && TABLE_RULE.test(lines[i + 1])) {
      endPara();
      const head = cells(line);
      const align = cells(lines[i + 1]).map((c) => (c.startsWith(":") && c.endsWith(":") ? "center" : c.endsWith(":") ? "right" : "left"));
      const rows: Span[][][] = [];
      i += 2;
      while (i < lines.length && lines[i].includes("|") && lines[i].trim()) rows.push(cells(lines[i++]).map(parseInline));
      blocks.push({ kind: "table", align, head: head.map(parseInline), rows });
      continue;
    }
    if (QUOTE.test(line)) {
      endPara();
      const body: string[] = [];
      while (i < lines.length && QUOTE.test(lines[i])) body.push(QUOTE.exec(lines[i++])![1]);
      blocks.push({ kind: "quote", blocks: parseMarkdown(body.join("\n")) });
      continue;
    }
    const bullet = BULLET.exec(line);
    const num = NUMBERED.exec(line);
    if (bullet || num) {
      endPara();
      const ordered = !bullet;
      const items: string[] = [];
      while (i < lines.length) {
        const m = ordered ? NUMBERED.exec(lines[i]) : BULLET.exec(lines[i]);
        if (m) items.push(ordered ? m[2] : m[1]);
        else if (lines[i].trim() && /^\s{2,}/.test(lines[i]) && items.length) items[items.length - 1] += `\n${lines[i].trim()}`;
        else break;
        i += 1;
      }
      blocks.push({ kind: "list", ordered, start: num ? Number(num[1]) : 1, items: items.map(parseInline) });
      continue;
    }
    para.push(line);
    i += 1;
  }
  endPara();
  return blocks;
}
