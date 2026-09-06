package com.miniautomation.backend.apitesting.dto;

/** Body of POST /api/api-testing/execute — an ad-hoc single "Send", always run from the CURRENT (possibly unsaved) editor state. */
public class ExecuteRequest {
    private Long requestId;
    private String requestName;
    private Long collectionId;
    private Long environmentId;
    private ApiRequestSpec request;

    public Long getRequestId() { return requestId; }
    public void setRequestId(Long requestId) { this.requestId = requestId; }

    public String getRequestName() { return requestName; }
    public void setRequestName(String requestName) { this.requestName = requestName; }

    public Long getCollectionId() { return collectionId; }
    public void setCollectionId(Long collectionId) { this.collectionId = collectionId; }

    public Long getEnvironmentId() { return environmentId; }
    public void setEnvironmentId(Long environmentId) { this.environmentId = environmentId; }

    public ApiRequestSpec getRequest() { return request; }
    public void setRequest(ApiRequestSpec request) { this.request = request; }
}
