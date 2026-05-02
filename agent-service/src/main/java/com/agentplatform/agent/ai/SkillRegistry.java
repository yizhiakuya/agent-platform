package com.agentplatform.agent.ai;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Scans {@code classpath:skills/<slug>/SKILL.md} once at startup, parses each
 * file's YAML front-matter into a {@link SkillDef}, and exposes lookup by
 * name.
 *
 * <p>Front-matter format (matches Claude Code's skill convention):
 * <pre>
 * ---
 * name: photos-search
 * description: One-line summary used in the skill listing.
 * ---
 * (markdown body — the playbook)
 * </pre>
 *
 * <p>SnakeYAML is on the classpath transitively via Spring Boot (used by
 * {@code application.yaml} parsing), so we reuse it. A skill whose file is
 * malformed or missing required keys is skipped with a {@code WARN} log;
 * startup never fails because of one bad skill.
 *
 * <p>Two consumers:
 * <ul>
 *   <li>{@link #all()} — prompt assembly enumerates all skills and injects
 *       just {@code name + description} into the system prompt as a listing.
 *   <li>{@link #get(String)} — {@link SkillLoadCallback} resolves a name back
 *       to a body when the LLM decides to pull a playbook.
 * </ul>
 */
@Component
public class SkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);

    private static final String SKILL_PATTERN = "classpath:skills/*/SKILL.md";

    /** Insertion-ordered so the listing is stable across restarts. */
    private final Map<String, SkillDef> skills = new LinkedHashMap<>();

    @PostConstruct
    void load() {
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources;
        try {
            resources = resolver.getResources(SKILL_PATTERN);
        } catch (IOException e) {
            log.warn("Failed to scan classpath for skills (pattern '{}'): {}",
                    SKILL_PATTERN, e.getMessage());
            return;
        }
        if (resources.length == 0) {
            log.info("No skills found under '{}'.", SKILL_PATTERN);
            return;
        }
        for (Resource r : resources) {
            try {
                SkillDef def = parse(r);
                if (def == null) continue;
                SkillDef prev = skills.put(def.name(), def);
                if (prev != null) {
                    log.warn("Skill name '{}' defined more than once; later definition wins.",
                            def.name());
                }
            } catch (Exception e) {
                log.warn("Failed to parse skill '{}': {}; skipping.",
                        safeUri(r), e.getMessage());
            }
        }
        log.info("Loaded {} skill(s): {}", skills.size(), skills.keySet());
    }

    /**
     * Parse one SKILL.md file. Returns {@code null} (and logs a warn) if the
     * file is missing front-matter or required keys.
     */
    private SkillDef parse(Resource resource) throws IOException {
        String raw;
        try (InputStream in = resource.getInputStream()) {
            raw = StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        }
        if (raw == null || raw.isBlank()) {
            log.warn("Skill '{}' is empty; skipping.", safeUri(resource));
            return null;
        }

        // Normalize line endings so the front-matter delimiters match
        // regardless of CRLF/LF on disk.
        String normalized = raw.replace("\r\n", "\n").replace('\r', '\n');

        if (!normalized.startsWith("---\n")) {
            log.warn("Skill '{}' missing leading '---' YAML front-matter; skipping.",
                    safeUri(resource));
            return null;
        }
        // Find the closing "---" delimiter on its own line.
        int closing = normalized.indexOf("\n---\n", 4);
        if (closing < 0) {
            // Allow the file to end immediately after the front-matter (no
            // body). Tolerate trailing-newline-less '---' too.
            closing = normalized.indexOf("\n---", 4);
            if (closing < 0) {
                log.warn("Skill '{}' missing closing '---' for YAML front-matter; skipping.",
                        safeUri(resource));
                return null;
            }
        }
        String yamlBlock = normalized.substring(4, closing); // exclusive of closing
        // Body starts after the closing delimiter line.
        int bodyStart = normalized.indexOf('\n', closing + 1);
        String body = bodyStart < 0 ? "" : normalized.substring(bodyStart + 1);

        Map<String, Object> meta;
        try {
            Object loaded = new Yaml().load(yamlBlock);
            if (loaded instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> casted = (Map<String, Object>) m;
                meta = casted;
            } else {
                log.warn("Skill '{}' front-matter is not a YAML mapping; skipping.",
                        safeUri(resource));
                return null;
            }
        } catch (RuntimeException e) {
            log.warn("Skill '{}' front-matter YAML parse failed: {}; skipping.",
                    safeUri(resource), e.getMessage());
            return null;
        }

        Object nameObj = meta.get("name");
        Object descObj = meta.get("description");
        if (!(nameObj instanceof String name) || name.isBlank()) {
            log.warn("Skill '{}' missing 'name' in front-matter; skipping.", safeUri(resource));
            return null;
        }
        String description = (descObj instanceof String s) ? s : "";

        return new SkillDef(name.trim(), description.trim(), body.stripLeading());
    }

    private static String safeUri(Resource r) {
        try {
            return r.getURI().toString();
        } catch (IOException e) {
            return r.getDescription();
        }
    }

    /** All loaded skills, in load order. Never null. */
    public Collection<SkillDef> all() {
        return Collections.unmodifiableCollection(skills.values());
    }

    /** Look up a skill by its declared {@code name}. */
    public Optional<SkillDef> get(String name) {
        if (name == null) return Optional.empty();
        return Optional.ofNullable(skills.get(name));
    }
}
