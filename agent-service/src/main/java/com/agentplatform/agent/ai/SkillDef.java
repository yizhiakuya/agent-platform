package com.agentplatform.agent.ai;

/**
 * In-memory representation of a parsed {@code SKILL.md} file.
 *
 * <p>{@code name} and {@code description} come from the YAML front-matter
 * block; {@code body} is the markdown content after the front-matter (the
 * playbook itself, fed back to the LLM via {@link SkillLoadCallback}).
 *
 * <p>Fields are non-null after a successful parse — {@link SkillRegistry}
 * skips files whose front-matter is missing required keys.
 */
public record SkillDef(String name, String description, String body) {}
