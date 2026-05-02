package com.agentplatform.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolSchemaValidatorTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final ToolSchemaValidator validator = new ToolSchemaValidator();

    @Test
    void valid_args_pass() throws Exception {
        JsonNode schema = mapper.readTree("""
                {
                  "type": "object",
                  "properties": {"limit": {"type": "integer", "minimum": 1, "maximum": 100}},
                  "required": ["limit"]
                }
                """);
        JsonNode args = mapper.readTree("{\"limit\": 5}");
        var r = validator.validate(schema, args);
        assertThat(r.valid()).isTrue();
        assertThat(r.errors()).isEmpty();
    }

    @Test
    void invalid_value_fails() throws Exception {
        JsonNode schema = mapper.readTree("""
                {
                  "type": "object",
                  "properties": {"limit": {"type": "integer", "minimum": 1}},
                  "required": ["limit"]
                }
                """);
        JsonNode args = mapper.readTree("{\"limit\": -3}");
        var r = validator.validate(schema, args);
        assertThat(r.valid()).isFalse();
        assertThat(r.errors()).isNotEmpty();
    }

    @Test
    void missing_required_property_fails() throws Exception {
        JsonNode schema = mapper.readTree("""
                {"type": "object", "required": ["limit"]}
                """);
        JsonNode args = mapper.readTree("{}");
        var r = validator.validate(schema, args);
        assertThat(r.valid()).isFalse();
    }
}
