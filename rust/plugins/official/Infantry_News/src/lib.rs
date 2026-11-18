// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! Infantry_News: the `news.headlines` capability as a WASM component (world `daemon-plugin`).
pub mod feed;

#[cfg(target_arch = "wasm32")]
mod guest {
    use super::feed;

    wit_bindgen::generate!({
        path: "../../../crates/infantry-plugin-wit/wit",
        world: "daemon-plugin",
    });

    use infantry::plugin::types::HttpRequest;

    struct News;

    fn setting(key: &str) -> Option<String> {
        infantry::plugin::config::get(key).map(|s| s.trim().to_string()).filter(|s| !s.is_empty())
    }

    fn settings() -> feed::Settings {
        let d = feed::Settings::default();
        let num = |k: &str, def: usize, lo: usize, hi: usize| {
            setting(k).and_then(|v| v.parse::<f64>().ok()).map(|v| (v as i64).clamp(lo as i64, hi as i64) as usize).unwrap_or(def)
        };
        feed::Settings {
            items: num("news.items", d.items, 1, 100),
            max_items: num("limits.news.maxItems", d.max_items, 1, 100),
            language: setting("news.language").unwrap_or(d.language),
            country: setting("news.country").unwrap_or(d.country),
            source_icons: setting("news.sourceIcons").map(|v| !matches!(v.to_lowercase().as_str(), "false" | "0" | "off")).unwrap_or(true),
            feed_bytes: num("limits.news.feedBytes", d.feed_bytes, 16_384, 20_971_520),
        }
    }

    fn clip(v: &serde_json::Value, k: &str) -> Option<String> {
        let s = match v.get(k)? {
            serde_json::Value::String(s) => s.clone(),
            other => other.to_string(),
        };
        let s: String = s.trim().chars().take(200).collect();
        (!s.is_empty()).then_some(s)
    }

    fn headlines(args: &str) -> Result<String, String> {
        let a: serde_json::Value = serde_json::from_str(args).unwrap_or(serde_json::Value::Null);
        let s = settings();
        let max = a.get("max").and_then(|m| m.as_f64().or_else(|| m.as_str().and_then(|x| x.parse().ok()))).map(|m| m as i64);
        let n = feed::effective_count(max, &s);
        let cap = s.max_items.clamp(1, 100);
        let req = feed::request(clip(&a, "query").as_deref(), clip(&a, "topic").as_deref(), clip(&a, "source").as_deref(), &s.language, &s.country);
        let resp = infantry::plugin::http::send(&HttpRequest {
            method: "GET".into(),
            url: req.url.clone(),
            headers: vec![
                ("User-Agent".into(), "InfantryAI-News/1.0".into()),
                ("Accept".into(), "application/rss+xml, application/xml;q=0.9, */*;q=0.5".into()),
            ],
            body: None,
            timeout_ms: None,
        })
        .map_err(|e| format!("the news feed could not be fetched ({})", e.chars().take(160).collect::<String>()))?;
        if (400..600).contains(&(resp.status as u32)) {
            return Err(format!("Google News answered HTTP {}", resp.status));
        }
        if resp.body.len() > s.feed_bytes {
            return Err("the news feed is larger than limits.news.feedBytes".into());
        }
        let xml = String::from_utf8_lossy(&resp.body);
        feed::answer(&req, &xml, n, cap, &s).map(|v| v.to_string())
    }

    impl Guest for News {
        fn start() -> Result<(), String> {
            Ok(())
        }
        fn stop() {}
        fn call_tool(name: String, args: String, _ctx: CallContext) -> Result<String, String> {
            match name.as_str() {
                "news_headlines" | "news.read" | "news.headlines" => headlines(&args),
                other => Err(format!("unknown tool {other}")),
            }
        }
        fn handle_frame(_f: Frame, _c: Caller) -> Result<Option<Frame>, String> {
            Ok(None)
        }
        fn handle_route(_r: RouteRequest) -> Result<HttpResponse, String> {
            Err("no routes".into())
        }
        fn on_hook(_k: String, _p: String) -> Result<String, String> {
            Err("no hooks".into())
        }
        fn on_event(_s: u64, _e: NamedEvent) {}
    }

    export!(News);
}
