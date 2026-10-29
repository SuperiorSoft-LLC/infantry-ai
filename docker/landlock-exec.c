/*
 * landlock-exec: run one command confined by Linux Landlock (unprivileged, kernel >= 5.13, inherited by every child).
 *
 *   landlock-exec [--ro PATH]... [--rw PATH]... [--rwfile PATH]... [--connect PORT]... -- COMMAND [ARGS...]
 *
 *   --ro PATH      read + execute under PATH (a directory or a file)
 *   --rw PATH      read + execute + write/create/delete under PATH (a directory or a file)
 *   --net best-effort  on a kernel without Landlock network rules, warn and run with TCP unrestricted instead of refusing
 *   --connect PORT allow outgoing TCP connections to PORT; when at least one is given, every other TCP connect is
 *                  refused (Landlock ABI >= 4, kernel 6.7+; on an older kernel the option is an error, never ignored)
 *
 * Anything not listed is out of reach: no read, no write, no execute. FAILS CLOSED: if Landlock is missing or a rule
 * cannot be added, the command is NOT run (exit 126). Sets no_new_privs (required by Landlock without CAP_SYS_ADMIN).
 * Built in the image (docker/Dockerfile) to /usr/local/bin/landlock-exec; the daemon wraps every agent CLI launch with it
 * when limits.sandbox.mode = 1 (runner/Sandbox.kt).
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <linux/landlock.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

#ifndef LANDLOCK_ACCESS_FS_REFER
#define LANDLOCK_ACCESS_FS_REFER (1ULL << 13)
#endif
#ifndef LANDLOCK_ACCESS_FS_TRUNCATE
#define LANDLOCK_ACCESS_FS_TRUNCATE (1ULL << 14)
#endif
#ifndef LANDLOCK_ACCESS_FS_IOCTL_DEV
#define LANDLOCK_ACCESS_FS_IOCTL_DEV (1ULL << 15)
#endif
/* ABI 4 (network) structures, defined here so older headers still build. */
#define LL_ACCESS_NET_BIND_TCP (1ULL << 0)
#define LL_ACCESS_NET_CONNECT_TCP (1ULL << 1)
#define LL_RULE_NET_PORT 2
struct ll_ruleset_attr { uint64_t handled_access_fs; uint64_t handled_access_net; };
struct ll_net_port_attr { uint64_t allowed_access; uint64_t port; } __attribute__((packed));

#define FS_READ (LANDLOCK_ACCESS_FS_EXECUTE | LANDLOCK_ACCESS_FS_READ_FILE | LANDLOCK_ACCESS_FS_READ_DIR)
#define FS_WRITE_V1 (LANDLOCK_ACCESS_FS_WRITE_FILE | LANDLOCK_ACCESS_FS_REMOVE_DIR | LANDLOCK_ACCESS_FS_REMOVE_FILE | \
    LANDLOCK_ACCESS_FS_MAKE_CHAR | LANDLOCK_ACCESS_FS_MAKE_DIR | LANDLOCK_ACCESS_FS_MAKE_REG | \
    LANDLOCK_ACCESS_FS_MAKE_SOCK | LANDLOCK_ACCESS_FS_MAKE_FIFO | LANDLOCK_ACCESS_FS_MAKE_BLOCK | LANDLOCK_ACCESS_FS_MAKE_SYM)
#define FS_FILE_ONLY (LANDLOCK_ACCESS_FS_EXECUTE | LANDLOCK_ACCESS_FS_WRITE_FILE | LANDLOCK_ACCESS_FS_READ_FILE | \
    LANDLOCK_ACCESS_FS_TRUNCATE | LANDLOCK_ACCESS_FS_IOCTL_DEV)

static void die(const char *what, const char *arg) {
    fprintf(stderr, "landlock-exec: %s%s%s: %s (refusing to run unconfined)\n", what, arg ? " " : "", arg ? arg : "", strerror(errno));
    exit(126);
}

int main(int argc, char **argv) {
    int abi = (int)syscall(SYS_landlock_create_ruleset, NULL, 0, LANDLOCK_CREATE_RULESET_VERSION);
    if (abi < 1) die("Landlock is not available", NULL);
    uint64_t handled = FS_READ | FS_WRITE_V1;
    if (abi >= 2) handled |= LANDLOCK_ACCESS_FS_REFER;
    if (abi >= 3) handled |= LANDLOCK_ACCESS_FS_TRUNCATE;
    if (abi >= 5) handled |= LANDLOCK_ACCESS_FS_IOCTL_DEV;

    int cmd = -1, ports = 0, best_effort = 0;
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--") == 0) { cmd = i + 1; break; }
        if (strcmp(argv[i], "--connect") == 0) ports++;
        if (strcmp(argv[i], "--net") == 0 && i + 1 < argc && strcmp(argv[i + 1], "best-effort") == 0) best_effort = 1;
    }
    if (cmd < 0 || cmd >= argc) { fprintf(stderr, "usage: landlock-exec [--ro P] [--rw P] [--connect PORT] -- CMD...\n"); return 2; }
    if (ports > 0 && abi < 4) {
        if (!best_effort) { errno = ENOSYS; die("--connect needs Landlock ABI 4 (kernel 6.7+)", NULL); }
        fprintf(stderr, "landlock-exec: Landlock ABI %d has no network rules; TCP connect is NOT restricted (--net best-effort)\n", abi);
        ports = 0;
    }

    struct ll_ruleset_attr attr = { .handled_access_fs = handled, .handled_access_net = ports ? LL_ACCESS_NET_CONNECT_TCP : 0 };
    size_t attr_size = abi >= 4 ? sizeof(attr) : sizeof(uint64_t);
    int rs = (int)syscall(SYS_landlock_create_ruleset, &attr, attr_size, 0);
    if (rs < 0) die("landlock_create_ruleset", NULL);

    for (int i = 1; i < cmd - 1; i += 2) {
        const char *opt = argv[i];
        if (i + 1 > cmd - 2) { fprintf(stderr, "landlock-exec: %s needs a value\n", opt); return 2; }
        const char *val = argv[i + 1];
        if (strcmp(opt, "--net") == 0) continue;
        if (strcmp(opt, "--connect") == 0) {
            if (!ports) continue;
            struct ll_net_port_attr np = { .allowed_access = LL_ACCESS_NET_CONNECT_TCP, .port = strtoull(val, NULL, 10) };
            if (syscall(SYS_landlock_add_rule, rs, LL_RULE_NET_PORT, &np, 0)) die("landlock_add_rule --connect", val);
            continue;
        }
        uint64_t access;
        if (strcmp(opt, "--ro") == 0) access = FS_READ;
        else if (strcmp(opt, "--rw") == 0) access = handled;
        else { fprintf(stderr, "landlock-exec: unknown option %s\n", opt); return 2; }
        int fd = open(val, O_PATH | O_CLOEXEC);
        if (fd < 0) {
            if (errno == ENOENT) continue; /* an absent optional path grants nothing */
            die("open", val);
        }
        struct stat st;
        if (fstat(fd, &st)) die("fstat", val);
        if (!S_ISDIR(st.st_mode)) access &= FS_FILE_ONLY;
        struct landlock_path_beneath_attr pb = { .allowed_access = access & handled, .parent_fd = fd };
        if (syscall(SYS_landlock_add_rule, rs, LANDLOCK_RULE_PATH_BENEATH, &pb, 0)) die("landlock_add_rule", val);
        close(fd);
    }
    if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0)) die("prctl(NO_NEW_PRIVS)", NULL);
    if (syscall(SYS_landlock_restrict_self, rs, 0)) die("landlock_restrict_self", NULL);
    close(rs);
    execvp(argv[cmd], &argv[cmd]);
    die("exec", argv[cmd]);
    return 126;
}
