<!-- SPDX-License-Identifier: Apache-2.0 -->
# docs/

| Path | What it is | Maintained |
|---|---|---|
| `wiki/` | The wiki: user guide, plugins, reference, contributing. Built by `mkdocs.yml` (`mkdocs build --strict -f docs/mkdocs.yml`, pinned in `requirements-wiki.txt`) and shipped in the app by the Infantry_Docs plugin. Start at [wiki/index.md](wiki/index.md). | Yes |
| `wiki/reference/` | Generated from the code by `scripts/docs-gen-reference.sh`; do not edit by hand. | Generated |
| `rust-port/LAYERS.md` | The crate layer map; must agree with `rust/crates/infantry-guards/src/layers.rs`. | Yes |
| `shared/product.md` | Product facts shared by the README and the wiki. | Yes |
| `legal/` | CLA, privacy, trademark. | Yes |
| `LICENSE`, `LICENSE-MAP.md` | The docs license and the license-split record; the current summary is [wiki/licensing.md](wiki/licensing.md). | Record |
| `launch/` | Launch copy drafts (not part of the wiki). | Owner |
| `img/` | Screenshots used by the README. | Yes |
| `os-app-release-notes.md` | Release notes for the BattalionOS app package. | Yes |
| `archive/` | Dated design records and studies, kept for history. Not in the wiki, not maintained; where one disagrees with the code, the code wins. | No |
