// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Small shared helpers: clock, number and time formatting, lenient JSON readers.
use serde_json::Value;

/// Epoch millis now.
pub fn now_ms() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}

/// A clock: epoch millis. Injected everywhere so tests never wait.
pub type Clock = std::sync::Arc<dyn Fn() -> i64 + Send + Sync>;

pub fn system_clock() -> Clock {
    std::sync::Arc::new(now_ms)
}

/// `12` for a whole number, else one decimal (`12.5`).
pub fn fmt_num(d: f64) -> String {
    if d == d.floor() && d.abs() < 1e15 {
        format!("{}", d as i64)
    } else {
        format!("{d:.1}")
    }
}

/// Rounded to one decimal.
pub fn round1(d: f64) -> f64 {
    (d * 10.0).round() / 10.0
}

/// "2d 3h", "1h 20m", "35m" until `at`.
pub fn until(at: i64, now: i64) -> String {
    let mins = ((at - now) / 60_000).max(0);
    if mins >= 24 * 60 {
        format!("{}d {}h", mins / (24 * 60), (mins % (24 * 60)) / 60)
    } else if mins >= 60 {
        format!("{}h {}m", mins / 60, mins % 60)
    } else {
        format!("{mins}m")
    }
}

/// `yyyy-MM-dd HH:mm +hh:mm` in the local zone (DisplayZone.stamp).
pub fn stamp(epoch_ms: i64) -> String {
    use chrono::TimeZone;
    match chrono::Local.timestamp_millis_opt(epoch_ms) {
        chrono::LocalResult::Single(t) | chrono::LocalResult::Ambiguous(t, _) => t.format("%Y-%m-%d %H:%M %:z").to_string(),
        chrono::LocalResult::None => epoch_ms.to_string(),
    }
}

/// The local calendar day (`yyyy-MM-dd`) of `epoch_ms`.
pub fn local_day(epoch_ms: i64) -> String {
    use chrono::TimeZone;
    match chrono::Local.timestamp_millis_opt(epoch_ms) {
        chrono::LocalResult::Single(t) | chrono::LocalResult::Ambiguous(t, _) => t.format("%Y-%m-%d").to_string(),
        chrono::LocalResult::None => "1970-01-01".into(),
    }
}

/// A number, or a string such as `"42"` / `"42.5%"`.
pub fn percent_of(v: Option<&Value>) -> Option<f64> {
    match v? {
        Value::Number(n) => n.as_f64(),
        Value::String(s) => s.trim().trim_end_matches('%').trim().parse().ok(),
        _ => None,
    }
}

/// ISO-8601 (with an offset), or epoch seconds / millis.
pub fn time_of(v: Option<&Value>) -> Option<i64> {
    let v = v?;
    let number = match v {
        Value::Number(n) => n.as_f64(),
        Value::String(s) => s.trim().parse::<f64>().ok(),
        _ => None,
    };
    if let Some(n) = number {
        let t = n as i64;
        return Some(if t < 100_000_000_000 { t * 1000 } else { t });
    }
    let s = v.as_str()?.trim();
    chrono::DateTime::parse_from_rfc3339(s).ok().map(|d| d.timestamp_millis())
}

/// `Retry-After`: delta-seconds or an HTTP date, as a delay in ms from `now`; `None` when it reads as neither.
pub fn parse_retry_after(value: Option<&str>, now: i64) -> Option<i64> {
    let v = value?.trim();
    if v.is_empty() {
        return None;
    }
    if let Ok(secs) = v.parse::<i64>() {
        return if secs >= 0 { Some(secs * 1000) } else { None };
    }
    chrono::DateTime::parse_from_rfc2822(v).ok().map(|d| (d.timestamp_millis() - now).max(0))
}

/// A non-blank string field.
pub fn str_field<'a>(v: &'a Value, key: &str) -> Option<&'a str> {
    v.get(key)?.as_str().filter(|s| !s.trim().is_empty())
}

/// A number field (never a string) as i64.
pub fn long_field(v: &Value, key: &str) -> Option<i64> {
    match v.get(key)? {
        Value::Number(n) => n.as_i64().or_else(|| n.as_f64().map(|f| f as i64)),
        _ => None,
    }
}

/// A number field (never a string) as f64.
pub fn f64_field(v: &Value, key: &str) -> Option<f64> {
    match v.get(key)? {
        Value::Number(n) => n.as_f64(),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn formats_and_parses() {
        assert_eq!(fmt_num(12.0), "12");
        assert_eq!(fmt_num(12.46), "12.5");
        assert_eq!(until(3 * 3_600_000 + 5 * 60_000, 0), "3h 5m");
        assert_eq!(until(26 * 3_600_000, 0), "1d 2h");
        assert_eq!(percent_of(Some(&json!("42.5%"))), Some(42.5));
        assert_eq!(time_of(Some(&json!(1_700_000_000))), Some(1_700_000_000_000));
        assert_eq!(time_of(Some(&json!("2025-01-01T00:00:00+00:00"))), Some(1_735_689_600_000));
        assert_eq!(parse_retry_after(Some("30"), 0), Some(30_000));
        assert_eq!(parse_retry_after(Some("Wed, 01 Jan 2025 00:01:00 GMT"), 1_735_689_600_000), Some(60_000));
        assert_eq!(parse_retry_after(Some("soon"), 0), None);
    }
}
