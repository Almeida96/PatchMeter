package io.github.almeida96.patchmeter;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class McpServerTest {
    private Analysis.Result fixture() throws IOException {
        try (var diff = Main.class.getResourceAsStream("/change.diff");
                var coverage = Main.class.getResourceAsStream("/jacoco.xml")) {
            assertNotNull(diff);
            assertNotNull(coverage);
            return Analysis.analyze(Patch.parse(diff.readAllBytes()),
                    Coverage.parse(coverage.readAllBytes(), "jacoco", "src/main/java", ""),
                    "src/main/java", "", "**/*.java", new BigDecimal("80"));
        }
    }

    private ObjectNode request(int id, String method, JsonNode params) {
        var node = Json.MAPPER.createObjectNode().put("jsonrpc", "2.0").put("id", id).put("method", method);
        node.set("params", params);
        return node;
    }

    private ObjectNode initialization(String version) {
        var params = Json.MAPPER.createObjectNode().put("protocolVersion", version);
        params.putObject("capabilities");
        params.putObject("clientInfo").put("name", "integration-test").put("version", "1");
        return request(1, "initialize", params);
    }

    private McpServer ready() throws IOException {
        var server = new McpServer(fixture());
        assertTrue(server.handle(initialization("2025-11-25")).has("result"));
        assertNull(server.handle(Json.read("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")));
        return server;
    }

    private ObjectNode call(McpServer server, String name, JsonNode args) {
        var params = Json.MAPPER.createObjectNode().put("name", name);
        params.set("arguments", args);
        return server.handle(request(2, "tools/call", params));
    }

    private List<JsonNode> frames(byte[] input) throws IOException {
        var output = new ByteArrayOutputStream();
        new McpServer(fixture()).serve(new ByteArrayInputStream(input), new PrintStream(output, true, StandardCharsets.UTF_8));
        var rows = new ArrayList<JsonNode>();
        for (var line : output.toString(StandardCharsets.UTF_8).lines().toList()) rows.add(Json.read(line));
        return rows;
    }

    @Test void toolsRequireInitializationAndInitializedNotification() throws Exception {
        var server = new McpServer(fixture());
        var list = request(2, "tools/list", Json.MAPPER.createObjectNode());
        assertEquals(-32600, server.handle(list).path("error").path("code").asInt());
        server.handle(initialization("2025-11-25"));
        assertEquals(-32600, server.handle(list).path("error").path("code").asInt());
        assertNull(server.handle(Json.read("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")));
        assertEquals(3, server.handle(list).path("result").path("tools").size());
    }

    @Test void pingIsAllowedBeforeAndDuringInitialization() throws Exception {
        var server = new McpServer(fixture());
        var ping = request(2, "ping", Json.MAPPER.createObjectNode());
        assertTrue(server.handle(ping).has("result"));
        server.handle(initialization("2025-11-25"));
        assertTrue(server.handle(ping).has("result"));
        assertEquals(-32600, server.handle(request(3, "tools/list", Json.MAPPER.createObjectNode()))
                .path("error").path("code").asInt());
    }

    @Test void negotiatesBothSupportedVersionsAndRejectsRepeatedInitialization() throws Exception {
        for (var version : List.of("2025-06-18", "2025-11-25")) {
            var server = new McpServer(fixture());
            var response = server.handle(initialization(version));
            assertEquals(version, response.path("result").path("protocolVersion").asText());
            assertEquals("patchmeter", response.path("result").path("serverInfo").path("name").asText());
            assertEquals(-32600, server.handle(initialization(version)).path("error").path("code").asInt());
        }
        assertEquals("2025-11-25", new McpServer(fixture()).handle(initialization("unsupported"))
                .path("result").path("protocolVersion").asText());
    }

    @Test void inventoryDeclaresOnlyReadOnlyToolsAndBoundedSchemas() throws Exception {
        var list = ready().handle(request(3, "tools/list", Json.MAPPER.createObjectNode())).path("result").path("tools");
        var names = new ArrayList<String>();
        for (var tool : list) {
            names.add(tool.path("name").asText());
            assertTrue(tool.path("annotations").path("readOnlyHint").asBoolean());
            assertTrue(tool.path("annotations").path("idempotentHint").asBoolean());
            assertFalse(tool.path("annotations").path("destructiveHint").asBoolean());
            assertFalse(tool.path("annotations").path("openWorldHint").asBoolean());
            assertFalse(tool.path("inputSchema").path("additionalProperties").asBoolean());
            assertEquals("object", tool.path("outputSchema").path("type").asText());
        }
        assertEquals(Set.of("summarize_patch", "list_changed_files", "inspect_changed_lines"), Set.copyOf(names));
    }

    @Test void structuredEvidenceEqualsTextAndPreservesPolicy() throws Exception {
        var response = call(ready(), "summarize_patch", Json.MAPPER.createObjectNode()).path("result");
        assertFalse(response.path("isError").asBoolean());
        assertEquals(Json.read(Json.compact(response.path("structuredContent"))),
                Json.read(response.path("content").get(0).path("text").asText()));
        var data = response.path("structuredContent");
        assertEquals("FAIL", data.path("status").asText());
        assertEquals(4, data.path("changedLines").asInt());
        assertEquals(2, data.path("coveredLines").asInt());
        assertEquals(1, data.path("notReportedLines").asInt());
        assertEquals(80, data.path("policy").path("minimumPercent").asInt());
    }

    @Test void filtersUncoveredLinesWithoutTreatingMissingRecordsAsCovered() throws Exception {
        var args = Json.MAPPER.createObjectNode().put("path", "src/main/java/shop/Pricing.java").put("status", "UNCOVERED");
        var data = call(ready(), "inspect_changed_lines", args).path("result").path("structuredContent");
        assertEquals(1, data.path("total").asInt());
        assertEquals(7, data.path("lines").get(0).path("line").asInt());
        args.put("status", "NOT_REPORTED");
        var absent = call(ready(), "inspect_changed_lines", args).path("result").path("structuredContent");
        assertEquals(8, absent.path("lines").get(0).path("line").asInt());
        assertTrue(absent.path("lines").get(0).path("hits").isNull());
    }

    @Test void paginationHasNoDuplicatesAndHasExplicitEnd() throws Exception {
        var server = ready();
        var args = Json.MAPPER.createObjectNode().put("limit", 1);
        var first = call(server, "list_changed_files", args).path("result").path("structuredContent");
        assertEquals(1, first.path("nextOffset").asInt());
        args.put("offset", 1);
        var last = call(server, "list_changed_files", args).path("result").path("structuredContent");
        assertTrue(last.path("nextOffset").isNull());
        assertNotEquals(first.path("files").get(0).path("path"), last.path("files").get(0).path("path"));
    }

    @Test void unknownToolsAndInvalidArgumentTypesFailWithoutChangingSession() throws Exception {
        var server = ready();
        for (var args : List.of("{\"limit\":false}", "{\"limit\":1.5}", "{\"limit\":0}",
                "{\"limit\":101}", "{\"offset\":-1}", "{\"offset\":1000001}", "{\"limit\":null}", "{\"file\":\"secret\"}"))
            assertEquals(-32602, call(server, "list_changed_files", Json.read(args)).path("error").path("code").asInt(), args);
        assertEquals(-32602, call(server, "delete_report", Json.MAPPER.createObjectNode()).path("error").path("code").asInt());
        assertEquals(-32602, call(server, "inspect_changed_lines", Json.MAPPER.createObjectNode()).path("error").path("code").asInt());
        assertEquals(-32602, call(server, "summarize_patch", Json.MAPPER.createArrayNode()).path("error").path("code").asInt());
        assertTrue(server.handle(request(9, "ping", Json.MAPPER.createObjectNode())).has("result"));
    }

    @Test void malformedEnvelopesAndUnknownMethodsHaveProtocolErrors() throws Exception {
        var server = ready();
        for (var raw : List.of("[]", "null", "{\"jsonrpc\":\"1.0\",\"id\":1,\"method\":\"ping\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":true,\"method\":\"ping\"}"))
            assertEquals(-32600, server.handle(Json.read(raw)).path("error").path("code").asInt());
        assertEquals(-32602, server.handle(request(4, "tools/list", Json.MAPPER.createArrayNode())).path("error").path("code").asInt());
        assertEquals(-32601, server.handle(request(4, "resources/list", Json.MAPPER.createObjectNode())).path("error").path("code").asInt());
        assertNull(server.handle(Json.read("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/unknown\"}")));
    }

    @Test void malformedJsonAndUtf8FramesDoNotBreakFollowingRequests() throws Exception {
        var bytes = new ByteArrayOutputStream();
        bytes.write("{\"id\":1,\"id\":2}\n{} {}\n".getBytes(StandardCharsets.UTF_8));
        bytes.write(new byte[] {(byte) 0xc3, 0x28, 0x0a});
        bytes.write((Json.compact(initialization("2025-11-25")) + "\n").getBytes(StandardCharsets.UTF_8));
        var responses = frames(bytes.toByteArray());
        assertEquals(4, responses.size());
        for (int i = 0; i < 3; i++) assertEquals(-32700, responses.get(i).path("error").path("code").asInt());
        assertEquals("patchmeter", responses.get(3).path("result").path("serverInfo").path("name").asText());
    }

    @Test void oversizedFrameIsDrainedAndFollowingSessionRemainsUsable() throws Exception {
        var input = "x".repeat(1024 * 1024 + 1) + "\n" + Json.compact(initialization("2025-11-25")) + "\n"
                + "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n"
                + "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}\n";
        var responses = frames(input.getBytes(StandardCharsets.UTF_8));
        assertEquals(3, responses.size());
        assertEquals(-32700, responses.get(0).path("error").path("code").asInt());
        assertTrue(responses.get(2).has("result"));
    }
}
