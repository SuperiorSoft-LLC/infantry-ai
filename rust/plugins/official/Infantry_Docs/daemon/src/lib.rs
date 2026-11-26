// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! Infantry_Docs, daemon half: the in-app docs lookup. The user-facing wiki pages are embedded in the component and
//! offered on demand as two capabilities, `docs.list` and `docs.show` (tools `docs_list`, `docs_show`). Nothing is
//! injected into a prompt: a page costs tokens only when an agent asks for it.
use infantry_plugin_sdk::types::CallContext;
use infantry_plugin_sdk::{export_plugin, Plugin};
use serde_json::{json, Value};

mod bundle {
    include!(concat!(env!("OUT_DIR"), "/pages.rs"));
}

pub struct Docs;

fn title_of(text: &str, name: &str) -> String {
    text.lines().find(|l| l.starts_with("# ")).map(|l| l[2..].trim().to_string()).unwrap_or_else(|| name.to_string())
}

/// A bundled page: (id = path under docs/wiki without `.md`, nav section, text).
pub type Page<'a> = (&'a str, &'a str, &'a str);

pub fn list(pages: &[Page]) -> Value {
    json!({"pages": pages.iter().map(|(n, s, t)| json!({"page": n, "section": s, "title": title_of(t, n)})).collect::<Vec<_>>()})
}

/// Finds a page by its path (`plugins/overview`, `.md` optional, `\` or `/`), or by its file name alone when only
/// one page has that name; case is ignored.
pub fn show(pages: &[Page], page: Option<&str>) -> Result<Value, String> {
    let raw = page.map(str::trim).unwrap_or("").replace('\\', "/");
    let raw = raw.trim_start_matches("docs/wiki/").trim_matches('/');
    let want = raw.strip_suffix(".md").unwrap_or(raw);
    if want.is_empty() {
        return Err("page is required (docs.list names the pages)".into());
    }
    let hit = match pages.iter().find(|(n, _, _)| n.eq_ignore_ascii_case(want)) {
        Some(p) => Some(p),
        None => {
            let by_name: Vec<_> =
                pages.iter().filter(|(n, _, _)| n.rsplit('/').next().is_some_and(|b| b.eq_ignore_ascii_case(want))).collect();
            if by_name.len() > 1 {
                return Err(format!("'{want}' names several pages: {}", by_name.iter().map(|p| p.0).collect::<Vec<_>>().join(", ")));
            }
            by_name.first().copied()
        }
    };
    match hit {
        Some((n, s, t)) => Ok(json!({"page": n, "section": s, "title": title_of(t, n), "text": t})),
        None => Err(format!(
            "no docs page '{}'{}",
            want.chars().take(60).collect::<String>(),
            if pages.is_empty() { " (none bundled)".to_string() } else { format!("; available: {}", pages.iter().map(|p| p.0).collect::<Vec<_>>().join(", ")) }
        )),
    }
}

impl Plugin for Docs {
    fn call_tool(name: String, args: String, _ctx: CallContext) -> Result<String, String> {
        let a: Value = serde_json::from_str(if args.trim().is_empty() { "{}" } else { &args }).unwrap_or(Value::Null);
        match name.as_str() {
            "docs_list" | "docs.list" => Ok(list(bundle::PAGES).to_string()),
            "docs_show" | "docs.show" => show(bundle::PAGES, a.get("page").and_then(|v| v.as_str())).map(|v| v.to_string()),
            other => Err(format!("unknown tool {other}")),
        }
    }
}

export_plugin!(Docs);

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn list_and_show() {
        let pages = [("usage", "", "# Usage page\nbody"), ("plugins/overview", "Plugins", "# Overview\n"), ("a/x", "", ""), ("b/x", "", "")];
        assert_eq!(list(&pages)["pages"][0]["title"], "Usage page");
        assert_eq!(list(&pages)["pages"][1]["section"], "Plugins");
        assert_eq!(show(&pages, Some("USAGE.md")).unwrap()["page"], "usage");
        assert_eq!(show(&pages, Some("plugins\\Overview.md")).unwrap()["page"], "plugins/overview");
        assert_eq!(show(&pages, Some("docs/wiki/plugins/overview")).unwrap()["page"], "plugins/overview");
        assert_eq!(show(&pages, Some("overview")).unwrap()["section"], "Plugins");
        assert!(show(&pages, Some("x")).unwrap_err().contains("several pages"));
        assert!(show(&pages, Some("nope")).unwrap_err().contains("available: usage"));
    }

    #[test]
    fn the_whole_wiki_tree_is_bundled_in_nav_order() {
        let p = bundle::PAGES;
        assert_eq!(p[0].0, "index");
        assert_eq!(show(p, Some("plugins/overview")).unwrap()["section"], "Plugins");
        assert_eq!(show(p, Some("reference/cli")).unwrap()["page"], "reference/cli");
        assert_eq!(show(p, Some("test-daemon")).unwrap()["page"], "contributing/test-daemon");
        let pos = |id: &str| p.iter().position(|x| x.0 == id).unwrap();
        assert!(pos("plugins/overview") < pos("plugins/create") && pos("plugins/create") < pos("reference/cli"));
    }
}
