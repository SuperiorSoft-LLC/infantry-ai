// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
//! The commands answered with no daemon: `install-cli` / `uninstall-cli` (Kotlin `CliPathCli` over `CliPath` and
//! `UserPathStore`) and `remote on|off|status` (`<stateDir>/network.properties`, Kotlin `RemoteAccess`).
//!
//! The PATH store is the file `INFANTRY_CLI_PATH_FILE` names (CI, containers, tests), else the Windows user PATH
//! (`HKCU\Environment`, through `reg.exe` with no window), else a marked block in the POSIX shell profile.
//! Never the machine PATH.

use super::args::Parsed;
use super::{CmdResult, Command, Ctx, Getenv, Needs};
use std::path::{Path, PathBuf};

pub const CLI_PATH_USAGE: &str = "usage: infantry install-cli [--dir <launcher dir>] [--dry-run] | infantry uninstall-cli [--dir <launcher dir>] [--dry-run]";
pub const STORE_ENV: &str = "INFANTRY_CLI_PATH_FILE";
pub const LAUNCHERS: [&str; 4] = ["infantry", "infantry.bat", "infantry.cmd", "infantry.ps1"];
pub const MAIN_CLASS: &str = "com.superiorsoftllc.infantry.cli.CliMainKt";
/// The native CLI's own executable: a folder holding it is a launcher folder too.
const NATIVE_LAUNCHER: &str = if cfg!(windows) { "infantry.exe" } else { "infantry" };
const LAUNCHER_PROBE_BYTES: usize = 8_192;
pub const NEW_TERMINAL_NOTE: &str = "Open a NEW terminal: a terminal that is already open keeps the PATH it started with.";
const PATHS_ADD_CLI: &str = "paths.addCli";
const MARK_START: &str = "# >>> Infantry AI CLI (infantry install-cli) >>>";
const MARK_END: &str = "# <<< Infantry AI CLI <<<";
const NO_WINDOW: u32 = 0x0800_0000;

fn windows() -> bool {
    cfg!(windows)
}

pub fn normalize(entry: &str) -> String {
    let t = entry.trim().trim_matches('"');
    let n = t.trim_end_matches(['/', '\\']);
    if n.is_empty() { t.to_string() } else { n.to_string() }
}

pub fn same_path(a: &str, b: &str, win: bool) -> bool {
    let (x, y) = (normalize(a), normalize(b));
    if win { x.eq_ignore_ascii_case(&y) } else { x == y }
}

fn entries(value: Option<&str>, sep: char) -> Vec<String> {
    value.unwrap_or("").split(sep).map(|e| e.trim().to_string()).filter(|e| !e.is_empty()).collect()
}

/// `holdsLauncher`: a folder with a shipped launcher script that names [MAIN_CLASS] (or the native CLI executable).
pub fn holds_launcher(dir: &str) -> bool {
    let d = PathBuf::from(normalize(dir));
    if !d.is_dir() {
        return false;
    }
    let script = LAUNCHERS.iter().any(|n| {
        let f = d.join(n);
        f.is_file()
            && std::fs::read(&f).map(|b| String::from_utf8_lossy(&b[..b.len().min(LAUNCHER_PROBE_BYTES)]).contains(MAIN_CLASS)).unwrap_or(false)
    });
    script || is_native_cli(&d.join(NATIVE_LAUNCHER))
}

/// The native `infantry` executable: a file whose bytes name this CLI's contract marker.
fn is_native_cli(f: &Path) -> bool {
    f.is_file() && std::fs::read(f).map(|b| twoway(&b, b"RUST-CLI-1 infantry native launcher")).unwrap_or(false)
}

fn twoway(hay: &[u8], needle: &[u8]) -> bool {
    hay.windows(needle.len()).any(|w| w == needle)
}

/// Kept in the binary so [is_native_cli] recognises it.
#[used]
static NATIVE_MARK: &[u8] = b"RUST-CLI-1 infantry native launcher";

/// Where a PATH entry is kept (Kotlin `UserPathStore`).
pub trait PathStore {
    fn where_(&self) -> String;
    fn separator(&self) -> char;
    fn shared(&self) -> bool;
    fn windows_paths(&self) -> bool;
    fn read(&self) -> Option<String>;
    fn entries(&self) -> Vec<String> {
        entries(self.read().as_deref(), self.separator())
    }
    fn write(&self, value: &str) -> Result<(), String>;
    fn recorded(&self) -> Vec<String> {
        Vec::new()
    }
    fn record(&self, _entries: &[String]) {}
}

fn atomic_write(file: &Path, text: &str) -> Result<(), String> {
    if let Some(p) = file.parent() {
        let _ = std::fs::create_dir_all(p);
    }
    let temp = file.with_file_name(format!("{}.infantry-tmp", file.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default()));
    std::fs::write(&temp, text).map_err(|e| e.to_string())?;
    if std::fs::rename(&temp, file).is_err() {
        std::fs::write(file, text).map_err(|e| e.to_string())?;
        let _ = std::fs::remove_file(&temp);
    }
    Ok(())
}

/// `FileUserPath`: the entries in a plain file, ours recorded beside it in `<file>.ours`.
pub struct FileStore(pub PathBuf);

impl FileStore {
    fn record_file(&self) -> PathBuf {
        self.0.with_file_name(format!("{}.ours", self.0.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default()))
    }
}

impl PathStore for FileStore {
    fn where_(&self) -> String {
        self.0.to_string_lossy().to_string()
    }
    fn separator(&self) -> char {
        if windows() { ';' } else { ':' }
    }
    fn shared(&self) -> bool {
        true
    }
    fn windows_paths(&self) -> bool {
        windows()
    }
    fn read(&self) -> Option<String> {
        std::fs::read_to_string(&self.0).ok().map(|t| t.trim_end_matches(['\n', '\r']).to_string()).filter(|t| !t.trim().is_empty())
    }
    fn write(&self, value: &str) -> Result<(), String> {
        atomic_write(&self.0, value)
    }
    fn recorded(&self) -> Vec<String> {
        std::fs::read_to_string(self.record_file()).unwrap_or_default().lines().map(|l| l.trim().to_string()).filter(|l| !l.is_empty()).collect()
    }
    fn record(&self, e: &[String]) {
        if e.is_empty() {
            let _ = std::fs::remove_file(self.record_file());
        } else {
            let _ = std::fs::write(self.record_file(), e.join("\n") + "\n");
        }
    }
}

/// `RegistryUserPath`: `HKCU\Environment` value `Path`, through `reg.exe` (no window). The value type is kept.
pub struct RegistryStore;

fn reg(args: &[&str]) -> Option<String> {
    let mut c = std::process::Command::new("reg");
    c.args(args);
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        c.creation_flags(NO_WINDOW);
    }
    let o = c.output().ok()?;
    o.status.success().then(|| String::from_utf8_lossy(&o.stdout).to_string())
}

/// (type, data) of `HKCU\Environment\<name>`.
fn reg_value(name: &str) -> Option<(String, String)> {
    let out = reg(&["query", "HKCU\\Environment", "/v", name])?;
    for line in out.lines() {
        let t = line.trim_start();
        if t.len() > name.len() && t[..name.len()].eq_ignore_ascii_case(name) {
            let rest = t[name.len()..].trim_start();
            let (ty, data) = rest.split_once("    ").unwrap_or((rest, ""));
            return Some((ty.trim().to_string(), data.trim_start().to_string()));
        }
    }
    None
}

impl PathStore for RegistryStore {
    fn where_(&self) -> String {
        "your user PATH (HKCU\\Environment)".into()
    }
    fn separator(&self) -> char {
        ';'
    }
    fn shared(&self) -> bool {
        true
    }
    fn windows_paths(&self) -> bool {
        true
    }
    fn read(&self) -> Option<String> {
        reg_value("Path").map(|(_, d)| d)
    }
    fn write(&self, value: &str) -> Result<(), String> {
        if value.trim().is_empty() {
            reg(&["delete", "HKCU\\Environment", "/v", "Path", "/f"]);
            return Ok(());
        }
        let ty = match reg_value("Path") {
            Some((t, _)) if t == "REG_SZ" => "REG_SZ",
            _ => "REG_EXPAND_SZ",
        };
        reg(&["add", "HKCU\\Environment", "/v", "Path", "/t", ty, "/d", value, "/f"]).map(|_| ()).ok_or_else(|| "could not write HKCU\\Environment\\Path".into())
    }
    fn recorded(&self) -> Vec<String> {
        reg_value("InfantryCliPath").map(|(_, d)| entries(Some(&d), ';')).unwrap_or_default()
    }
    fn record(&self, e: &[String]) {
        if e.is_empty() {
            reg(&["delete", "HKCU\\Environment", "/v", "InfantryCliPath", "/f"]);
        } else {
            reg(&["add", "HKCU\\Environment", "/v", "InfantryCliPath", "/t", "REG_SZ", "/d", &e.join(";"), "/f"]);
        }
    }
}

/// `PosixProfilePath`: a marked block in the user's own shell profile.
pub struct ProfileStore(pub PathBuf);

impl ProfileStore {
    pub fn for_shell(home: &Path, shell: Option<&str>) -> ProfileStore {
        let mac = cfg!(target_os = "macos");
        let sh = shell.unwrap_or("");
        let name = if sh.ends_with("zsh") {
            ".zshrc"
        } else if sh.ends_with("bash") {
            if mac { ".bash_profile" } else { ".bashrc" }
        } else if mac {
            ".zshrc"
        } else {
            ".profile"
        };
        ProfileStore(home.join(name))
    }
    fn drop_block(text: &str) -> String {
        let mut lines: Vec<&str> = text.split('\n').collect();
        while let Some(start) = lines.iter().position(|l| l.trim() == MARK_START) {
            let end = lines[start..].iter().position(|l| l.trim() == MARK_END).map(|r| start + r).unwrap_or(lines.len() - 1);
            lines.drain(start..=end);
        }
        let j = lines.join("\n");
        let j = j.trim_end_matches('\n');
        if j.is_empty() { String::new() } else { format!("{j}\n") }
    }
}

const ESCAPED: &str = "\"\\$`";

impl PathStore for ProfileStore {
    fn where_(&self) -> String {
        self.0.to_string_lossy().to_string()
    }
    fn separator(&self) -> char {
        ':'
    }
    fn shared(&self) -> bool {
        false
    }
    fn windows_paths(&self) -> bool {
        false
    }
    fn read(&self) -> Option<String> {
        let text = std::fs::read_to_string(&self.0).ok()?;
        let lines: Vec<&str> = text.lines().collect();
        let start = lines.iter().position(|l| l.trim() == MARK_START)?;
        let end = lines[start..].iter().position(|l| l.trim() == MARK_END).map(|r| start + r).unwrap_or(lines.len() - 1);
        let line = lines[start + 1..end.max(start + 1)].iter().find(|l| l.trim_start().starts_with("export") && l.contains("PATH=\""))?;
        let v = line.split_once("PATH=\"")?.1;
        let v = &v[..v.find('"').unwrap_or(v.len())];
        let v = v.strip_suffix(":$PATH").unwrap_or(v);
        let mut out = String::new();
        let mut it = v.chars().peekable();
        while let Some(c) = it.next() {
            if c == '\\' && it.peek().is_some_and(|n| ESCAPED.contains(*n)) {
                out.push(it.next().unwrap());
            } else {
                out.push(c);
            }
        }
        (!out.trim().is_empty()).then_some(out)
    }
    fn entries(&self) -> Vec<String> {
        self.read().into_iter().collect()
    }
    fn write(&self, value: &str) -> Result<(), String> {
        let kept = Self::drop_block(&std::fs::read_to_string(&self.0).unwrap_or_default());
        let next = if value.trim().is_empty() {
            kept
        } else {
            let esc: String = value.chars().flat_map(|c| if ESCAPED.contains(c) { vec!['\\', c] } else { vec![c] }).collect();
            format!("{kept}{MARK_START}\nexport PATH=\"{esc}:$PATH\"\n{MARK_END}\n")
        };
        atomic_write(&self.0, &next)
    }
}

/// `CliPathCli.store`.
pub fn store(getenv: Getenv) -> Box<dyn PathStore> {
    if let Some(f) = getenv(STORE_ENV).map(|v| v.trim().to_string()).filter(|v| !v.is_empty()) {
        return Box::new(FileStore(PathBuf::from(f)));
    }
    if windows() {
        Box::new(RegistryStore)
    } else {
        Box::new(ProfileStore::for_shell(&super::home(), getenv("SHELL").as_deref()))
    }
}

pub struct Plan {
    pub after: Vec<String>,
    pub added: Vec<String>,
    pub removed: Vec<String>,
}

impl Plan {
    pub fn changed(&self) -> bool {
        !self.added.is_empty() || !self.removed.is_empty()
    }
}

/// `CliPath.plan`.
pub fn plan(current: &[String], launcher_dir: &str, is_ours: &dyn Fn(&str) -> bool, win: bool) -> Plan {
    let mine = normalize(launcher_dir);
    let mut after: Vec<String> = Vec::new();
    let mut removed = Vec::new();
    for e in current {
        if !is_ours(e) {
            after.push(e.clone());
        } else if same_path(e, &mine, win) {
            if after.iter().any(|a| same_path(a, &mine, win)) { removed.push(e.clone()) } else { after.push(mine.clone()) }
        } else {
            removed.push(e.clone());
        }
    }
    let added = if after.iter().any(|a| same_path(a, &mine, win)) {
        Vec::new()
    } else {
        after.push(mine.clone());
        vec![mine]
    };
    Plan { after, added, removed }
}

fn is_ours(store: &dyn PathStore, recorded: &[String], entry: &str) -> bool {
    if store.shared() { holds_launcher(entry) || recorded.iter().any(|r| same_path(r, entry, store.windows_paths())) } else { !entry.trim().is_empty() }
}

/// `CliPath.Status.line`.
pub fn status_line(store: &dyn PathStore, mine: &str, stored: &[String]) -> String {
    let win = store.windows_paths();
    let recorded = store.recorded();
    let entry = stored.iter().find(|s| same_path(s, mine, win)).or_else(|| stored.iter().find(|s| is_ours(store, &recorded, s)));
    let on_path = stored.iter().any(|s| same_path(s, mine, win));
    let live = entry.is_some_and(|e| holds_launcher(e));
    let where_ = store.where_();
    match (on_path, live) {
        (false, _) => format!("`infantry` is NOT on {where_}. Add it: infantry install-cli"),
        (true, false) => format!("`infantry` is on {where_} at {}, but that folder holds no launcher any more. Fix it: infantry install-cli", entry.map(String::as_str).unwrap_or("null")),
        _ => format!("`infantry` is on {where_} at {}", entry.map(String::as_str).unwrap_or("null")),
    }
}

/// `CliPathCli.launcherDir`.
fn launcher_dir(ctx: &mut Ctx, dir: Option<&str>, command: &str) -> Option<PathBuf> {
    if let Some(d) = dir {
        let f = PathBuf::from(super::tools_cmds::absolute(&ctx.cwd, d));
        if !holds_launcher(&f.to_string_lossy()) {
            ctx.err(format!("error: {} holds no Infantry AI launcher ({} starting {MAIN_CLASS})", f.display(), LAUNCHERS.join(", ")));
            return None;
        }
        return Some(f);
    }
    let found = ctx
        .env("INFANTRY_BIN")
        .filter(|b| !b.trim().is_empty())
        .map(|b| {
            let f = PathBuf::from(b.trim());
            if f.is_file() { f.parent().map(Path::to_path_buf).unwrap_or(f) } else { f }
        })
        .or_else(|| std::env::current_exe().ok().and_then(|e| e.parent().map(Path::to_path_buf)));
    match found {
        Some(f) if holds_launcher(&f.to_string_lossy()) => Some(f),
        _ => {
            ctx.err(format!("error: no Infantry AI launcher found from this command; name its folder: infantry {command} --dir <launcher dir>"));
            None
        }
    }
}

fn cli_path_validate(p: &Parsed, _: Getenv) -> Option<String> {
    let cmd = p.command.clone().unwrap_or_default();
    (!p.args.is_empty()).then(|| format!("{cmd} takes no arguments; {}", CLI_PATH_USAGE.trim_start_matches("usage: ")))
}

fn cli_path_run(ctx: &mut Ctx, p: &Parsed) -> CmdResult {
    let command = p.command.clone().unwrap_or_default();
    let dry = p.flag("dry-run");
    let Some(dir) = launcher_dir(ctx, p.val("dir"), &command) else { return Ok(1) };
    let st = store(ctx.getenv);
    let st = st.as_ref();
    let adding = command == "install-cli";
    let mine = normalize(&dir.to_string_lossy());
    let current = st.entries();
    let recorded = st.recorded();
    let pl = if adding {
        plan(&current, &mine, &|e| is_ours(st, &recorded, e), st.windows_paths())
    } else {
        let ours: Vec<String> = current.iter().filter(|e| is_ours(st, &recorded, e)).cloned().collect();
        Plan { after: current.iter().filter(|e| !ours.contains(e)).cloned().collect(), added: Vec::new(), removed: ours }
    };
    let written = pl.changed() && !dry;
    if written {
        if let Err(e) = st.write(&pl.after.join(&st.separator().to_string())) {
            ctx.err(format!("error: {e}"));
            return Ok(1);
        }
    }
    if !dry {
        st.record(&if adding { vec![mine.clone()] } else { Vec::new() });
    }
    for r in &pl.removed {
        ctx.out(format!("removed {r} (that folder no longer holds the launcher)"));
    }
    let where_ = st.where_();
    if !pl.changed() {
        ctx.out(if adding { format!("already on {where_}: nothing to change") } else { format!("not on {where_}: nothing to change") });
    } else if dry {
        ctx.out(format!("would write {where_} with {mine} (dry run: nothing was written)"));
    } else {
        ctx.out(format!("{} {mine} {} {where_}", if adding { "added" } else { "removed" }, if adding { "to" } else { "from" }));
    }
    ctx.out(status_line(st, &mine, if written { &pl.after } else { &current }));
    if written {
        ctx.out(NEW_TERMINAL_NOTE);
        if adding {
            ctx.out(format!("The installed app keeps this entry up to date at every start; to stop that: infantry settings set {PATHS_ADD_CLI} false"));
        }
    }
    Ok(0)
}

pub static INSTALL_CLI: Command = Command { name: "install-cli", needs: Needs::Local, validate: cli_path_validate, run: cli_path_run };
pub static UNINSTALL_CLI: Command = Command { name: "uninstall-cli", needs: Needs::Local, validate: cli_path_validate, run: cli_path_run };

// ---- remote ----

/// `remote.token`: 33 random bytes as unpadded base64url (44 characters).
const TOKEN_BYTES: usize = 33;
const MIN_TOKEN_LENGTH: usize = 32;

fn props(file: &Path) -> Vec<(String, String)> {
    std::fs::read_to_string(file)
        .unwrap_or_default()
        .lines()
        .filter(|l| !l.trim_start().starts_with('#') && !l.trim_start().starts_with('!'))
        .filter_map(|l| l.split_once(['=', ':']).map(|(k, v)| (k.trim().to_string(), v.trim().replace("\\:", ":").replace("\\\\", "\\"))))
        .collect()
}

struct Remote {
    remote: bool,
    host: Option<String>,
    port: Option<i32>,
}

fn load(dir: &Path) -> Remote {
    let p = props(&dir.join("network.properties"));
    let get = |k: &str| p.iter().rev().find(|(x, _)| x == k).map(|(_, v)| v.clone());
    Remote {
        remote: get("remote").is_some_and(|v| v.eq_ignore_ascii_case("true")),
        host: get("host").filter(|h| !h.is_empty()),
        port: get("port").and_then(|v| v.parse().ok()).filter(|p| (0..=65535).contains(p)),
    }
}

fn save(dir: &Path, r: &Remote) -> Result<(), String> {
    std::fs::create_dir_all(dir).map_err(|e| e.to_string())?;
    let esc = |v: &str| v.replace('\\', "\\\\").replace(':', "\\:");
    let mut text = String::from("#Infantry AI remote access\n");
    text.push_str(&format!("#{}\n", chrono::Utc::now().format("%a %b %d %H:%M:%S UTC %Y")));
    if let Some(h) = &r.host {
        text.push_str(&format!("host={}\n", esc(h)));
    }
    if let Some(p) = r.port {
        text.push_str(&format!("port={p}\n"));
    }
    text.push_str(&format!("remote={}\n", r.remote));
    std::fs::write(dir.join("network.properties"), text).map_err(|e| e.to_string())
}

fn ensure_token(dir: &Path) -> Result<String, String> {
    let f = dir.join("remote.token");
    if let Some(t) = std::fs::read_to_string(&f).ok().map(|t| t.trim().to_string()).filter(|t| t.len() >= MIN_TOKEN_LENGTH) {
        return Ok(t);
    }
    use base64::Engine;
    let mut bytes = Vec::with_capacity(TOKEN_BYTES + 16);
    while bytes.len() < TOKEN_BYTES {
        bytes.extend_from_slice(uuid::Uuid::new_v4().as_bytes());
    }
    bytes.truncate(TOKEN_BYTES);
    let token = base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(&bytes);
    std::fs::create_dir_all(dir).map_err(|e| e.to_string())?;
    std::fs::write(&f, &token).map_err(|e| e.to_string())?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = std::fs::set_permissions(&f, std::fs::Permissions::from_mode(0o600));
    }
    Ok(token)
}

fn remote_validate(_: &Parsed, _: Getenv) -> Option<String> {
    None
}

fn remote_run(ctx: &mut Ctx, p: &Parsed) -> CmdResult {
    let dir = ctx.state_dir();
    let action = p.args.first().map(|a| a.to_lowercase());
    let Some(action) = action.filter(|a| matches!(a.as_str(), "on" | "off" | "status")) else {
        ctx.err("error: usage: infantry remote on|off|status");
        return Ok(2);
    };
    let current = load(&dir);
    if action != "status" {
        save(&dir, &Remote { remote: action == "on", ..current })?;
    }
    let s = load(&dir);
    ctx.out(format!("remote access: {}", if s.remote { "ON" } else { "OFF" }));
    if !s.remote {
        ctx.out("The daemon listens on loopback only.");
    } else {
        let token = ensure_token(&dir)?;
        let port = s.port.or_else(|| ctx.env("INFANTRY_PORT").and_then(|v| v.trim().parse().ok())).unwrap_or(super::RUST_DEFAULT_PORT);
        let address = s.host.filter(|h| !h.starts_with("0.")).unwrap_or_else(|| "<tailscale-ip>".into());
        ctx.out(format!("address: ws://{address}:{port}/ws"));
        ctx.out(format!("token:   {token}"));
        ctx.out(format!("Allow inbound TCP {port} from 100.64.0.0/10 in the firewall. The token and traffic are cleartext ws://."));
    }
    if action != "status" {
        ctx.out("Restart the app/daemon for this to take effect (it was not restarted).");
    }
    Ok(0)
}

pub static REMOTE: Command = Command { name: "remote", needs: Needs::Local, validate: remote_validate, run: remote_run };

#[cfg(test)]
mod tests {
    use super::super::BufIo;
    use super::*;

    fn tmp(tag: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("infantry-cli-{tag}-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&d).unwrap();
        d
    }

    #[test]
    fn install_cli_dry_run_writes_nothing() {
        let root = tmp("clipath");
        let bin = root.join("bin");
        std::fs::create_dir_all(&bin).unwrap();
        std::fs::write(bin.join("infantry.cmd"), format!("@java -cp x {MAIN_CLASS} %*")).unwrap();
        let store_file = root.join("path.txt");
        std::fs::write(&store_file, "C:\\other").unwrap();
        let (sf, b) = (store_file.to_string_lossy().to_string(), bin.to_string_lossy().to_string());
        let env = move |k: &str| (k == STORE_ENV).then(|| sf.clone());
        let mut io = BufIo::default();
        let argv: Vec<String> = ["install-cli", "--dir", &b, "--dry-run"].iter().map(|s| s.to_string()).collect();
        let code = super::super::run(&argv, &mut io, &env, None);
        assert_eq!(code, 0, "{:?}", io.err);
        assert_eq!(io.out[0], format!("would write {} with {} (dry run: nothing was written)", store_file.display(), b));
        assert_eq!(io.out[1], format!("`infantry` is NOT on {}. Add it: infantry install-cli", store_file.display()));
        assert_eq!(std::fs::read_to_string(&store_file).unwrap(), "C:\\other");
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn remote_on_off_in_a_temp_state_dir() {
        let dir = tmp("remote");
        let d = dir.to_string_lossy().to_string();
        let env = move |k: &str| (k == "INFANTRY_STATE_DIR").then(|| d.clone());
        let mut io = BufIo::default();
        let code = super::super::run(&["remote".to_string(), "off".to_string()], &mut io, &env, None);
        assert_eq!(code, 0);
        assert_eq!(io.out, vec!["remote access: OFF", "The daemon listens on loopback only.", "Restart the app/daemon for this to take effect (it was not restarted)."]);
        let mut io = BufIo::default();
        assert_eq!(super::super::run(&["remote".to_string(), "sideways".to_string()], &mut io, &env, None), 2);
        assert_eq!(io.err, vec!["error: usage: infantry remote on|off|status"]);
        let _ = std::fs::remove_dir_all(&dir);
    }
}
