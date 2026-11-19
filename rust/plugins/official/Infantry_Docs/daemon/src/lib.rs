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

pub fn list(pages: &[(&str, &str)]) -> Value {
    json!({"pages": pages.iter().map(|(n, t)| json!({"page": n, "title": title_of(t, n)})).collect::<Vec<_>>()})
}

pub fn show(pages: &[(&str, &str)], page: Option<&str>) -> Result<Value, String> {
    let want = page.map(str::trim).map(|p| p.strip_suffix(".md").unwrap_or(p)).unwrap_or("");
    if want.is_empty() {
        return Err("page is required (docs.list names the pages)".into());
    }
    match pages.iter().find(|(n, _)| n.eq_ignore_ascii_case(want)) {
        Some((n, t)) => Ok(json!({"page": n, "title": title_of(t, n), "text": t})),
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
        let pages = [("usage", "# Usage page\nbody")];
        assert_eq!(list(&pages)["pages"][0]["title"], "Usage page");
        assert_eq!(show(&pages, Some("USAGE.md")).unwrap()["page"], "usage");
        assert!(show(&pages, Some("nope")).unwrap_err().contains("available: usage"));
        assert!(!bundle::PAGES.is_empty());
    }
}
