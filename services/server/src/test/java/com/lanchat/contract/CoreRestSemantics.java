package com.lanchat.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lanchat.common.Result;
import com.lanchat.dto.*;
import com.lanchat.entity.ChatMessage;
import com.lanchat.service.impl.UserServiceImpl;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/** Test-classpath export policy, verified against Jackson and real service input guards.
 * Fields/routes still originate in Java; this is not a second hand-written DTO catalog. */
final class CoreRestSemantics {
    private CoreRestSemantics() {}

    static void enrich(ObjectNode document, ObjectMapper mapper) {
        ObjectNode schemas = (ObjectNode) document.at("/components/schemas");
        // A Java reference can be serialized as JSON null. Optional means absence,
        // not nullability: preserve both explicitly instead of guessing from TS usage.
        for (Class<?> type : List.of(LoginDTO.class, TokenRefreshDTO.class, RegisterDTO.class,
                LoginVO.class, ConversationSummary.class, DeviceLoginVO.class,
                NodePublicInfo.class, ChatMessage.class)) {
            ObjectNode schema = (ObjectNode) schemas.get(type.getSimpleName());
            assertNotNull(schema, "Missing MVC model " + type.getName());
            ObjectNode fields = (ObjectNode) schema.get("properties");
            var description = mapper.getSerializationConfig().introspect(mapper.constructType(type));
            for (var property : description.findProperties()) {
                if (!fields.has(property.getName())) continue; // e.g. @JsonIgnore refreshToken
                var member = property.getPrimaryMember();
                if (member != null && !member.getRawType().isPrimitive()) {
                    fields.set(property.getName(), nullable((ObjectNode) fields.get(property.getName()), mapper));
                }
            }
            schema.put("x-java-source", type.getName());
            schema.put("x-nullability-policy", "Java reference fields may be null; absence remains separate from business requiredness.");
        }
        // Result factories guarantee code/msg; data and requestId can be null or
        // absent for older compatible peers. Do not force UI state into wire DTOs.
        var empty = mapper.valueToTree(Result.success());
        assertTrue(empty.has("code") && empty.has("msg"));
        assertTrue(empty.path("data").isNull() && empty.path("requestId").isNull());
        schemas.fields().forEachRemaining(entry -> {
            if (!entry.getKey().startsWith("Result")) return;
            ObjectNode schema = (ObjectNode) entry.getValue();
            ObjectNode fields = (ObjectNode) schema.get("properties");
            if (fields == null || !fields.has("code") || !fields.has("msg")) return;
            schema.putArray("required").add("code").add("msg");
            for (String field : List.of("data", "requestId")) {
                if (fields.has(field)) fields.set(field, nullable((ObjectNode) fields.get(field), mapper));
            }
            schema.put("x-java-source", Result.class.getName());
        });

        // This service guard runs before accessing any dependencies. Keeping the
        // assertions here prevents the export from inventing required login fields.
        var service = new UserServiceImpl();
        for (String missing : List.of("username", "password")) {
            LoginDTO request = new LoginDTO();
            request.setUsername("contract-user");
            request.setPassword("contract-password");
            ReflectionTestUtils.setField(request, missing, null);
            assertThrows(IllegalArgumentException.class, () -> service.login(request));
            ReflectionTestUtils.setField(request, missing, "  ");
            assertThrows(IllegalArgumentException.class, () -> service.login(request));
        }
        for (String missing : List.of("username", "password")) {
            RegisterDTO request = new RegisterDTO();
            request.setUsername("contract-user");
            request.setPassword("Contract123");
            ReflectionTestUtils.setField(request, missing, null);
            assertThrows(IllegalArgumentException.class, () -> service.register(request));
            ReflectionTestUtils.setField(request, missing, "  ");
            assertThrows(IllegalArgumentException.class, () -> service.register(request));
        }
        for (String model : List.of("LoginDTO", "RegisterDTO")) {
            ObjectNode input = (ObjectNode) schemas.get(model);
            input.putArray("required").add("username").add("password");
            for (String name : List.of("username", "password")) {
                ObjectNode field = (ObjectNode) input.path("properties").get(name);
                field.remove("nullable");
                field.put("minLength", 1);
                field.put("description", "Required non-blank value; normalization, reserved names and password policy remain in UserServiceImpl and behavior tests.");
            }
        }
        ObjectNode login = (ObjectNode) schemas.get("LoginDTO");
        @SuppressWarnings("unchecked")
        Set<String> types = (Set<String>) ReflectionTestUtils.getField(UserServiceImpl.class, "ALLOWED_DEVICE_TYPES");
        assertNotNull(types);
        var normalized = ((ObjectNode) login.path("properties").get("deviceType")).putArray("x-normalized-values");
        new TreeSet<>(types).forEach(normalized::add);
        ((ObjectNode) login.path("properties").get("deviceType"))
                .put("description", "Trim/lowercase; missing, blank or unknown input normalizes to web. This is not a rejecting enum.");
        ((ObjectNode) schemas.get("TokenRefreshDTO").path("properties").get("deviceType"))
                .put("description", "Not authoritative during refresh: the existing refresh token determines the device type.");
    }

    private static ObjectNode nullable(ObjectNode original, ObjectMapper mapper) {
        if (original.path("nullable").asBoolean() || original.has("anyOf")) return original;
        if (original.has("$ref")) {
            // OpenAPI 3.0 ignores siblings of $ref. An explicit null-only alternative
            // keeps reference identity and does not make every use of the DTO nullable.
            ObjectNode result = mapper.createObjectNode();
            var choices = result.putArray("anyOf");
            choices.add(original);
            choices.addObject().put("type", "object").put("nullable", true).putArray("enum").addNull();
            return result;
        }
        original.put("nullable", true);
        return original;
    }
}
