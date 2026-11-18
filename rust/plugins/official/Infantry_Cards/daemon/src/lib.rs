// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! Infantry_Cards, daemon half: the chat card directory (`cards.list`, `cards.show` as tools `cards_list`,
//! `cards_show`) and the safe card image proxy (`card_image` frame).
//!
//! HOST NEEDS: (1) the loaded modules' `cards` descriptors: this plugin asks the `module_cards` frame
//! (`{}` -> `cards` `[{type,module,summary,spec,hosts[]}]`, spec already resolved to text by the host, first
//! declaration of a type wins); (2) the image proxy fetches from any host a card type declares, but `http` is limited
//! to `daemon.endpoints`, so the fetch works only once the host grants card hosts (or fetches for the guest). The
//! on-disk image cache (`limits.cards.imageCacheMb`, `imageTtlHours`) is not built: it needs a files import.
use infantry_plugin_sdk::types::{CallContext, Caller, Frame, HttpRequest};
use infantry_plugin_sdk::{export_plugin, host, Plugin};
use serde_json::{json, Value};

const TIMEOUT_MS: u32 = 15_000;
const DEFAULT_SPEC_MAX: usize = 16_384;
const DEFAULT_IMAGE_BYTES: usize = 2_097_152;

pub struct Cards;

#[derive(Clone, Debug, PartialEq)]
pub struct Entry {
    pub ty: String,
    pub module: String,
    pub summary: String,
    pub spec: String,
    pub hosts: Vec<String>,
}

fn valid_type(t: &str) -> bool {
    let mut c = t.chars();
    matches!(c.next(), Some('a'..='z')) && c.all(|c| matches!(c, 'a'..='z' | '0'..='9' | '_' | '-'))
}

/// Entries from the host's `cards` answer: first declaration wins, malformed types are skipped.
pub fn entries(data: &str) -> Vec<Entry> {
    let v: Value = serde_json::from_str(data).unwrap_or(Value::Null);
    let mut out: Vec<Entry> = vec![];
    for c in v.as_array().into_iter().flatten() {
        let g = |k: &str| c.get(k).and_then(|x| x.as_str()).unwrap_or("").trim().to_string();
        let ty = g("type");
        if !valid_type(&ty) || out.iter().any(|e| e.ty == ty) {
            continue;
        }
        let hosts = c.get("hosts").and_then(|h| h.as_array()).map(|a| a.iter().filter_map(|x| x.as_str().map(String::from)).collect()).unwrap_or_default();
        out.push(Entry { ty, module: g("module"), summary: g("summary"), spec: g("spec"), hosts });
    }
    out
}

pub fn list(es: &[Entry]) -> Value {
    json!({"cards": es.iter().map(|e| json!({"type": e.ty, "module": e.module, "summary": e.summary})).collect::<Vec<_>>()})
}

pub fn show(es: &[Entry], ty: Option<&str>, max: usize) -> Result<Value, String> {
    let t = ty.map(str::trim).map(|t| t.strip_prefix("card:").unwrap_or(t)).unwrap_or("");
    if t.is_empty() {
        return Err("type is required (cards.list names the installed card types)".into());
    }
    let Some(e) = es.iter().find(|e| e.ty == t) else {
        return Err(format!(
            "no card type '{}' is installed{}",
            t.chars().take(60).collect::<String>(),
            if es.is_empty() { " (no loaded plugin declares a card)".to_string() } else { format!("; installed: {}", es.iter().map(|e| e.ty.as_str()).collect::<Vec<_>>().join(", ")) }
        ));
    };
    let spec = if e.spec.is_empty() { if e.summary.is_empty() { "(no spec declared)".to_string() } else { e.summary.clone() } } else { e.spec.clone() };
    let spec = if spec.len() > max { format!("{}\n(spec cut at {max} bytes)", spec.chars().scan(0usize, |n, c| { *n += c.len_utf8(); (*n <= max).then_some(c) }).collect::<String>()) } else { spec };
    let mut o = json!({"type": e.ty, "module": e.module, "summary": e.summary, "fence": format!("```card:{}", e.ty), "spec": spec});
    if !e.hosts.is_empty() {
        o["imageHosts"] = json!(e.hosts);
    }
    Ok(o)
}

/// `example.com` exact, `*.example.com` any subdomain, `*` any host.
pub fn host_allowed(host: &str, patterns: &[String]) -> bool {
    let h = host.to_lowercase();
    patterns.iter().map(|p| p.trim().to_lowercase()).any(|p| p == "*" || p == h || p.strip_prefix("*.").map(|s| h.ends_with(&format!(".{s}"))).unwrap_or(false))
}

fn url_host(url: &str) -> Option<String> {
    let rest = url.strip_prefix("https://")?;
    let auth = rest.split(['/', '?', '#']).next()?;
    let auth = auth.rsplit('@').next()?;
    let h = auth.split(':').next()?;
    (!h.is_empty()).then(|| h.to_string())
}

fn b64(bytes: &[u8]) -> String {
    const T: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut s = String::with_capacity(bytes.len().div_ceil(3) * 4);
    for c in bytes.chunks(3) {
        let n = (c[0] as u32) << 16 | (*c.get(1).unwrap_or(&0) as u32) << 8 | *c.get(2).unwrap_or(&0) as u32;
        s.push(T[(n >> 18) as usize & 63] as char);
        s.push(T[(n >> 12) as usize & 63] as char);
        s.push(if c.len() > 1 { T[(n >> 6) as usize & 63] as char } else { '=' });
        s.push(if c.len() > 2 { T[n as usize & 63] as char } else { '=' });
    }
    s
}

fn setting(k: &str) -> Option<String> {
    host::config::get(k).map(|s| s.trim().to_string()).filter(|s| !s.is_empty())
}

/// The `card_image` answer: `{ok, mime, base64}` or `{ok:false, refused|failed}`.
pub fn image_answer(es: &[Entry], data: &str, extra_hosts: &[String], max_bytes: usize, enabled: bool, fetch: &dyn Fn(&str) -> Result<(u16, Vec<(String, String)>, Vec<u8>), String>) -> Value {
    let refuse = |why: &str| json!({"ok": false, "refused": why});
    let Some(o) = serde_json::from_str::<Value>(data).ok().filter(|v| v.is_object()) else { return refuse("expects {\"type\", \"url\"}") };
    let (ty, url) = (o.get("type").and_then(|v| v.as_str()).unwrap_or(""), o.get("url").and_then(|v| v.as_str()).unwrap_or(""));
    if !enabled {
        return refuse("card images are switched off (cards.images.enabled)");
    }
    let Some(e) = es.iter().find(|e| e.ty == ty) else { return refuse(&format!("no card type '{}' is installed", ty.chars().take(60).collect::<String>())) };
    let Some(h) = url_host(url) else { return refuse("only https image URLs are fetched") };
    let mut allow = e.hosts.clone();
    allow.extend_from_slice(extra_hosts);
    if !host_allowed(&h, &allow) {
        return refuse(&format!("host {h} is not declared by card:{ty}"));
    }
    match fetch(url) {
        Err(e) => json!({"ok": false, "failed": e.chars().take(160).collect::<String>()}),
        Ok((status, headers, body)) => {
            if !(200..300).contains(&status) {
                return json!({"ok": false, "failed": format!("HTTP {status}")});
            }
            if body.len() > max_bytes {
                return json!({"ok": false, "failed": "image is larger than limits.cards.imageBytes"});
            }
            let mime = headers.iter().find(|(k, _)| k.eq_ignore_ascii_case("content-type")).map(|(_, v)| v.split(';').next().unwrap_or("").trim().to_lowercase()).unwrap_or_default();
            if !mime.starts_with("image/") || mime.contains("svg") {
                return json!({"ok": false, "failed": format!("not a raster image ({})", if mime.is_empty() { "no content type" } else { &mime })});
            }
            json!({"ok": true, "mime": mime, "base64": b64(&body)})
        }
    }
}

fn load_entries() -> Result<Vec<Entry>, String> {
    let r = host::frames::request("module_cards", "{}", None, TIMEOUT_MS)?;
    if r.type_ == "error" {
        return Err(r.data);
    }
    Ok(entries(&r.data))
}

impl Plugin for Cards {
    fn call_tool(name: String, args: String, _ctx: CallContext) -> Result<String, String> {
        let a: Value = serde_json::from_str(if args.trim().is_empty() { "{}" } else { &args }).unwrap_or(Value::Null);
        match name.as_str() {
            "cards_list" => Ok(list(&load_entries()?).to_string()),
            "cards_show" => {
                let max = setting("cards.specMaxBytes").and_then(|v| v.parse::<usize>().ok()).map(|n| n.max(256)).unwrap_or(DEFAULT_SPEC_MAX);
                show(&load_entries()?, a.get("type").and_then(|v| v.as_str()), max).map(|v| v.to_string())
            }
            other => Err(format!("unknown tool {other}")),
        }
    }

    fn handle_frame(frame: Frame, _caller: Caller) -> Result<Option<Frame>, String> {
        if frame.type_ != "card_image" {
            return Err(format!("this plugin does not answer {}", frame.type_));
        }
        let es = load_entries()?;
        let extra: Vec<String> = setting("cards.images.allow").map(|s| s.split(',').map(|x| x.trim().to_string()).filter(|x| !x.is_empty()).collect()).unwrap_or_default();
        let max = setting("limits.cards.imageBytes").and_then(|v| v.parse().ok()).unwrap_or(DEFAULT_IMAGE_BYTES);
        let enabled = setting("cards.images.enabled").map(|v| v != "false").unwrap_or(true);
        let fetch = |url: &str| {
            host::http::send(&HttpRequest { method: "GET".into(), url: url.into(), headers: vec![("Accept".into(), "image/*".into())], body: None, timeout_ms: None }).map(|r| (r.status, r.headers, r.body))
        };
        let out = image_answer(&es, &frame.data, &extra, max, enabled, &fetch);
        Ok(Some(Frame { type_: "card_image".into(), data: out.to_string(), session_id: None, request_id: frame.request_id }))
    }
}

export_plugin!(Cards);

#[cfg(test)]
mod tests {
    use super::*;

    fn es() -> Vec<Entry> {
        entries(r#"[{"type":"news","module":"Infantry_News","summary":"s","spec":"how","hosts":["*.good.com"]},{"type":"news","module":"x"},{"type":"Bad!"}]"#)
    }

    #[test]
    fn directory() {
        assert_eq!(es().len(), 1);
        assert_eq!(show(&es(), Some("card:news"), 100).unwrap()["fence"], "```card:news");
        assert!(show(&es(), Some("zzz"), 100).unwrap_err().contains("installed: news"));
    }

    #[test]
    fn image_rules() {
        let ok = |_: &str| Ok((200u16, vec![("Content-Type".to_string(), "image/png".to_string())], vec![1u8, 2, 3]));
        let a = image_answer(&es(), r#"{"type":"news","url":"https://cdn.good.com/a.png"}"#, &[], 100, true, &ok);
        assert_eq!(a["base64"], "AQID");
        let r = image_answer(&es(), r#"{"type":"news","url":"https://evil.com/a.png"}"#, &[], 100, true, &ok);
        assert!(r["refused"].as_str().unwrap().contains("not declared"));
    }
}
