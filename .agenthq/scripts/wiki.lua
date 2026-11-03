-- wiki.lua: build or preview the docs/wiki site (WIKI-SITE-1). Local only, never publishes.
-- Run: agenthq script run wiki --args '{"mode":"build"}'   (mode build | serve; serve takes "port", default 8899)
-- Needs scripting.exec.enabled (+ allow python). Toolchain: docs/requirements-wiki.txt in a venv outside the repo.
-- Config is args: venv (default C:/Users/antho/agenthq-dist/wiki-venv), outRoot (default C:/Users/antho/agenthq-dist/wiki), sha8.
local a = input.args or {}
local venv = a.venv or "C:/Users/antho/agenthq-dist/wiki-venv"
local py = venv .. "/Scripts/python.exe"
local root = a.outRoot or "C:/Users/antho/agenthq-dist/wiki"
local mode = a.mode or "build"
local port = tonumber(a.port or 8899)
if port == 8797 or port == 8798 or port == 8787 then error("wiki: port " .. port .. " is reserved") end
local sha = a.sha8 or "local"
local out = root .. "/" .. sha

if not input.results then
  -- idempotent: installs/updates the pinned toolchain, then builds in strict mode (a broken link fails)
  ih.exec{command = {py, "-m", "pip", "install", "-q", "-r", "docs/requirements-wiki.txt"}, timeoutSec = 300, script = "wiki-install"}
  ih.exec{command = {py, "-m", "mkdocs", "build", "--strict", "-f", "docs/mkdocs.yml", "-d", out}, timeoutSec = 300, script = "wiki-build"}
  if mode == "serve" then
    ih.exec{command = {py, "-m", "mkdocs", "serve", "--strict", "-f", "docs/mkdocs.yml", "-a", "127.0.0.1:" .. port}, background = true, script = "wiki-serve"}
  end
  ih.again()
else
  local b = input.results[2] and input.results[2].json or {}
  if b.exitCode ~= 0 then
    ih.log("wiki build FAILED: " .. (b.stderrTail or ""):sub(-600))
  else
    ih.log("wiki built into " .. out .. (mode == "serve" and (", preview http://127.0.0.1:" .. port) or ""))
  end
end
