package com.superiorsoftllc.infantry.ui.model

/**
 * M2-1: the 16 role portraits. An agent and its role are one representation: the agent's portrait is
 * its role's, the same everywhere (agents list, chat, board, header). [key] is the PNG's file name under
 * `composeResources/drawable/`, and the stable identity tests compare. Pure data, no Compose.
 */
enum class RolePortrait(val key: String) {
    ARCHITECT("01_software_architect"),
    FULLSTACK("02_full_stack_developer"),
    FRONTEND("03_frontend_developer"),
    BACKEND("04_backend_developer"),
    MOBILE("05_mobile_developer"),
    DEVOPS("06_devops_engineer"),
    QA("07_qa_engineer"),
    SECURITY("08_security_engineer"),
    PM("09_project_manager"),
    ASSISTANT("10_project_assistant"),
    BRAINSTORMER("11_brainstormer"),
    MARKETING("12_marketing_specialist"),
    GIT("13_git_expert"),
    SEO("14_seo_specialist"),
    LOCALIZATION("15_localization_specialist"),
    CUSTOM("16_custom_agent"),
    ;

    /** The path [org.jetbrains.compose.resources] reads the PNG from. */
    val resourcePath: String get() = "drawable/$key.png"
}

/**
 * Role id -> portrait. Covers every daemon catalog id (infantry `roles/index.json`) and the obvious
 * spelled-out aliases. ORIGIN-SCRUB-1a (contract 1.90.0) renamed five ids; their retired names stay here so a
 * daemon older than 1.90.0 still paints the right portrait: `git` -> version-control, `i18n` -> localization,
 * `brainstormer` -> ideation, `bot` -> automation, `chief-of-staff` -> assistant. `automation`, `custom`, a
 * user-defined role, an unknown id or no role at all get [RolePortrait.CUSTOM].
 */
private val PORTRAIT_BY_ROLE: Map<String, RolePortrait> = buildMap {
    fun alias(p: RolePortrait, vararg ids: String) = ids.forEach { put(it, p) }
    alias(RolePortrait.ARCHITECT, "architect", "software-architect", "architecture")
    alias(RolePortrait.FULLSTACK, "fullstack", "full-stack", "full-stack-developer", "fullstack-developer")
    alias(RolePortrait.FRONTEND, "frontend", "front-end", "frontend-developer", "front-end-developer")
    alias(RolePortrait.BACKEND, "backend", "back-end", "backend-developer", "back-end-developer")
    alias(RolePortrait.MOBILE, "mobile", "mobile-developer")
    alias(RolePortrait.DEVOPS, "devops", "dev-ops", "devops-engineer")
    alias(RolePortrait.QA, "qa", "qa-engineer", "tester", "quality-assurance")
    alias(RolePortrait.SECURITY, "security", "security-engineer", "secops")
    alias(RolePortrait.PM, "pm", "project-manager", "product-manager", "manager")
    alias(RolePortrait.ASSISTANT, "assistant", "project-assistant", "chief-of-staff")
    alias(RolePortrait.BRAINSTORMER, "brainstormer", "brainstorm", "ideation")
    alias(RolePortrait.MARKETING, "marketing", "marketing-specialist", "marketer")
    alias(RolePortrait.GIT, "version-control", "vcs", "git", "git-expert")
    alias(RolePortrait.SEO, "seo", "seo-specialist")
    alias(RolePortrait.LOCALIZATION, "localization", "localisation", "localization-specialist", "i18n", "l10n", "translator")
}

/** The portrait for [roleId]; case, spaces and underscores are normalised ("Project Manager" -> pm). */
fun rolePortraitFor(roleId: String?): RolePortrait {
    val id = roleId?.trim()?.lowercase()?.replace(' ', '-')?.replace('_', '-')
    if (id.isNullOrEmpty()) return RolePortrait.CUSTOM
    return PORTRAIT_BY_ROLE[id] ?: RolePortrait.CUSTOM
}
