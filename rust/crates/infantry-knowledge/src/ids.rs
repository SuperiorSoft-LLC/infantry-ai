// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Role ids (the `RoleIds` contract), the palette a badge falls back to, initials and display names.

/// The canonical role ids, in the catalog's stable order.
pub const CANONICAL: &[&str] = &[
    "architect", "backend", "frontend", "fullstack", "qa", "devops", "security", "mobile", "pm", "marketing",
    "version-control", "seo", "localization", "ideation", "automation", "assistant",
];
/// The catch-all role every catalog carries after the canonical ones.
pub const CUSTOM: &str = "custom";
/// Retired id -> the id that replaced it (ORIGIN-SCRUB-1a).
pub const RETIRED: &[(&str, &str)] = &[
    ("git", "version-control"),
    ("i18n", "localization"),
    ("brainstormer", "ideation"),
    ("bot", "automation"),
    ("chief-of-staff", "assistant"),
];
/// The PM role id: may address other roles' notes, writes global notes by default.
pub const PM: &str = "pm";

/// The canonical ids plus `custom`.
pub fn built_in() -> impl Iterator<Item = &'static str> {
    CANONICAL.iter().copied().chain(std::iter::once(CUSTOM))
}

pub fn is_built_in(id: &str) -> bool {
    built_in().any(|b| b == id)
}

/// `[a-z][a-z0-9-]{0,31}`.
pub fn is_valid(id: &str) -> bool {
    let b = id.as_bytes();
    !b.is_empty() && b.len() <= 32 && b[0].is_ascii_lowercase() && b.iter().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || *c == b'-')
}

/// A retired id reads as the id that replaced it.
pub fn normalize(id: &str) -> String {
    RETIRED.iter().find(|(old, _)| *old == id).map(|(_, new)| new.to_string()).unwrap_or_else(|| id.to_string())
}

/// The retired names of `id`.
pub fn retired_names_of(id: &str) -> Vec<&'static str> {
    RETIRED.iter().filter(|(_, new)| *new == id).map(|(old, _)| *old).collect()
}

/// Java's `String.hashCode` (UTF-16 units, wrapping), so a generated colour is the same one the Kotlin app picked.
pub fn java_hash(s: &str) -> i32 {
    s.encode_utf16().fold(0i32, |h, c| h.wrapping_mul(31).wrapping_add(c as i32))
}

/// The palette an id with no colour of its own falls back to.
pub const FALLBACK: &[&str] = &[
    "#6366f1", "#0ea5e9", "#22c55e", "#14b8a6", "#ef4444", "#f97316", "#8b5cf6", "#eab308", "#ec4899", "#06b6d4", "#d946ef", "#64748b",
];

/// A deterministic pick from [`FALLBACK`].
pub fn color_for(role_id: &str) -> String {
    FALLBACK[java_hash(role_id).rem_euclid(FALLBACK.len() as i32) as usize].to_string()
}

/// Up to two initials, from the first and last word (`chief-of-staff` -> `CS`); `?` for a blank id.
pub fn initials(role_id: &str) -> String {
    let letters: Vec<char> = role_id
        .split(['-', '_', ' ', '.'])
        .map(str::trim)
        .filter(|w| !w.is_empty())
        .filter_map(|w| w.chars().find(|c| c.is_alphanumeric()))
        .flat_map(|c| c.to_uppercase())
        .collect();
    match letters.len() {
        0 => "?".into(),
        1 => letters[0].to_string(),
        _ => format!("{}{}", letters[0], letters[letters.len() - 1]),
    }
}

/// `chief-of-staff` -> `Chief Of Staff`.
pub fn prettify(id: &str) -> String {
    let s = id
        .split(['-', '_', ' '])
        .filter(|w| !w.is_empty())
        .map(|w| {
            let mut cs = w.chars();
            match cs.next() {
                Some(f) => f.to_uppercase().collect::<String>() + cs.as_str(),
                None => String::new(),
            }
        })
        .collect::<Vec<_>>()
        .join(" ");
    if s.trim().is_empty() {
        id.to_string()
    } else {
        s
    }
}

/// An icon key: `[a-z0-9][a-z0-9-]{0,31}`.
pub fn is_icon(s: &str) -> bool {
    let b = s.as_bytes();
    !b.is_empty() && b.len() <= 32 && (b[0].is_ascii_lowercase() || b[0].is_ascii_digit()) && b.iter().all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || *c == b'-')
}

/// A preference id (card type, tool, MCP server): `[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}`.
pub fn is_pref_id(s: &str) -> bool {
    let b = s.as_bytes();
    !b.is_empty() && b.len() <= 64 && b[0].is_ascii_alphanumeric() && b.iter().all(|c| c.is_ascii_alphanumeric() || b"_.:-".contains(c))
}

/// A module id as module.json `name` carries it: `[A-Za-z0-9][A-Za-z0-9_.-]{0,63}`.
pub fn is_module(s: &str) -> bool {
    let b = s.as_bytes();
    !b.is_empty() && b.len() <= 64 && b[0].is_ascii_alphanumeric() && b.iter().all(|c| c.is_ascii_alphanumeric() || b"_.-".contains(c))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ids_palette_initials() {
        assert!(is_valid("version-control") && !is_valid("Backend") && !is_valid("9x") && !is_valid(""));
        assert_eq!(normalize("git"), "version-control");
        assert_eq!(java_hash("a"), 97);
        assert_eq!(java_hash("ab"), 3105);
        assert_eq!(initials("chief-of-staff"), "CS");
        assert_eq!(initials("backend"), "B");
        assert_eq!(initials(""), "?");
        assert_eq!(prettify("chief-of-staff"), "Chief Of Staff");
        assert!(FALLBACK.contains(&color_for("whatever").as_str()));
    }
}
