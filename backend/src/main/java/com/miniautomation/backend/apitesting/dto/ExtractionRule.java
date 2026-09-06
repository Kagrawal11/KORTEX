package com.miniautomation.backend.apitesting.dto;

/**
 * Extracts one value from a response and makes it available as a variable —
 * the mechanism behind request chaining (e.g. extract {@code $.token} from a
 * login response, save as {@code {{token}}}, use it in the next request's
 * Authorization header).
 *
 * source = JSON_PATH | HEADER | STATUS_CODE. For JSON_PATH, {@code path} is
 * a JSONPath expression; for HEADER, {@code path} is the header name.
 *
 * saveTo = RUNTIME | ENVIRONMENT. RUNTIME makes the variable available only
 * to later requests within the SAME run (never persisted beyond it).
 * ENVIRONMENT additionally writes the value back onto the run's environment,
 * so it survives for future runs — an explicit opt-in per extraction rule,
 * never an implicit side effect of running a request.
 */
public class ExtractionRule {
    private String source = "JSON_PATH";
    private String path;
    private String variableName;
    private String saveTo = "RUNTIME";

    public ExtractionRule() {
    }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public String getVariableName() { return variableName; }
    public void setVariableName(String variableName) { this.variableName = variableName; }

    public String getSaveTo() { return saveTo; }
    public void setSaveTo(String saveTo) { this.saveTo = saveTo; }
}
