package com.miniautomation.backend.apitesting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.miniautomation.backend.apitesting.dto.ApiRequestSpec;
import com.miniautomation.backend.apitesting.dto.KeyValueItem;
import com.miniautomation.backend.entity.ApiCollectionEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Imports an OpenAPI 3.0 (JSON) specification into a real Kortex API Testing
 * collection — one request per path+method operation, grouped into a folder
 * per top-level path segment. Deliberately a modest, practical subset (per
 * this feature's own scope) rather than full spec fidelity: only the first
 * {@code servers[0].url} is used as the base URL, only the
 * {@code application/json} request body media type is imported, and a
 * request body with no explicit example is filled in with a shallow
 * placeholder built from its schema's top-level properties rather than a
 * fully-resolved (and potentially recursive/circular) schema. YAML OpenAPI
 * documents are not supported — this app has no YAML parser dependency
 * elsewhere, and JSON is a fully valid, widely-exported OpenAPI format.
 */
@Service
public class OpenApiImportService {

    private static final List<String> HTTP_METHODS = List.of("get", "post", "put", "patch", "delete", "head", "options");

    private final ApiCollectionService collectionService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OpenApiImportService(ApiCollectionService collectionService) {
        this.collectionService = collectionService;
    }

    public ApiCollectionEntity importSpec(MultipartFile file) {
        JsonNode root = readJson(file);
        if (!root.has("paths")) {
            throw new ApiTestingException("This file does not look like an OpenAPI specification (no \"paths\" found).");
        }

        String name = textOrDefault(root.path("info").path("title"), "Imported API");
        String description = textOrDefault(root.path("info").path("description"), null);
        String baseUrl = root.path("servers").isArray() && root.path("servers").size() > 0
                ? textOrDefault(root.path("servers").get(0).path("url"), "") : "";

        ApiCollectionEntity collection = collectionService.createCollection(name, description);

        Map<String, Long> folderIdByName = new LinkedHashMap<>();
        int requestCount = 0;

        Iterator<Map.Entry<String, JsonNode>> paths = root.path("paths").fields();
        while (paths.hasNext()) {
            Map.Entry<String, JsonNode> pathEntry = paths.next();
            String pathTemplate = pathEntry.getKey();
            JsonNode pathNode = pathEntry.getValue();

            for (String method : HTTP_METHODS) {
                if (!pathNode.has(method)) continue;
                JsonNode operation = pathNode.path(method);

                ApiRequestSpec spec = buildRequestSpec(method, pathTemplate, baseUrl, pathNode, operation);
                Long folderId = folderIdFor(collection.getId(), pathTemplate, folderIdByName);
                collectionService.createRequest(collection.getId(), folderId, spec);
                requestCount++;
            }
        }

        System.out.println("[ApiTesting] OpenAPI import \"" + name + "\": " + folderIdByName.size()
                + " folder(s), " + requestCount + " request(s).");
        return collection;
    }

    private Long folderIdFor(Long collectionId, String pathTemplate, Map<String, Long> folderIdByName) {
        String[] segments = pathTemplate.split("/");
        String folderName = null;
        for (String segment : segments) {
            if (segment != null && !segment.isBlank() && !segment.startsWith("{")) {
                folderName = segment;
                break;
            }
        }
        if (folderName == null) return null;
        return folderIdByName.computeIfAbsent(folderName,
                fn -> collectionService.createFolder(collectionId, fn).getId());
    }

    private ApiRequestSpec buildRequestSpec(String method, String pathTemplate, String baseUrl,
                                             JsonNode pathNode, JsonNode operation) {
        ApiRequestSpec spec = new ApiRequestSpec();
        String summary = textOrDefault(operation.path("summary"), null);
        String operationId = textOrDefault(operation.path("operationId"), null);
        spec.setName(summary != null ? summary : (operationId != null ? operationId : method.toUpperCase() + " " + pathTemplate));
        spec.setMethod(method.toUpperCase());
        spec.setUrl(baseUrl + convertPathTemplate(pathTemplate));
        spec.setAuthType("INHERIT");

        var headers = new java.util.ArrayList<KeyValueItem>();
        var params = new java.util.ArrayList<KeyValueItem>();
        collectParameters(pathNode.path("parameters"), params, headers);
        collectParameters(operation.path("parameters"), params, headers);
        spec.setParams(params);
        spec.setHeaders(headers);

        JsonNode jsonBody = operation.path("requestBody").path("content").path("application/json");
        if (!jsonBody.isMissingNode()) {
            spec.setBodyType("JSON");
            spec.setBodyContent(exampleOrPlaceholder(jsonBody));
        } else {
            spec.setBodyType("NONE");
        }

        return spec;
    }

    /** OpenAPI's {param} path-template syntax becomes this app's {{param}} variable syntax, so it resolves through the same environment/pre-request mechanism as everything else. */
    private String convertPathTemplate(String pathTemplate) {
        return pathTemplate.replaceAll("\\{([^{}]+)\\}", "{{$1}}");
    }

    private void collectParameters(JsonNode parametersNode, List<KeyValueItem> params, List<KeyValueItem> headers) {
        if (!parametersNode.isArray()) return;
        for (JsonNode param : parametersNode) {
            String in = textOrDefault(param.path("in"), "");
            String name = textOrDefault(param.path("name"), null);
            if (name == null || name.isBlank()) continue;
            String example = firstNonBlank(
                    textOrDefault(param.path("example"), null),
                    textOrDefault(param.path("schema").path("example"), null),
                    textOrDefault(param.path("schema").path("default"), null));
            KeyValueItem item = new KeyValueItem(name, example != null ? example : "");
            item.setEnabled(param.path("required").asBoolean(false));
            if ("header".equalsIgnoreCase(in)) headers.add(item);
            else if ("query".equalsIgnoreCase(in)) params.add(item);
            // "path" params are already folded into the URL template itself; "cookie" params are not modelled.
        }
    }

    private String exampleOrPlaceholder(JsonNode mediaTypeNode) {
        if (mediaTypeNode.has("example")) {
            return prettyPrint(mediaTypeNode.path("example"));
        }
        JsonNode schema = mediaTypeNode.path("schema");
        if (schema.has("example")) {
            return prettyPrint(schema.path("example"));
        }
        if (schema.path("properties").isObject()) {
            ObjectNode placeholder = objectMapper.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> props = schema.path("properties").fields();
            while (props.hasNext()) {
                Map.Entry<String, JsonNode> prop = props.next();
                placeholder.set(prop.getKey(), placeholderForType(textOrDefault(prop.getValue().path("type"), "string")));
            }
            return prettyPrint(placeholder);
        }
        return "";
    }

    private JsonNode placeholderForType(String type) {
        return switch (type) {
            case "integer", "number" -> objectMapper.getNodeFactory().numberNode(0);
            case "boolean" -> objectMapper.getNodeFactory().booleanNode(false);
            case "array" -> objectMapper.createArrayNode();
            case "object" -> objectMapper.createObjectNode();
            default -> objectMapper.getNodeFactory().textNode("");
        };
    }

    private String prettyPrint(JsonNode node) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception e) {
            return node.toString();
        }
    }

    private String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private String textOrDefault(JsonNode node, String fallback) {
        if (node == null) return fallback;
        if (node.isTextual()) return node.asText();
        if (node.isNumber() || node.isBoolean()) return node.asText();
        return fallback;
    }

    private JsonNode readJson(MultipartFile file) {
        try {
            return objectMapper.readTree(file.getInputStream());
        } catch (Exception e) {
            throw new ApiTestingException("Could not parse the uploaded OpenAPI specification (JSON expected): " + e.getMessage());
        }
    }
}
