// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! A FRESH state dir's database: when `infantry.db` holds no table at all, the full schema is created (tables first,
//! then indexes, triggers and views) and stamped with its version, as the Kotlin daemon's `Schema.create` does. A
//! database that already has tables (a migrated copy, an older daemon's) is never touched here.
use infantry_core::db::Db;

include!(concat!(env!("OUT_DIR"), "/schema_sources.rs"));

/// Creates the schema when the database is empty. True = it was created.
pub fn ensure(db: &Db) -> Result<bool, String> {
    db.write(|c| {
        let tables: i64 = c.query_row("SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'", [], |r| r.get(0))?;
        if tables > 0 {
            return Ok(false);
        }
        let (mut first, mut rest) = (Vec::new(), Vec::new());
        for (_, text) in SCHEMA_SOURCES {
            for s in infantry_board::schema::schema_statements(text) {
                if s.to_uppercase().starts_with("CREATE TABLE") {
                    first.push(s);
                } else {
                    rest.push(s);
                }
            }
        }
        let tx = c.transaction()?;
        for s in first.iter().chain(rest.iter()) {
            tx.execute_batch(s)?;
        }
        tx.execute_batch(&format!("PRAGMA user_version = {SCHEMA_VERSION};"))?;
        tx.commit()?;
        Ok(true)
    })
    .map_err(|e| format!("creating the schema: {e}"))
}
