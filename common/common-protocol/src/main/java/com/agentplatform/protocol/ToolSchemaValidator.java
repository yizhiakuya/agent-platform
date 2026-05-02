package com.agentplatform.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;

import java.util.List;
import java.util.Set;

/**
 * Validate a tool call's {@code args} against its declared JSON Schema.
 *
 * <p>Used both client-side (Android can early-reject malformed args before invoking)
 * and server-side (agent-service can validate LLM-generated args before forwarding).
 */
public final class ToolSchemaValidator {

    private final JsonSchemaFactory factory =
            JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);

    public ValidationResult validate(JsonNode schema, JsonNode args) {
        JsonSchema jsonSchema = factory.getSchema(schema);
        Set<ValidationMessage> errors = jsonSchema.validate(args);
        if (errors.isEmpty()) {
            return new ValidationResult(true, List.of());
        }
        return new ValidationResult(false,
                errors.stream().map(ValidationMessage::getMessage).toList());
    }

    public record ValidationResult(boolean valid, List<String> errors) {}
}
