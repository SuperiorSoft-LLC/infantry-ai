// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! `cli_locations` / `cli_location_set` (CLIPATH-1): where each harness CLI runs from, and the user's override of it, per
//! harness. The CLI list and the default lookup are the installer's ([`HarnessInstall`]), so this unit names no harness.
//! Overrides are durable in the `settings` table under `cli.executable.<id>` (a JSON string; `""` = cleared), the rows
//! the Kotlin daemon wrote. A set path must be an existing file; a bad one is refused and nothing is stored.
use super::harness_install::HarnessInstall;
use infantry_core::db::Db;
use infantry_core::frames::{Envelope, FrameCtx, FrameUnit, Refusal};
use infantry_protocol::frames as f;
use serde_json::{json, Value};
use std::path::Path;
use std::sync::Arc;

pub const KEY_PREFIX: &str = "cli.executable.";

/// The overrides, read from and written to the daemon's `settings` table.
pub struct DbOverrides(pub Arc<Db>);

impl DbOverrides {
    pub fn get(&self, id: &str) -> Option<String> {
        let key = format!("{KEY_PREFIX}{id}");
        let raw: Option<String> = self
            .0
            .read(|c| c.query_row("SELECT value FROM settings WHERE key = ?1", [&key], |r| r.get::<_, String>(0)).map(Some).or_else(|e| if matches!(e, rusqlite::Error::QueryReturnedNoRows) { Ok(None) } else { Err(e) }))
            .ok()
            .flatten();
        match serde_json::from_str::<Value>(&raw?).ok()? {
            Value::String(s) => Some(s.trim().to_string()).filter(|s| !s.is_empty()),
            _ => None,
        }
    }

    pub fn set(&self, id: &str, path: Option<&str>) -> Result<(), String> {
        let key = format!("{KEY_PREFIX}{id}");
        let value = Value::String(path.map(str::trim).unwrap_or_default().to_string()).to_string();
        let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0);
        self.0
            .write(|c| {
                c.execute(
                    "INSERT INTO settings(key, value, updated_at) VALUES (?1, ?2, ?3) ON CONFLICT(key) DO UPDATE SET value = excluded.value, updated_at = excluded.updated_at",
                    rusqlite::params![key, value, now],
                )
            })
            .map(|_| ())
            .map_err(|e| format!("could not store the location: {e}"))
    }
}

pub struct CliLocations {
    overrides: DbOverrides,
    install: Arc<HarnessInstall>,
}

impl CliLocations {
    pub fn new(db: Arc<Db>, install: Arc<HarnessInstall>) -> Arc<CliLocations> {
        Arc::new(CliLocations { overrides: DbOverrides(db), install })
    }

    /// The `cli_locations` body: `{tools: [{id, label, path?, source?, override?, reason?}]}`; source is `override`,
    /// `path` (the user's own install) or `app` (the app's private copy).
    pub fn locations(&self) -> Value {
        let tools: Vec<Value> = HarnessInstall::clis()
            .into_iter()
            .map(|(id, label)| {
                let mut v = json!({"id": id, "label": label});
                let own = self.overrides.get(id);
                match own.as_deref() {
                    Some(o) if Path::new(o).is_file() => {
                        v["path"] = json!(o);
                        v["source"] = json!("override");
                    }
                    _ => match self.install.located(id) {
                        Some((p, src)) => {
                            v["path"] = json!(p.to_string_lossy());
                            v["source"] = json!(src);
                        }
                        None => v["reason"] = json!(format!("{label} not found")),
                    },
                }
                if let Some(o) = own {
                    if !Path::new(&o).is_file() {
                        v["reason"] = json!(format!("the override '{o}' no longer exists"));
                    }
                    v["override"] = json!(o);
                }
                v
            })
            .collect();
        json!({ "tools": tools })
    }

    /// Stores `path` as harness `id`'s override, or clears it when None/blank.
    pub fn set(&self, id: &str, path: Option<&str>) -> Result<Value, String> {
        let clis = HarnessInstall::clis();
        let (_, label) = clis
            .iter()
            .find(|c| c.0 == id)
            .ok_or_else(|| format!("unknown CLI '{id}' (known: {})", clis.iter().map(|c| c.0).collect::<Vec<_>>().join(", ")))?;
        let trimmed = path.map(str::trim).filter(|p| !p.is_empty());
        if let Some(p) = trimmed {
            if !Path::new(p).is_file() {
                return Err(format!("{label}: '{p}' does not exist or is not a file"));
            }
        }
        self.overrides.set(id, trimmed)?;
        Ok(self.locations())
    }
}

impl FrameUnit for CliLocations {
    fn name(&self) -> &'static str {
        "cli-locations"
    }

    fn frames(&self) -> &'static [&'static str] {
        &[f::CLI_LOCATIONS, f::CLI_LOCATION_SET]
    }

    fn handle(&self, env: &Envelope, _ctx: &FrameCtx) -> Result<Vec<Envelope>, Refusal> {
        let ty = env.r#type.as_str();
        let body = match ty {
            f::CLI_LOCATIONS => self.locations(),
            _ => {
                let d: Value = if env.data.trim().is_empty() {
                    json!({})
                } else {
                    serde_json::from_str(&env.data).map_err(|e| Refusal::new(ty, format!("the payload is not valid JSON ({e})")))?
                };
                let id = d["id"].as_str().ok_or_else(|| Refusal::new(ty, "an id is required"))?;
                self.set(id, d["path"].as_str()).map_err(|e| Refusal::new(ty, e))?
            }
        };
        let mut e = Envelope::new(f::CLI_LOCATIONS, body.to_string());
        e.request_id = env.request_id.clone();
        e.session_id = env.session_id.clone();
        Ok(vec![e])
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ask(u: &CliLocations, ty: &str, data: &str) -> Result<Vec<Envelope>, Refusal> {
        u.handle(&Envelope::new(ty, data), &FrameCtx { conn_id: 1, owner: true, bus: infantry_core::bus::EventBus::new(4) })
    }

    #[test]
    fn locations_list_and_a_set_location_is_validated_and_persists_per_harness() {
        let dir = std::env::temp_dir().join(format!("cl-loc-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&dir).unwrap();
        let exe = dir.join("my-cli.exe");
        std::fs::write(&exe, b"x").unwrap();
        let exe = exe.to_string_lossy().into_owned();
        let db = Arc::new(Db::open(dir.join("infantry.db")).unwrap());
        db.write(|c| c.execute_batch("CREATE TABLE IF NOT EXISTS settings (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL, updated_at INTEGER NOT NULL)")).unwrap();
        let install = HarnessInstall::new(&dir, Arc::new(|| {}), Arc::new(|| {}));
        let u = CliLocations::new(db.clone(), install.clone());
        let (first, _) = HarnessInstall::clis()[0];
        let (second, _) = HarnessInstall::clis()[1];

        let l: Value = serde_json::from_str(&ask(&u, "cli_locations", "").unwrap()[0].data).unwrap();
        assert_eq!(l["tools"].as_array().unwrap().len(), HarnessInstall::clis().len());
        assert_eq!(l["tools"][0]["id"], first);

        // A bad path or an unknown CLI is refused and nothing is stored.
        let e = ask(&u, "cli_location_set", &json!({"id": first, "path": "/nope/cli"}).to_string()).unwrap_err();
        assert!(e.0.contains("'/nope/cli' does not exist"), "{}", e.0);
        assert!(ask(&u, "cli_location_set", &json!({"id": "bogus", "path": exe}).to_string()).unwrap_err().0.contains("unknown CLI 'bogus'"));
        assert!(ask(&u, "cli_location_set", r#"{"path":"x"}"#).unwrap_err().0.contains("an id is required"));
        assert!(DbOverrides(db.clone()).get(first).is_none());

        // A good path is stored for that harness only; a fresh unit over the same db reads it back.
        let out = ask(&u, "cli_location_set", &json!({"id": first, "path": exe}).to_string()).unwrap();
        assert_eq!(out[0].r#type, "cli_locations");
        let l: Value = serde_json::from_str(&out[0].data).unwrap();
        assert_eq!((l["tools"][0]["source"].as_str(), l["tools"][0]["path"].as_str(), l["tools"][0]["override"].as_str()), (Some("override"), Some(exe.as_str()), Some(exe.as_str())));
        assert_eq!(CliLocations::new(db.clone(), install).locations()["tools"][0]["override"], exe.as_str());
        assert!(DbOverrides(db.clone()).get(second).is_none());

        // Blank clears it.
        let l: Value = serde_json::from_str(&ask(&u, "cli_location_set", &json!({"id": first, "path": ""}).to_string()).unwrap()[0].data).unwrap();
        assert!(l["tools"][0]["override"].is_null());
        assert!(DbOverrides(db).get(first).is_none());
        let _ = std::fs::remove_dir_all(dir);
    }
}
