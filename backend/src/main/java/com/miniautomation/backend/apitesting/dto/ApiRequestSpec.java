package com.miniautomation.backend.apitesting.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * The full, flattened shape of one API request — used both as the request
 * body for an ad-hoc "Send" (so the workspace can execute whatever is
 * currently in the editor, saved or not) and as the typed view of a saved
 * {@code ApiRequestEntity}'s JSON columns at the REST layer. The entity
 * itself keeps these as JSON text columns (see its class javadoc for why);
 * this type is what actually gets serialised to/from that JSON.
 */
public class ApiRequestSpec {
    private String name;
    private String method = "GET";
    private String url = "";
    private List<KeyValueItem> params = new ArrayList<>();
    private List<KeyValueItem> headers = new ArrayList<>();
    private String bodyType = "NONE";
    private String bodyContent = "";
    private List<KeyValueItem> formFields = new ArrayList<>();
    private String authType = "INHERIT";
    private AuthConfig auth = new AuthConfig();
    private List<AssertionDefinition> assertions = new ArrayList<>();
    private List<PreRequestVariable> preRequestVars = new ArrayList<>();
    private List<ExtractionRule> extractions = new ArrayList<>();

    public ApiRequestSpec() {
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public List<KeyValueItem> getParams() { return params; }
    public void setParams(List<KeyValueItem> params) { this.params = params; }

    public List<KeyValueItem> getHeaders() { return headers; }
    public void setHeaders(List<KeyValueItem> headers) { this.headers = headers; }

    public String getBodyType() { return bodyType; }
    public void setBodyType(String bodyType) { this.bodyType = bodyType; }

    public String getBodyContent() { return bodyContent; }
    public void setBodyContent(String bodyContent) { this.bodyContent = bodyContent; }

    public List<KeyValueItem> getFormFields() { return formFields; }
    public void setFormFields(List<KeyValueItem> formFields) { this.formFields = formFields; }

    public String getAuthType() { return authType; }
    public void setAuthType(String authType) { this.authType = authType; }

    public AuthConfig getAuth() { return auth; }
    public void setAuth(AuthConfig auth) { this.auth = auth; }

    public List<AssertionDefinition> getAssertions() { return assertions; }
    public void setAssertions(List<AssertionDefinition> assertions) { this.assertions = assertions; }

    public List<PreRequestVariable> getPreRequestVars() { return preRequestVars; }
    public void setPreRequestVars(List<PreRequestVariable> preRequestVars) { this.preRequestVars = preRequestVars; }

    public List<ExtractionRule> getExtractions() { return extractions; }
    public void setExtractions(List<ExtractionRule> extractions) { this.extractions = extractions; }
}
