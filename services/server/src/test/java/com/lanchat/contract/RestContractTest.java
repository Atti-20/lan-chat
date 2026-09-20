package com.lanchat.contract;

import com.lanchat.config.LanChatPrivateDeploymentProperties;
import com.lanchat.control.admin.ControlAdminOperationService;
import com.lanchat.control.audit.ControlAuditService;
import com.lanchat.control.device.DeviceManagementService;
import com.lanchat.control.policy.OrganizationPolicyService;
import com.lanchat.control.rbac.AuthorizationService;
import com.lanchat.control.rbac.RoleManagementService;
import com.lanchat.control.service.ControlServerInfoService;
import com.lanchat.security.JwtAuthenticationFilter;
import com.lanchat.security.JwtUtil;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lanchat.service.BroadcastService;
import com.lanchat.service.ChatMessageService;
import com.lanchat.service.ConversationService;
import com.lanchat.service.FileService;
import com.lanchat.service.FriendService;
import com.lanchat.service.GroupService;
import com.lanchat.service.LanNodeDiscoveryService;
import com.lanchat.service.NodeDiagnosticsService;
import com.lanchat.service.ResumableUploadService;
import com.lanchat.service.RuntimeLogService;
import com.lanchat.service.TemporaryRoomService;
import com.lanchat.service.UserService;
import com.lanchat.websocket.ChatWebSocketHandler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.configuration.SpringDocSpecPropertiesConfiguration;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeMap;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Export the real MVC routes and DTO shapes without DB, network or application startup runners. */
@WebMvcTest(excludeAutoConfiguration = org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class, properties = {"springdoc.api-docs.enabled=true", "springdoc.api-docs.version=OPENAPI_3_0",
        "springdoc.override-with-generic-response=false"})
@org.springframework.boot.context.properties.EnableConfigurationProperties(org.springdoc.core.properties.SpringDocConfigProperties.class)
@AutoConfigureMockMvc(addFilters = false)
@ImportAutoConfiguration({SpringDocConfiguration.class, SpringDocSpecPropertiesConfiguration.class,
        SpringDocWebMvcConfiguration.class})
@MockitoBean(types = {org.springframework.security.core.userdetails.UserDetailsService.class, AuthorizationService.class, BroadcastService.class, ChatMessageService.class, ChatWebSocketHandler.class, ControlAdminOperationService.class, ControlAuditService.class, ControlServerInfoService.class, ConversationService.class, DeviceManagementService.class, FileService.class, FriendService.class, GroupService.class, JwtUtil.class, LanChatPrivateDeploymentProperties.class, LanNodeDiscoveryService.class, NodeDiagnosticsService.class, OrganizationPolicyService.class, ResumableUploadService.class, RoleManagementService.class, RuntimeLogService.class, TemporaryRoomService.class, UserService.class})
@org.springframework.test.context.ContextConfiguration(classes = RestContractTest.MvcContractContext.class)
class RestContractTest {
    @MockitoBean com.lanchat.push.MobilePushService mobilePushService;
    @MockitoBean com.lanchat.push.PushConfiguration pushConfiguration;
    @MockitoBean com.lanchat.recovery.RecoveryResumeSessions recoverySessions;
    @MockitoBean com.lanchat.recovery.RecoverySnapshotManifest recoveryManifests;
    @MockitoBean com.lanchat.recovery.RecoverySnapshotPages recoveryPages;
    @MockitoBean com.lanchat.recovery.MutationStreamReader recoveryReader;
    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    @org.springframework.context.annotation.ComponentScan(basePackages = "com.lanchat", useDefaultFilters = false,
            includeFilters = @org.springframework.context.annotation.ComponentScan.Filter(
                    org.springframework.web.bind.annotation.RestController.class))
    @org.springframework.context.annotation.Import({com.lanchat.security.SecurityConfig.class, JwtAuthenticationFilter.class})
    static class MvcContractContext {}

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired RequestMappingHandlerMapping routes;
    @Autowired org.springframework.web.context.WebApplicationContext context;
    @Autowired org.springframework.security.web.FilterChainProxy securityFilters;
    @Autowired FileService files;
    @Autowired ConversationService conversations;
    @Autowired BroadcastService broadcasts;
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Test void exportedContractMatchesEveryBusinessRouteAndCommittedSnapshot() throws Exception {
        String json = mvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        var document = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(json);
        document.remove("servers");
        assertTrue(document.at("/components/schemas/MutationPage/properties/records").isObject(),
                "Mutation page must not collapse into the snapshot Page schema");
        assertTrue(document.at("/components/schemas/SnapshotPage/properties/items").isObject(),
                "Snapshot and mutation pages require independent generated models");
        assertEquals("#/components/schemas/MessageDetails",document.at("/components/schemas/Item/properties/details/$ref").asText());
        for(String field : java.util.List.of("fromUserId","contentType","createTime","isBurn")) {
            assertTrue(document.at("/components/schemas/MessageDetails/properties/"+field).isObject(),
                    "A new client needs complete normal-message rendering metadata: "+field);
        }
        document.putObject("info").put("title", "MeshX REST API").put("version", "1")
                .put("description", "Generated MVC structure; authentication, errors and coverage boundaries are enforced by contracts and tests.");
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (!Files.isDirectory(root.resolve("contracts"))) root = root.getParent();
        String securitySource = Files.readString(root.resolve("services/server/src/main/java/com/lanchat/security/SecurityConfig.java"));
        String permits = securitySource.substring(securitySource.indexOf(".requestMatchers("), securitySource.indexOf(").permitAll()"));
        var matcher = java.util.regex.Pattern.compile("\"(/[^\"]+)\"").matcher(permits);
        var publicPatterns = new java.util.ArrayList<String>();
        while (matcher.find()) publicPatterns.add(matcher.group(1));
        assertFalse(publicPatterns.isEmpty(), "SecurityConfig matcher extraction needs updating");
        ((com.fasterxml.jackson.databind.node.ObjectNode) document.get("components"))
                .putObject("securitySchemes").putObject("bearerAuth")
                .put("type", "http").put("scheme", "bearer").put("bearerFormat", "JWT");
        ObjectNode errorSchema = ((ObjectNode) document.at("/components/schemas")).putObject("ResultError");
        errorSchema.put("type", "object").putArray("required").add("code").add("msg");
        var errorFields = errorSchema.putObject("properties");
        errorFields.putObject("code").put("type", "integer");
        errorFields.putObject("msg").put("type", "string");
        errorFields.putObject("requestId").put("type", "string").put("nullable", true);
        errorFields.putObject("data").put("type", "object").put("nullable", true);
        var paths = (com.fasterxml.jackson.databind.node.ObjectNode) document.get("paths");
        var remove = new java.util.ArrayList<String>();
        paths.fieldNames().forEachRemaining(p -> { if (!p.startsWith("/api/")) remove.add(p); });
        remove.forEach(paths::remove);
        paths.fields().forEachRemaining(entry -> entry.getValue().forEach(value -> {
            var operation = (com.fasterxml.jackson.databind.node.ObjectNode) value;
            boolean isPublic = publicPatterns.stream().anyMatch(pattern -> new org.springframework.util.AntPathMatcher().match(pattern, entry.getKey()));
            var security = operation.putArray("security");
            if (!isPublic) security.addObject().putArray("bearerAuth");
            operation.put("x-permission-source", "Controller/service authorization remains authoritative; public refresh/logout may require refresh cookie or token body.");
            var responses = (com.fasterxml.jackson.databind.node.ObjectNode) operation.get("responses");
            if (!isPublic) {
                errorResponse(responses.putObject("401"), "Missing or expired access token");
                errorResponse(responses.putObject("403"), "Permission denied by the security filter");
            }
            errorResponse(responses.putObject("default"), "JSON application error, including 409/503; inspect HTTP and Result.code. Binary disconnects may have no body.");
            var successContent = responses.path("200").path("content");
            successContent.forEach(media -> {
                var shape = media.get("schema");
                if (shape != null && shape.path("$ref").asText().startsWith("#/components/schemas/Result")) {
                    var alternatives = ((ObjectNode) media).putObject("schema").putArray("anyOf");
                    alternatives.add(shape);
                    alternatives.addObject().put("$ref", "#/components/schemas/ResultError");
                }
            });
        }));
        routes.getHandlerMethods().forEach((mapping, handler) -> {
            if (!handler.getBeanType().getPackageName().startsWith("com.lanchat")) return;
            mapping.getPatternValues().stream().filter(p -> p.startsWith("/api/")).map(p -> p.replaceAll(":[^}]+}", "}")).forEach(path -> {
                assertTrue(paths.has(path), "Undocumented route " + path);
                mapping.getMethodsCondition().getMethods().forEach(method -> {
                    String verb = method.name().toLowerCase();
                    assertTrue(paths.get(path).has(verb), "Undocumented method " + path);
                    ObjectNode operation = (ObjectNode) paths.get(path).get(verb);
                    operation.putObject("x-source")
                            .put("class", handler.getMethod().getDeclaringClass().getName())
                            .put("method", handler.getMethod().getName());
                    if (mapping.getConsumesCondition().getConsumableMediaTypes().contains(org.springframework.http.MediaType.APPLICATION_OCTET_STREAM)) {
                        operation.putObject("requestBody").put("required", true).putObject("content")
                                .putObject("application/octet-stream").putObject("schema")
                                .put("type", "string").put("format", "binary");
                    }
                    if (java.util.Arrays.stream(handler.getMethod().getParameterTypes())
                            .anyMatch(org.springframework.web.multipart.MultipartFile.class::isAssignableFrom)) {
                        normalizeMultipart(operation);
                    }
                    boolean recovery = handler.getBeanType().equals(com.lanchat.controller.RecoveryController.class);
                    if (recovery) {
                        var responseMap = (ObjectNode) operation.get("responses");
                        for (String code : java.util.List.of("400", "401", "403", "404", "409", "410", "503")) {
                            ObjectNode error = responseMap.has(code) ? (ObjectNode) responseMap.get(code) : responseMap.putObject(code);
                            errorResponse(error, "Recovery JSON Result error; HTTP status equals code and data.reason identifies the failure. Cache-Control: no-store.");
                        }
                    }
                    if (!recovery && handler.getMethod().getGenericReturnType().getTypeName().contains("ResponseEntity")) {
                        var responseMap = (ObjectNode) operation.get("responses");
                        for (String code : java.util.List.of("401", "403", "404", "default")) {
                            ObjectNode error = responseMap.has(code) ? (ObjectNode) responseMap.get(code) : responseMap.putObject(code);
                            errorResponse(error, "May be an empty streaming response or JSON Result error; check body and Content-Type before parsing.");
                            error.put("x-empty-body-allowed", true);
                        }
                        ((ObjectNode) operation.at("/responses/200/content")).putObject("application/json")
                                .putObject("schema").put("$ref", "#/components/schemas/ResultError");
                    }
                    if (handler.getMethod().getGenericReturnType().getTypeName().contains("<byte[]>")) {
                        assertEquals("com.lanchat.controller.BroadcastController#exportExcel",
                                handler.getBeanType().getName() + "#" + handler.getMethod().getName(),
                                "New byte[] endpoint: review actual content type before exporting");
                        var content = (ObjectNode) operation.at("/responses/200/content");
                        content.remove("*/*");
                        content.putObject(XLSX).putObject("schema").put("type", "string").put("format", "binary");
                    }
                });
            });
        });
        CoreRestSemantics.enrich(document, mapper);
        Path snapshot = root.resolve("contracts/rest/openapi.json");
        String normalized = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(sorted(document)) + "\n";
        Files.createDirectories(Path.of("target/contracts"));
        Files.writeString(Path.of("target/contracts/openapi.json"), normalized);
        if (Boolean.getBoolean("meshx.contract.update")) {
            Files.createDirectories(snapshot.getParent());
            Files.writeString(snapshot, normalized);
        }
        assertTrue(Files.exists(snapshot), "Export with -Dmeshx.contract.update=true");
        assertEquals(mapper.readTree(Files.readString(snapshot)), mapper.readTree(normalized),
                "REST contract drift; review target/contracts/openapi.json and regenerate with -Dmeshx.contract.update=true");
    }

    private void errorResponse(ObjectNode response, String description) {
        response.put("description", description).putObject("content").putObject("application/json")
                .putObject("schema").put("$ref", "#/components/schemas/ResultError");
    }

    private void normalizeMultipart(ObjectNode operation) {
        ObjectNode body = (ObjectNode) operation.get("requestBody");
        JsonNode original = body.get("content").elements().next().get("schema");
        ObjectNode form = original.deepCopy();
        var params = operation.get("parameters");
        if (params != null) {
            var remaining = mapper.createArrayNode();
            params.forEach(parameter -> {
                if ("query".equals(parameter.path("in").asText())) {
                    ((ObjectNode) form.get("properties")).set(parameter.path("name").asText(), parameter.get("schema"));
                    if (parameter.path("required").asBoolean()) form.withArray("required").add(parameter.path("name").asText());
                } else remaining.add(parameter);
            });
            if (remaining.isEmpty()) operation.remove("parameters");
            else operation.set("parameters", remaining);
        }
        body.put("required", true).putObject("content").putObject("multipart/form-data").set("schema", form);
    }

    @Test void multipartAndSpreadsheetContractsMatchRealMvcEncoding() throws Exception {
        var principal = new com.lanchat.security.LoginUser(1L, "contract-fixture", "web");
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(principal, null, java.util.List.of()));
        try {
            org.mockito.Mockito.when(conversations.canUploadFile("private:1:2", 1L)).thenReturn(true);
            var file = new org.springframework.mock.web.MockMultipartFile("file", "fixture.png", "image/png", new byte[]{1, 2, 3});
            for (String path : java.util.List.of("upload", "avatar", "broadcast-image")) {
                mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart("/api/v1/file/" + path)
                                .file(file).param("conversationId", "private:1:2"))
                        .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(200));
            }
            org.mockito.Mockito.verify(files).uploadFile(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(1L));
            org.mockito.Mockito.verify(files).uploadAvatar(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(1L));
            org.mockito.Mockito.verify(files).uploadBroadcastImage(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(1L));
            var denied = mvc.perform(get("/api/v1/file/content/fixture.png")).andExpect(status().isForbidden()).andReturn().getResponse();
            assertEquals(0, denied.getContentAsByteArray().length);
            var expired = mvc.perform(get("/api/v1/file/preview/expired-fixture")).andExpect(status().isUnauthorized()).andReturn().getResponse();
            assertEquals(0, expired.getContentAsByteArray().length);
            org.mockito.Mockito.when(broadcasts.listRecipients(1L, 1L, "ALL")).thenReturn(java.util.List.of());
            var response = mvc.perform(get("/api/v1/broadcast/1/export.xlsx")).andExpect(status().isOk()).andReturn().getResponse();
            assertEquals(XLSX, response.getContentType());
            try (var workbook = new org.apache.poi.xssf.usermodel.XSSFWorkbook(new java.io.ByteArrayInputStream(response.getContentAsByteArray()))) {
                assertEquals("用户名", workbook.getSheetAt(0).getRow(0).getCell(0).getStringCellValue());
            }
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    @Test void realSecurityFilterReturnsJsonErrorForMissingToken() throws Exception {
        var securedMvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context)
                .addFilters(securityFilters).build();
        securedMvc.perform(get("/api/v1/chat/conversations")).andExpect(status().isUnauthorized())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().contentTypeCompatibleWith("application/json"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(401))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.msg").isString());
        var translation = securityFilters.getFilterChains().get(0).getFilters().stream()
                .filter(org.springframework.security.web.access.ExceptionTranslationFilter.class::isInstance).findFirst().orElseThrow();
        var deniedHandler = (org.springframework.security.web.access.AccessDeniedHandler)
                org.springframework.test.util.ReflectionTestUtils.getField(translation, "accessDeniedHandler");
        var deniedResponse = new org.springframework.mock.web.MockHttpServletResponse();
        deniedHandler.handle(new org.springframework.mock.web.MockHttpServletRequest(), deniedResponse,
                new org.springframework.security.access.AccessDeniedException("contract-denied"));
        assertEquals(403, deniedResponse.getStatus());
        assertEquals(403, mapper.readTree(deniedResponse.getContentAsString()).get("code").asInt());
    }

    private Object sorted(JsonNode node) {
        if (node.isObject()) {
            var map = new TreeMap<String, Object>();
            node.fields().forEachRemaining(entry -> map.put(entry.getKey(), sorted(entry.getValue())));
            return map;
        }
        if (node.isArray()) {
            var list = new java.util.ArrayList<Object>();
            node.forEach(value -> list.add(sorted(value)));
            return list;
        }
        return node;
    }
}
