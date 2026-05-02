package com.agentplatform.agent.ai;

/**
 * Layered persona markdown bundle, OpenClaw-style.
 *
 * <ul>
 *   <li>{@code soul}     — behavioral identity (Core Truths / Boundaries / Vibe / Continuity).</li>
 *   <li>{@code identity} — name / vibe / emoji self-introduction (user-overridable).</li>
 *   <li>{@code agents}   — operational constraints (startup order, red lines, tool decision tree, comms style).</li>
 *   <li>{@code tools}    — environment-specific knowledge (bound devices, tool naming conventions, per-tool gotchas).</li>
 * </ul>
 *
 * <p>Any field may be empty string when its source markdown is missing — callers
 * should treat empty as "skip this section" rather than failing.
 */
public record PersonaBundle(String soul, String identity, String agents, String tools) {
}
