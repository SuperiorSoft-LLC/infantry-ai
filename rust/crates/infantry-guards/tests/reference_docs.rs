// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
//! The generated reference pages (`docs/wiki/reference/`) match the code. With `INFANTRY_DOCS_WRITE=1` the test writes
//! them instead (that is what `scripts/docs-gen-reference.sh` runs).
use infantry_guards::reference_docs::{render_all, repo_root};

#[test]
fn reference_docs_are_fresh() {
    let root = repo_root();
    let write = std::env::var("INFANTRY_DOCS_WRITE").is_ok_and(|v| v == "1");
    let mut stale = Vec::new();
    for (rel, text) in render_all(&root) {
        let path = root.join(&rel);
        if write {
            std::fs::create_dir_all(path.parent().unwrap()).unwrap();
            std::fs::write(&path, &text).unwrap();
            continue;
        }
        let on_disk = std::fs::read_to_string(&path).unwrap_or_default().replace("\r\n", "\n");
        if on_disk != text {
            stale.push(rel);
        }
    }
    assert!(stale.is_empty(), "generated reference pages are stale: {stale:?}. Run scripts/docs-gen-reference.sh and commit the result.");
}
