package com.miniautomation.backend.apitesting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniautomation.backend.apitesting.dto.ApiRequestSpec;
import com.miniautomation.backend.apitesting.dto.AuthConfig;
import com.miniautomation.backend.apitesting.dto.EnvironmentVariable;
import com.miniautomation.backend.apitesting.dto.KeyValueItem;
import com.miniautomation.backend.entity.ApiCollectionEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

/**
 * Imports a Postman v2.1 collection export into a real Kortex API Testing
 * collection — method, URL, headers, body (raw/urlencoded/formdata text
 * fields), and the common auth types (bearer/basic/apikey) are preserved.
 * Deliberately modest rather than 100%-faithful (per this feature's own
 * scope: import must not become the blocker for the rest of the module):
 * Postman's own pre/test JS scripts are not imported (this app's scripting
 * extension point is the structured pre-request-variable/extraction
 * mechanism, not arbitrary JS), and file-upload form-data fields are skipped
 * since a portable collection JSON never embeds the actual file bytes.
 * Nested folders deeper than one level are flattened into their nearest
 * ancestor folder, matching this app's own one-level folder model.
 */
@Service
public class PostmanImportService {

    private final ApiCollectionService collectionService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PostmanImportService(ApiCollectionService collectionService) {
        this.collectionService = collectionService;
    }

    public ApiCollectionEntity importCollection(MultipartFile file) {
        JsonNode root = readJson(file);

        String name = textOrDefault(root.path("info").path("name"), "Imported Postman Collection");
        String description = readDescription(root.path("info"));

        ApiCollectionEntity collection = collectionService.createCollection(name, description);

        AuthConfig auth = convertAuth(root.path("auth"));
        List<EnvironmentVariable> variables = new ArrayList<>();
        for (JsonNode v : root.path("variable")) {
            String key = textOrDefault(v.path("key"), null);
            if (key != null && !key.isBlank()) {
                variables.add(new EnvironmentVariable(key, textOrDefault(v.path("value"), ""), false));
            }
        }
        collectionService.updateCollection(collection.getId(), name, description, auth, variables);

        int[] counters = {0, 0}; // [foldersCreated, requestsCreated] — for the summary log only
        for (JsonNode item : root.path("item")) {
            importItem(collection.getId(), null, item, counters);
        }

        System.out.println("[ApiTesting] Postman import \"" + name + "\": " + counters[0] + " folder(s), "
                + counters[1] + " request(s).");
        return collection;
    }

    private void importItem(Long collectionId, Long folderId, JsonNode item, int[] counters) {
        if (item.has("item")) {
            // A folder — Postman allows arbitrary nesting; this app models one level,
            // so a folder found while already inside another folder contributes its
            // requests to that SAME parent folder rather than creating a deeper one.
            Long effectiveFolderId = folderId;
            if (folderId == null) {
                String folderName = textOrDefault(item.path("name"), "Folder");
                effectiveFolderId = collectionService.createFolder(collectionId, folderName).getId();
                counters[0]++;
            }
            for (JsonNode child : item.path("item")) {
                importItem(collectionId, effectiveFolderId, child, counters);
            }
            return;
        }

        if (!item.has("request")) return;
        String name = textOrDefault(item.path("name"), "Request");
        ApiRequestSpec spec = convertRequest(name, item.path("request"));
        collectionService.createRequest(collectionId, folderId, spec);
        counters[1]++;
    }

    private ApiRequestSpec convertRequest(String name, JsonNode request) {
        ApiRequestSpec spec = new ApiRequestSpec();
        spec.setName(name);
        spec.setMethod(textOrDefault(request.path("method"), "GET").toUpperCase());

        JsonNode urlNode = request.path("url");
        spec.setUrl(urlNode.isTextual() ? urlNode.asText() : textOrDefault(urlNode.path("raw"), ""));

        List<KeyValueItem> headers = new ArrayList<>();
        for (JsonNode h : request.path("header")) {
            String key = textOrDefault(h.path("key"), null);
            if (key == null || key.isBlank()) continue;
            KeyValueItem kv = new KeyValueItem(key, textOrDefault(h.path("value"), ""));
            kv.setEnabled(!h.path("disabled").asBoolean(false));
            headers.add(kv);
        }
        spec.setHeaders(headers);

        JsonNode body = request.path("body");
        String mode = textOrDefault(body.path("mode"), "none");
        switch (mode) {
            case "raw" -> {
                String language = textOrDefault(body.path("options").path("raw").path("language"), "text");
                spec.setBodyType("json".equalsIgnoreCase(language) ? "JSON" : "RAW");
                spec.setBodyContent(textOrDefault(body.path("raw"), ""));
            }
            case "urlencoded" -> {
                spec.setBodyType("FORM_URLENCODED");
                spec.setFormFields(convertFormFields(body.path("urlencoded")));
            }
            case "formdata" -> {
                spec.setBodyType("MULTIPART");
                spec.setFormFields(convertFormFields(body.path("formdata")));
            }
            default -> spec.setBodyType("NONE");
        }

        if (request.has("auth")) {
            spec.setAuthType(mapAuthType(request.path("auth").path("type").asText("noauth")));
            spec.setAuth(convertAuth(request.path("auth")));
        } else {
            spec.setAuthType("INHERIT");
        }

        return spec;
    }

    private List<KeyValueItem> convertFormFields(JsonNode fieldsNode) {
        List<KeyValueItem> fields = new ArrayList<>();
        for (JsonNode f : fieldsNode) {
            if ("file".equalsIgnoreCase(textOrDefault(f.path("type"), "text"))) continue; // no file bytes available in a portable export
            String key = textOrDefault(f.path("key"), null);
            if (key == null || key.isBlank()) continue;
            KeyValueItem kv = new KeyValueItem(key, textOrDefault(f.path("value"), ""));
            kv.setEnabled(!f.path("disabled").asBoolean(false));
            fields.add(kv);
        }
        return fields;
    }

    private AuthConfig convertAuth(JsonNode authNode) {
        AuthConfig auth = new AuthConfig();
        if (authNode == null || authNode.isMissingNode()) {
            auth.setType("NONE");
            return auth;
        }
        String type = textOrDefault(authNode.path("type"), "noauth");
        auth.setType(mapAuthType(type));
        switch (type) {
            case "bearer" -> auth.setToken(postmanAuthField(authNode, "bearer", "token"));
            case "basic" -> {
                auth.setUsername(postmanAuthField(authNode, "basic", "username"));
                auth.setPassword(postmanAuthField(authNode, "basic", "password"));
            }
            case "apikey" -> {
                auth.setApiKeyName(postmanAuthField(authNode, "apikey", "key"));
                auth.setApiKeyValue(postmanAuthField(authNode, "apikey", "value"));
                String in = postmanAuthField(authNode, "apikey", "in");
                auth.setApiKeyAddTo("query".equalsIgnoreCase(in) ? "QUERY" : "HEADER");
            }
            default -> { /* NONE — nothing further to map */ }
        }
        return auth;
    }

    private String mapAuthType(String postmanType) {
        return switch (postmanType) {
            case "bearer" -> "BEARER";
            case "basic" -> "BASIC";
            case "apikey" -> "API_KEY";
            default -> "NONE";
        };
    }

    private String postmanAuthField(JsonNode authNode, String authType, String fieldKey) {
        for (JsonNode entry : authNode.path(authType)) {
            if (fieldKey.equals(textOrDefault(entry.path("key"), null))) {
                return textOrDefault(entry.path("value"), "");
            }
        }
        return "";
    }

    private String readDescription(JsonNode infoNode) {
        JsonNode desc = infoNode.path("description");
        if (desc.isTextual()) return desc.asText();
        if (desc.isObject()) return textOrDefault(desc.path("content"), null);
        return null;
    }

    private String textOrDefault(JsonNode node, String fallback) {
        return node != null && node.isTextual() && !node.asText().isBlank() ? node.asText() : fallback;
    }

    private JsonNode readJson(MultipartFile file) {
        try {
            return objectMapper.readTree(file.getInputStream());
        } catch (Exception e) {
            throw new ApiTestingException("Could not parse the uploaded Postman collection: " + e.getMessage());
        }
    }
}
