package com.superiorsoftllc.agenthq.plugin.api

/**
 * GAP-AUDIT-1 (e): how a modules.json grant (`allowOverride`, `allowInject`) matches a node id or selector. An entry is
 * an exact string, or a glob: `*` matches any run of characters except `/` (one path segment, dots included, so
 * `infantry.board.*` grants every node id under that prefix), `**` matches anything, `/` included
 * (`infantry.shell.frame/` then a double star). There is no other wildcard; every other character is literal. A grant never reaches a
 * locked node: the lock is checked separately, after the grant.
 */
object GrantPattern {
    /** Whether any entry of [grants] matches [target]. */
    fun matches(grants: Collection<String>, target: String): Boolean = grants.any { matches(it, target) }

    /** Whether grant [pattern] matches [target]. */
    fun matches(pattern: String, target: String): Boolean =
        if ('*' !in pattern) pattern == target else glob(pattern, 0, target, 0)

    private fun glob(p: String, pi: Int, t: String, ti: Int): Boolean {
        if (pi == p.length) return ti == t.length
        if (p[pi] == '*') {
            val any = pi + 1 < p.length && p[pi + 1] == '*'
            val next = if (any) pi + 2 else pi + 1
            var i = ti
            while (true) {
                if (glob(p, next, t, i)) return true
                if (i == t.length || (!any && t[i] == '/')) return false
                i++
            }
        }
        return ti < t.length && p[pi] == t[ti] && glob(p, pi + 1, t, ti + 1)
    }
}
