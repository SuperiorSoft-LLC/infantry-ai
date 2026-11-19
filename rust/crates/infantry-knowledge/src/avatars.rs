// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Worker avatars: the bundled manifest (`/avatars/index.json`), owner overrides (`<dir>/<id>.png|webp`) and the
//! resolution override -> bundled -> generated (role colour + initials), per role and per agent.
//!
//! Kotlin resolved these client side; here the daemon serves them so every client (web, desktop, phone) sees the
//! same picture: an [`AvatarRef`] rides on each role in `roles`, and the bytes are served at [`URL_PREFIX`]`<id>`.
use std::collections::BTreeMap;
use std::fs;
use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use crate::ids;
use crate::roles::resource;

/// The HTTP path the image bytes are served at: `GET /avatars/<roleId-or-agentId>` (png or webp).
pub const URL_PREFIX: &str = "/avatars/";
pub const MANIFEST_PATH: &str = "/avatars/index.json";
/// `AvatarUploads`: the biggest upload accepted, in bytes, and the biggest side, in pixels.
pub const MAX_BYTES: usize = 256 * 1024;
pub const MAX_SIDE: u32 = 512;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Format {
    Png,
    Webp,
}

impl Format {
    pub const ALL: [Format; 2] = [Format::Png, Format::Webp];
    pub fn ext(self) -> &'static str {
        match self {
            Format::Png => "png",
            Format::Webp => "webp",
        }
    }
    pub fn media_type(self) -> &'static str {
        match self {
            Format::Png => "image/png",
            Format::Webp => "image/webp",
        }
    }
    pub fn from_name(name: &str) -> Option<Format> {
        let l = name.to_lowercase();
        if l.ends_with(".png") {
            Some(Format::Png)
        } else if l.ends_with(".webp") {
            Some(Format::Webp)
        } else {
            None
        }
    }
}

/// One bundled asset.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct AvatarAsset {
    pub file: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub source: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub licence: Option<String>,
}

/// Role id -> bundled asset. `{}` / blank is the empty manifest; a bad key or file is an error.
pub fn parse_manifest(text: &str) -> Result<BTreeMap<String, AvatarAsset>, String> {
    let body = text.trim_start_matches('\u{feff}').trim();
    if body.is_empty() || body == "{}" {
        return Ok(BTreeMap::new());
    }
    let m: BTreeMap<String, AvatarAsset> = serde_json::from_str(body).map_err(|e| format!("{MANIFEST_PATH} is not a valid avatar manifest: {e}"))?;
    for (id, a) in &m {
        if !ids::is_valid(id) {
            return Err(format!("{MANIFEST_PATH} maps role id '{id}': a role id is a lowercase letter then up to 31 lowercase letters, digits or '-'"));
        }
        if a.file.trim().is_empty() {
            return Err(format!("{MANIFEST_PATH}: role '{id}' has no 'file'"));
        }
        if Format::from_name(&a.file).is_none() {
            return Err(format!("{MANIFEST_PATH}: role '{id}' names '{}': only .png and .webp assets are supported", a.file));
        }
    }
    Ok(m)
}

/// Which tier an avatar came from.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Tier {
    Override,
    Bundled,
    Generated,
}

/// The avatar reference a role (or agent) carries on the wire. `url` is set when there are bytes to fetch;
/// `color` + `initials` always are, so a client can paint the generated badge without another call.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AvatarRef {
    /// The id it resolved for (the agent id when an agent override won, else the role id).
    pub id: String,
    pub tier: Tier,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub url: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub media_type: Option<String>,
    pub color: String,
    pub initials: String,
    /// Changes when the picture does (bytes length + mtime), so a client can cache by `url?v=<version>`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub version: Option<String>,
}

/// A validated upload.
#[derive(Debug, Clone)]
pub struct Upload {
    pub format: Format,
    pub bytes: Vec<u8>,
    pub width: u32,
    pub height: u32,
}

/// `AvatarUploads.validate`: a PNG or WEBP of at most [`MAX_BYTES`] and [`MAX_SIDE`] px a side.
pub fn validate(bytes: Vec<u8>) -> Result<Upload, String> {
    if bytes.is_empty() {
        return Err("the image is empty".into());
    }
    if bytes.len() > MAX_BYTES {
        return Err(format!("the image is {} KB; the limit is {} KB", bytes.len().div_ceil(1024), MAX_BYTES / 1024));
    }
    let (format, w, h) = if bytes.starts_with(&[0x89, b'P', b'N', b'G', 0x0D, 0x0A, 0x1A, 0x0A]) {
        if bytes.len() < 24 || &bytes[12..16] != b"IHDR" {
            return Err("the PNG has no IHDR header".into());
        }
        let be = |i: usize| u32::from_be_bytes([bytes[i], bytes[i + 1], bytes[i + 2], bytes[i + 3]]);
        (Format::Png, be(16), be(20))
    } else if bytes.len() >= 30 && &bytes[0..4] == b"RIFF" && &bytes[8..12] == b"WEBP" {
        let le24 = |i: usize| u32::from(bytes[i]) | u32::from(bytes[i + 1]) << 8 | u32::from(bytes[i + 2]) << 16;
        match &bytes[12..16] {
            b"VP8X" => (Format::Webp, le24(24) + 1, le24(27) + 1),
            b"VP8L" => {
                let b = &bytes[21..25];
                let bits = u32::from_le_bytes([b[0], b[1], b[2], b[3]]);
                (Format::Webp, (bits & 0x3FFF) + 1, ((bits >> 14) & 0x3FFF) + 1)
            }
            b"VP8 " => {
                let le16 = |i: usize| u32::from(bytes[i]) | u32::from(bytes[i + 1]) << 8;
                (Format::Webp, le16(26) & 0x3FFF, le16(28) & 0x3FFF)
            }
            _ => return Err("the WEBP has an unknown chunk".into()),
        }
    } else {
        return Err("only PNG and WEBP images are accepted".into());
    };
    if w == 0 || h == 0 {
        return Err("the image has no size".into());
    }
    if w > MAX_SIDE || h > MAX_SIDE {
        return Err(format!("the image is {w}x{h}; the limit is {MAX_SIDE}x{MAX_SIDE}"));
    }
    Ok(Upload { format, bytes, width: w, height: h })
}

/// The resolver: overrides in `dir` (`<id>.png` before `<id>.webp`), then the bundled manifest, then generated.
pub struct Avatars {
    dir: PathBuf,
    manifest: BTreeMap<String, AvatarAsset>,
    read_asset: Box<dyn Fn(&str) -> Option<Vec<u8>> + Send + Sync>,
}

/// An avatar's bytes, ready to serve.
pub struct Image {
    pub media_type: &'static str,
    pub bytes: Vec<u8>,
}

impl Avatars {
    /// Overrides in `dir`, the manifest and images this build bundles.
    pub fn new(dir: impl Into<PathBuf>) -> Avatars {
        let manifest = resource(MANIFEST_PATH).and_then(|b| parse_manifest(&String::from_utf8_lossy(b)).ok()).unwrap_or_default();
        Avatars { dir: dir.into(), manifest, read_asset: Box::new(|p| resource(p).map(<[u8]>::to_vec)) }
    }

    pub fn with_manifest(dir: impl Into<PathBuf>, manifest: BTreeMap<String, AvatarAsset>, read_asset: impl Fn(&str) -> Option<Vec<u8>> + Send + Sync + 'static) -> Avatars {
        Avatars { dir: dir.into(), manifest, read_asset: Box::new(read_asset) }
    }

    pub fn dir(&self) -> &Path {
        &self.dir
    }

    pub fn manifest(&self) -> &BTreeMap<String, AvatarAsset> {
        &self.manifest
    }

    fn candidate(&self, id: &str, f: Format) -> Option<PathBuf> {
        ids::is_valid(id).then(|| self.dir.join(format!("{id}.{}", f.ext())))
    }

    fn own(&self, id: &str) -> Option<(PathBuf, Format)> {
        Format::ALL.into_iter().find_map(|f| self.candidate(id, f).filter(|p| p.is_file()).map(|p| (p, f)))
    }

    /// The override file of `id` (also under its retired / current names; the id's own file wins).
    pub fn find_override(&self, id: &str) -> Option<(PathBuf, Format)> {
        if !ids::is_valid(id) {
            return None;
        }
        let current = ids::normalize(id);
        let mut names = vec![id.to_string(), current.clone()];
        names.extend(ids::retired_names_of(&current).into_iter().map(String::from));
        names.dedup();
        names.iter().find_map(|n| self.own(n))
    }

    fn bundled(&self, role_id: &str) -> Option<(Format, Vec<u8>)> {
        let a = self.manifest.get(role_id).or_else(|| self.manifest.get(&ids::normalize(role_id)))?;
        let f = Format::from_name(&a.file)?;
        (self.read_asset)(&format!("{URL_PREFIX}{}", a.file)).map(|b| (f, b))
    }

    /// The avatar of `role_id`; with `agent_id`, an agent override wins over the role's.
    pub fn resolve(&self, role_id: &str, agent_id: Option<&str>) -> AvatarRef {
        let generated = |id: &str| AvatarRef { id: id.into(), tier: Tier::Generated, url: None, media_type: None, color: ids::color_for(id), initials: ids::initials(id), version: None };
        let base = generated(role_id);
        if let Some(agent) = agent_id.filter(|a| ids::is_valid(a)) {
            if let Some((p, f)) = self.own(agent) {
                return AvatarRef { id: agent.into(), tier: Tier::Override, url: Some(format!("{URL_PREFIX}{agent}")), media_type: Some(f.media_type().into()), version: version(&p), ..base };
            }
        }
        if let Some((p, f)) = self.find_override(role_id) {
            return AvatarRef { tier: Tier::Override, url: Some(format!("{URL_PREFIX}{role_id}")), media_type: Some(f.media_type().into()), version: version(&p), ..base };
        }
        if let Some((f, b)) = self.bundled(role_id) {
            return AvatarRef { tier: Tier::Bundled, url: Some(format!("{URL_PREFIX}{role_id}")), media_type: Some(f.media_type().into()), version: Some(format!("b{}", b.len())), ..base };
        }
        base
    }

    /// The bytes served at `GET /avatars/<id>`: the override, else the bundled asset. None = 404 (paint generated).
    pub fn image(&self, id: &str) -> Option<Image> {
        let id = id.trim_end_matches(".png").trim_end_matches(".webp");
        if let Some((p, f)) = self.find_override(id) {
            return fs::read(p).ok().map(|bytes| Image { media_type: f.media_type(), bytes });
        }
        self.bundled(id).map(|(f, bytes)| Image { media_type: f.media_type(), bytes })
    }

    /// Stores a validated upload as the override of `id` (a role or agent id), dropping the other format.
    pub fn store(&self, id: &str, upload: &Upload) -> Result<PathBuf, String> {
        let target = self.candidate(id, upload.format).ok_or_else(|| format!("'{}' is not a role id", crate::roles::take(id, 40)))?;
        fs::create_dir_all(&self.dir).map_err(|e| e.to_string())?;
        for f in Format::ALL.into_iter().filter(|f| *f != upload.format) {
            if let Some(p) = self.candidate(id, f) {
                let _ = fs::remove_file(p);
            }
        }
        let tmp = self.dir.join(format!(".{id}.{}.tmp", upload.format.ext()));
        fs::write(&tmp, &upload.bytes).map_err(|e| e.to_string())?;
        fs::rename(&tmp, &target).map_err(|e| e.to_string())?;
        Ok(target)
    }

    /// Deletes the override of `id` in every format; true when something was removed.
    pub fn clear(&self, id: &str) -> bool {
        let mut removed = false;
        for f in Format::ALL {
            if let Some(p) = self.candidate(id, f).filter(|p| p.is_file()) {
                removed |= fs::remove_file(p).is_ok();
            }
        }
        removed
    }
}

fn version(p: &Path) -> Option<String> {
    let m = fs::metadata(p).ok()?;
    let t = m.modified().ok()?.duration_since(std::time::UNIX_EPOCH).ok()?.as_millis();
    Some(format!("{}-{t}", m.len()))
}

/// The axum router serving `GET /avatars/{id}` (the composition root merges it under its auth layer).
pub fn router(avatars: std::sync::Arc<Avatars>) -> axum::Router {
    use axum::extract::{Path as P, State};
    use axum::http::{header, StatusCode};
    use axum::response::{IntoResponse, Response};
    async fn get(State(a): State<std::sync::Arc<Avatars>>, P(id): P<String>) -> Response {
        match a.image(&id) {
            Some(img) => ([(header::CONTENT_TYPE, img.media_type), (header::CACHE_CONTROL, "no-cache")], img.bytes).into_response(),
            None => StatusCode::NOT_FOUND.into_response(),
        }
    }
    axum::Router::new().route("/avatars/{id}", axum::routing::get(get)).with_state(avatars)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn png(w: u32, h: u32) -> Vec<u8> {
        let mut b = vec![0x89, b'P', b'N', b'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13];
        b.extend_from_slice(b"IHDR");
        b.extend_from_slice(&w.to_be_bytes());
        b.extend_from_slice(&h.to_be_bytes());
        b.extend_from_slice(&[8, 6, 0, 0, 0]);
        b
    }

    #[test]
    fn tiers_and_uploads() {
        let dir = tempfile::tempdir().unwrap();
        let m = parse_manifest(r#"{"qa":{"file":"qa.png"}}"#).unwrap();
        let a = Avatars::with_manifest(dir.path(), m, |p| (p == "/avatars/qa.png").then(|| png(8, 8)));
        assert_eq!(a.resolve("backend", None).tier, Tier::Generated);
        assert_eq!(a.resolve("qa", None).tier, Tier::Bundled);
        assert!(validate(png(600, 10)).is_err());
        assert!(validate(b"GIF89a".to_vec()).is_err());
        let up = validate(png(64, 64)).unwrap();
        a.store("version-control", &up).unwrap();
        let r = a.resolve("git", None);
        assert_eq!((r.tier, r.url.as_deref()), (Tier::Override, Some("/avatars/git")), "a retired id finds the new id's override");
        a.store("ag-1", &up).unwrap();
        assert_eq!(a.resolve("backend", Some("ag-1")).id, "ag-1");
        assert!(a.image("qa").is_some() && a.image("backend").is_none());
        assert!(a.clear("ag-1") && !a.clear("ag-1"));
        assert!(parse_manifest(r#"{"Bad":{"file":"x.png"}}"#).is_err());
    }
}
