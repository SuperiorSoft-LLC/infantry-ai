// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! Google News RSS, the pure half (no network, no clock): the feed URL a request maps to, the parse of a feed into
//! headline items, and the `card:news` card. Mirrors the frozen Kotlin `GoogleNewsFeed` / `NewsDaemonPlugin` behaviour.

use quick_xml::events::Event;
use quick_xml::Reader;
use serde_json::{json, Map, Value};

pub const BASE: &str = "https://news.google.com/rss";

/// Google News topic sections; a topic outside these is searched for instead.
pub fn topic_section(topic: &str) -> Option<&'static str> {
    Some(match topic.to_lowercase().as_str() {
        "world" => "WORLD",
        "nation" | "us" => "NATION",
        "business" => "BUSINESS",
        "technology" | "tech" => "TECHNOLOGY",
        "entertainment" => "ENTERTAINMENT",
        "sports" | "sport" => "SPORTS",
        "science" => "SCIENCE",
        "health" => "HEALTH",
        _ => return None,
    })
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Request {
    pub url: String,
    pub label: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct Item {
    pub title: String,
    pub source: String,
    pub url: String,
    pub time: String,
    pub source_url: String,
    pub image: String,
    pub icon: String,
}

/// `application/x-www-form-urlencoded` like Java's `URLEncoder` (space is `+`, `.-*_` stay).
pub fn enc(s: &str) -> String {
    let mut o = String::new();
    for b in s.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'.' | b'-' | b'*' | b'_' => o.push(b as char),
            b' ' => o.push('+'),
            _ => o.push_str(&format!("%{b:02X}")),
        }
    }
    o
}

fn upper_first(s: &str) -> String {
    let mut c = s.chars();
    match c.next() {
        Some(f) => f.to_uppercase().collect::<String>() + c.as_str(),
        None => String::new(),
    }
}

/// `^(?:https?://)?(?:www\.)?([a-z0-9-]+(?:\.[a-z0-9-]+)+)/?$` (case-insensitive); the lowercased domain.
fn domain_of(s: &str) -> Option<String> {
    let l = s.to_lowercase();
    let mut r = l.as_str();
    r = r.strip_prefix("https://").or_else(|| r.strip_prefix("http://")).unwrap_or(r);
    r = r.strip_prefix("www.").unwrap_or(r);
    r = r.strip_suffix('/').unwrap_or(r);
    let labels: Vec<&str> = r.split('.').collect();
    let ok = labels.len() >= 2
        && labels.iter().all(|p| !p.is_empty() && p.bytes().all(|b| b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'-'));
    ok.then(|| r.to_string())
}

/// The feed for query / topic / source in locale `lang` (`en-US`) and country (`US`).
pub fn request(query: Option<&str>, topic: Option<&str>, source: Option<&str>, lang: &str, country: &str) -> Request {
    let q = query.unwrap_or("").trim();
    let t = topic.unwrap_or("").trim();
    let s = source.unwrap_or("").trim();
    let lang_head = lang.split('-').next().unwrap_or(lang);
    let ceid = format!("hl={}&gl={}&ceid={}", enc(lang), enc(country), enc(&format!("{country}:{lang_head}")));
    if q.is_empty() && s.is_empty() {
        if t.is_empty() {
            return Request { url: format!("{BASE}?{ceid}"), label: "Top stories".into() };
        }
        if let Some(sec) = topic_section(t) {
            return Request { url: format!("{BASE}/headlines/section/topic/{sec}?{ceid}"), label: upper_first(t) };
        }
    }
    let domain = domain_of(s);
    let last = domain.as_ref().map(|d| format!("site:{d}")).unwrap_or_else(|| s.to_string());
    let terms: Vec<&str> = [q, t, last.as_str()].into_iter().filter(|x| !x.is_empty()).collect();
    let name = domain.unwrap_or_else(|| s.to_string());
    let head = [q, t].into_iter().filter(|x| !x.is_empty()).collect::<Vec<_>>().join(" \u{b7} ");
    let mut label = if head.is_empty() { "Latest".to_string() } else { head };
    if !name.is_empty() {
        label.push_str(&format!(" ({name})"));
    }
    Request { url: format!("{BASE}/search?q={}&{ceid}", enc(&terms.join(" "))), label }
}

/// Strip `<[^>]+>` tags and collapse whitespace (Kotlin `clean`).
fn clean(s: &str) -> String {
    let mut stripped = String::with_capacity(s.len());
    let mut rest = s;
    while let Some(i) = rest.find('<') {
        stripped.push_str(&rest[..i]);
        let after = &rest[i + 1..];
        match after.find('>') {
            Some(end) if end > 0 => rest = &after[end + 1..],
            _ => {
                stripped.push('<');
                rest = after;
            }
        }
    }
    stripped.push_str(rest);
    stripped.split_whitespace().collect::<Vec<_>>().join(" ")
}

struct Node {
    name: String,
    attrs: Vec<(String, String)>,
    text: String,
    end: usize, // exclusive index just past the last descendant
}

impl Node {
    fn attr(&self, k: &str) -> String {
        self.attrs.iter().find(|(n, _)| n == k).map(|(_, v)| v.trim().to_string()).unwrap_or_default()
    }
}

fn open(e: &quick_xml::events::BytesStart, nodes: &mut Vec<Node>) -> Result<usize, String> {
    let mut attrs = Vec::new();
    for a in e.attributes() {
        let a = a.map_err(|x| x.to_string())?;
        let v = a.normalized_value().map_err(|x| x.to_string())?.into_owned();
        attrs.push((a.key.into_inner().to_string(), v));
    }
    nodes.push(Node { name: e.name().into_inner().to_string(), attrs, text: String::new(), end: 0 });
    Ok(nodes.len() - 1)
}

fn push_text(nodes: &mut [Node], stack: &[usize], t: &str) {
    for &i in stack {
        nodes[i].text.push_str(t);
    }
}

fn parse_nodes(xml: &str) -> Result<Vec<Node>, String> {
    let mut rd = Reader::from_str(xml);
    let mut nodes: Vec<Node> = Vec::new();
    let mut stack: Vec<usize> = Vec::new();
    let bad = |m: String| format!("the feed is not valid RSS ({})", m.chars().take(120).collect::<String>());
    loop {
        match rd.read_event().map_err(|e| bad(e.to_string()))? {
            Event::Start(e) => {
                let i = open(&e, &mut nodes).map_err(bad)?;
                stack.push(i);
            }
            Event::Empty(e) => {
                let i = open(&e, &mut nodes).map_err(bad)?;
                nodes[i].end = nodes.len();
            }
            Event::End(_) => {
                let i = stack.pop().ok_or_else(|| bad("unbalanced end tag".into()))?;
                nodes[i].end = nodes.len();
            }
            Event::Text(t) => push_text(&mut nodes, &stack, &*t.as_ref() as &str),
            Event::CData(t) => push_text(&mut nodes, &stack, &*t.as_ref() as &str),
            Event::GeneralRef(r) => {
                let name = r.as_ref().to_string();
                let rep = if let Some(c) = r.resolve_char_ref().map_err(|e| bad(e.to_string()))? {
                    c.to_string()
                } else if let Some(s) = quick_xml::escape::resolve_predefined_entity(&name) {
                    s.to_string()
                } else {
                    return Err(bad(format!("undefined entity &{name};")));
                };
                push_text(&mut nodes, &stack, &rep);
            }
            Event::DocType(_) => return Err(bad("DOCTYPE is not allowed".into())),
            Event::Eof => break,
            _ => {}
        }
    }
    if !stack.is_empty() {
        return Err(bad("unclosed element".into()));
    }
    if nodes.is_empty() {
        return Err(bad("empty document".into()));
    }
    Ok(nodes)
}

fn host_of(url: &str) -> String {
    let u = url.trim();
    let rest = match u.split_once("://") {
        Some((_, r)) => r,
        None => return String::new(),
    };
    let auth = rest.split(['/', '?', '#']).next().unwrap_or("");
    let auth = auth.rsplit('@').next().unwrap_or("");
    let host = if let Some(s) = auth.strip_prefix('[') { s.split(']').next().unwrap_or("") } else { auth.split(':').next().unwrap_or("") };
    let host = host.to_lowercase();
    host.strip_prefix("www.").unwrap_or(&host).to_string()
}

/// The source's site icon through Google's favicon service; empty when `source_url` has no host.
pub fn icon_for(source_url: &str) -> String {
    let h = host_of(source_url);
    if h.is_empty() {
        String::new()
    } else {
        format!("https://www.google.com/s2/favicons?domain={}&sz=64", enc(&h))
    }
}

fn days_from_civil(y: i64, m: i64, d: i64) -> i64 {
    let y = if m <= 2 { y - 1 } else { y };
    let era = y.div_euclid(400);
    let yoe = y - era * 400;
    let doy = (153 * (if m > 2 { m - 3 } else { m + 9 }) + 2) / 5 + d - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146097 + doe - 719468
}

fn civil_from_days(z: i64) -> (i64, i64, i64) {
    let z = z + 719468;
    let era = z.div_euclid(146097);
    let doe = z - era * 146097;
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    (if m <= 2 { y + 1 } else { y }, m, d)
}

fn parse_rfc1123(raw: &str) -> Option<String> {
    let mut s = raw.trim();
    if let Some((dow, rest)) = s.split_once(',') {
        if dow.trim().len() != 3 {
            return None;
        }
        s = rest.trim();
    }
    let p: Vec<&str> = s.split_whitespace().collect();
    if p.len() != 5 {
        return None;
    }
    let day: i64 = p[0].parse().ok()?;
    let mon = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"]
        .iter()
        .position(|m| m.eq_ignore_ascii_case(p[1]))? as i64
        + 1;
    let year: i64 = p[2].parse().ok().filter(|_| p[2].len() == 4)?;
    let t: Vec<&str> = p[3].split(':').collect();
    let (h, mi, sec): (i64, i64, i64) = match t.as_slice() {
        [h, m] => (h.parse().ok()?, m.parse().ok()?, 0),
        [h, m, s] => (h.parse().ok()?, m.parse().ok()?, s.parse().ok()?),
        _ => return None,
    };
    let off = match p[4] {
        "GMT" | "UT" | "Z" => 0,
        z if z.len() == 5 && (z.starts_with('+') || z.starts_with('-')) => {
            let v = z[1..3].parse::<i64>().ok()? * 3600 + z[3..5].parse::<i64>().ok()? * 60;
            if z.starts_with('-') {
                -v
            } else {
                v
            }
        }
        _ => return None,
    };
    if !(1..=31).contains(&day) || h > 23 || mi > 59 || sec > 59 {
        return None;
    }
    let epoch = days_from_civil(year, mon, day) * 86400 + h * 3600 + mi * 60 + sec - off;
    let (y, m, d) = civil_from_days(epoch.div_euclid(86400));
    let r = epoch.rem_euclid(86400);
    Some(format!("{y:04}-{m:02}-{d:02}T{:02}:{:02}:{:02}Z", r / 3600, r % 3600 / 60, r % 60))
}

/// RFC 1123 to ISO-8601 (UTC); unparsable stays as given (trimmed).
pub fn iso_time(raw: &str) -> String {
    parse_rfc1123(raw).unwrap_or_else(|| raw.trim().to_string())
}

/// The items of an RSS 2.0 feed in feed order, at most `max`. A malformed feed is an `Err` with one plain message.
pub fn parse(xml: &str, max: usize) -> Result<Vec<Item>, String> {
    let nodes = parse_nodes(xml)?;
    let mut out = Vec::new();
    for (k, n) in nodes.iter().enumerate() {
        if out.len() >= max {
            break;
        }
        if n.name != "item" {
            continue;
        }
        let desc = &nodes[k + 1..n.end];
        let first = |tag: &str| desc.iter().find(|d| d.name == tag);
        let text = |tag: &str| first(tag).map(|d| clean(&d.text)).unwrap_or_default();
        let source_el = first("source");
        let source = source_el.map(|d| clean(&d.text)).unwrap_or_default();
        let mut title = text("title");
        let suffix = format!(" - {source}");
        if !source.is_empty() && title.ends_with(&suffix) {
            title = title[..title.len() - suffix.len()].trim().to_string();
        }
        let url = text("link");
        if title.is_empty() || !url.starts_with("http") {
            continue;
        }
        let host = host_of(&url);
        out.push(Item {
            title,
            source: if source.is_empty() { host } else { source },
            url,
            time: iso_time(&text("pubDate")),
            source_url: source_el.map(|d| d.attr("url")).unwrap_or_default(),
            image: image_of(desc),
            icon: String::new(),
        });
    }
    Ok(out)
}

fn image_of(desc: &[Node]) -> String {
    for tag in ["media:content", "media:thumbnail", "enclosure"] {
        for m in desc.iter().filter(|d| d.name == tag) {
            let url = m.attr("url");
            let ty = m.attr("type").to_lowercase();
            let medium = m.attr("medium").to_lowercase();
            if !url.starts_with("https://") {
                continue;
            }
            if tag == "enclosure" && !ty.starts_with("image/") {
                continue;
            }
            if tag == "media:content" && !medium.is_empty() && medium != "image" {
                continue;
            }
            return url;
        }
    }
    String::new()
}

/// The `card:news` body `{"title", "items": [{title, source, url, time?, sourceUrl?, image?, icon?}]}`.
pub fn card_body(label: &str, items: &[Item]) -> Value {
    let arr: Vec<Value> = items
        .iter()
        .map(|i| {
            let mut m = Map::new();
            m.insert("title".into(), json!(i.title));
            m.insert("source".into(), json!(i.source));
            m.insert("url".into(), json!(i.url));
            for (k, v) in [("time", &i.time), ("sourceUrl", &i.source_url), ("image", &i.image), ("icon", &i.icon)] {
                if !v.is_empty() {
                    m.insert(k.into(), json!(v));
                }
            }
            Value::Object(m)
        })
        .collect();
    json!({ "title": label, "items": arr })
}

/// The `news.*` / `limits.news.*` settings, already read (Kotlin defaults).
#[derive(Debug, Clone)]
pub struct Settings {
    pub items: usize,
    pub max_items: usize,
    pub language: String,
    pub country: String,
    pub source_icons: bool,
    pub feed_bytes: usize,
}

impl Default for Settings {
    fn default() -> Self {
        Settings { items: 6, max_items: 20, language: "en-US".into(), country: "US".into(), source_icons: true, feed_bytes: 2_097_152 }
    }
}

/// The count to return: `max` or the `news.items` default, clamped to `1..=limits.news.maxItems`.
pub fn effective_count(max: Option<i64>, s: &Settings) -> usize {
    let cap = s.max_items.clamp(1, 100);
    match max {
        Some(m) => (m.max(1) as usize).min(cap),
        None => s.items.clamp(1, cap),
    }
}

/// Build the capability answer `{title, feed, count, card}` from a fetched feed body.
pub fn answer(req: &Request, xml: &str, n: usize, cap: usize, s: &Settings) -> Result<Value, String> {
    let mut items = parse(xml, cap)?;
    items.truncate(n);
    if s.source_icons {
        for i in &mut items {
            i.icon = icon_for(&i.source_url);
        }
    }
    let body = card_body(&req.label, &items);
    Ok(json!({ "title": req.label, "feed": req.url, "count": items.len(), "card": format!("```card:news\n{body}\n```") }))
}

#[cfg(test)]
mod tests {
    use super::*;

    const TOP: &str = include_str!("../tests/fixtures/top.xml");

    #[test]
    fn top_stories_url() {
        let r = request(None, None, None, "en-US", "US");
        assert_eq!(r.url, "https://news.google.com/rss?hl=en-US&gl=US&ceid=US%3Aen");
        assert_eq!(r.label, "Top stories");
    }

    #[test]
    fn topic_section_and_search() {
        let r = request(None, Some("tech"), None, "en-GB", "GB");
        assert_eq!(r.url, "https://news.google.com/rss/headlines/section/topic/TECHNOLOGY?hl=en-GB&gl=GB&ceid=GB%3Aen");
        assert_eq!(r.label, "Tech");
        let r = request(Some("SpaceX launch"), Some("tech"), Some("https://www.Reuters.com/"), "en-US", "US");
        assert_eq!(r.url, "https://news.google.com/rss/search?q=SpaceX+launch+tech+site%3Areuters.com&hl=en-US&gl=US&ceid=US%3Aen");
        assert_eq!(r.label, "SpaceX launch \u{b7} tech (reuters.com)");
        let r = request(None, None, Some("The Verge"), "en-US", "US");
        assert!(r.url.contains("search?q=The+Verge&"));
        assert_eq!(r.label, "Latest (The Verge)");
        let r = request(None, Some("gardening"), None, "en-US", "US");
        assert!(r.url.contains("search?q=gardening&"));
    }

    #[test]
    fn parses_recorded_feed() {
        let items = parse(TOP, 100).unwrap();
        assert_eq!(items.len(), 8);
        let i = &items[0];
        assert_eq!(i.title, "APEC summit latest: Trump welcomed at airport by Xi in rare move and greets Putin at banquet");
        assert_eq!(i.source, "CNN");
        assert_eq!(i.time, "2026-11-18T12:49:00Z");
        assert!(i.url.starts_with("https://news.google.com/rss/articles/"));
        assert!(i.source_url.starts_with("http"));
        assert_eq!(parse(TOP, 3).unwrap().len(), 3);
    }

    #[test]
    fn media_images_and_entities() {
        let xml = r#"<rss xmlns:media="m"><channel><item><title>A &amp; B &lt;i&gt;x&lt;/i&gt; - Src</title><link>https://x.test/a</link>
          <source url="https://src.test">Src</source><pubDate>Thu, 12 Nov 2026 10:30:00 +0100</pubDate>
          <media:content url="http://insecure/i.jpg"/><media:thumbnail url="https://img.test/t.jpg"/></item>
          <item><title>no link</title></item><item><title><![CDATA[Two]]></title><link>https://y.test/b</link></item></channel></rss>"#;
        let items = parse(xml, 10).unwrap();
        assert_eq!(items.len(), 2);
        assert_eq!(items[0].title, "A & B x");
        assert_eq!(items[0].time, "2026-11-12T09:30:00Z");
        assert_eq!(items[0].image, "https://img.test/t.jpg");
        assert_eq!(items[1].source, "y.test");
        assert_eq!(items[1].time, "");
    }

    #[test]
    fn malformed_and_doctype_are_plain_errors() {
        assert!(parse("<rss><channel><item></rss>", 5).unwrap_err().starts_with("the feed is not valid RSS"));
        assert!(parse("not xml", 5).is_err());
        assert!(parse("<!DOCTYPE x [<!ENTITY a \"b\">]><rss/>", 5).is_err());
    }

    #[test]
    fn time_and_icon() {
        assert_eq!(iso_time("garbage "), "garbage");
        assert_eq!(iso_time("Sun, 01 Mar 2026 23:59:59 GMT"), "2026-03-01T23:59:59Z");
        assert_eq!(icon_for("https://www.cnn.com/x"), "https://www.google.com/s2/favicons?domain=cnn.com&sz=64");
        assert_eq!(icon_for(""), "");
    }

    #[test]
    fn count_defaults_and_cap() {
        let s = Settings::default();
        assert_eq!(effective_count(None, &s), 6);
        assert_eq!(effective_count(Some(50), &s), 20);
        assert_eq!(effective_count(Some(0), &s), 1);
        assert_eq!(effective_count(None, &Settings { max_items: 3, ..s }), 3);
    }

    #[test]
    fn answer_shape_never_invents() {
        let s = Settings::default();
        let req = request(None, None, None, "en-US", "US");
        let a = answer(&req, TOP, 2, 20, &s).unwrap();
        assert_eq!(a["count"], 2);
        let card = a["card"].as_str().unwrap();
        assert!(card.starts_with("```card:news\n{\"title\":\"Top stories\",\"items\":[{\"title\":\"APEC"));
        assert!(card.ends_with("\n```"));
        assert!(card.contains("\"icon\":\"https://www.google.com/s2/favicons?domain="));
        let a = answer(&req, "<rss><channel/></rss>", 6, 20, &s).unwrap();
        assert_eq!(a["count"], 0);
    }
}
