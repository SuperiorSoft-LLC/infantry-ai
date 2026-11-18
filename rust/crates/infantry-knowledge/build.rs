// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! Embeds the bundled role catalog and avatar set (the Kotlin Infantry_Roles resources, the spec) into the crate,
//! as the Kotlin jar bundled them on its classpath: `roles/*` and `avatars/*`, path -> bytes.
use std::{env, fs, path::Path};

fn main() {
    let res = Path::new(&env::var("CARGO_MANIFEST_DIR").unwrap()).join("../../../plugins/official/Infantry_Roles/daemon/src/main/resources");
    println!("cargo:rerun-if-changed={}", res.display());
    let mut out = String::from("pub static RESOURCES: &[(&str, &[u8])] = &[\n");
    for dir in ["roles", "avatars"] {
        let mut names: Vec<String> = fs::read_dir(res.join(dir))
            .map(|d| d.flatten().filter(|e| e.path().is_file()).filter_map(|e| e.file_name().to_str().map(String::from)).collect())
            .unwrap_or_default();
        names.sort();
        for n in names {
            let p = res.join(dir).join(&n).canonicalize().unwrap();
            let p = p.display().to_string().trim_start_matches(r"\\?\").replace('\\', "/");
            out.push_str(&format!("    ({:?}, include_bytes!({p:?})),\n", format!("/{dir}/{n}")));
        }
    }
    out.push_str("];\n");
    fs::write(Path::new(&env::var("OUT_DIR").unwrap()).join("resources.rs"), out).unwrap();
}
