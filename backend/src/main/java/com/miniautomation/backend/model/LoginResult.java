package com.miniautomation.backend.model;

public class LoginResult {

    private String initialUrl;
    private String finalUrl;
    private boolean urlChanged;
    private boolean successTextFound;
    private boolean visibleTextFound;
    private boolean targetElementFound;
    private boolean overallSuccess;
    private String actualPageTitleAfterSubmit;
    private long submitToResultDurationMs;
    private String verificationSummary;

    public LoginResult() {
    }

    public String getInitialUrl() {
        return initialUrl;
    }

    public void setInitialUrl(String initialUrl) {
        this.initialUrl = initialUrl;
    }

    public String getFinalUrl() {
        return finalUrl;
    }

    public void setFinalUrl(String finalUrl) {
        this.finalUrl = finalUrl;
    }

    public boolean isUrlChanged() {
        return urlChanged;
    }

    public void setUrlChanged(boolean urlChanged) {
        this.urlChanged = urlChanged;
    }

    public boolean isSuccessTextFound() {
        return successTextFound;
    }

    public void setSuccessTextFound(boolean successTextFound) {
        this.successTextFound = successTextFound;
    }

    public boolean isVisibleTextFound() {
        return visibleTextFound;
    }

    public void setVisibleTextFound(boolean visibleTextFound) {
        this.visibleTextFound = visibleTextFound;
    }

    public boolean isTargetElementFound() {
        return targetElementFound;
    }

    public void setTargetElementFound(boolean targetElementFound) {
        this.targetElementFound = targetElementFound;
    }

    public boolean isOverallSuccess() {
        return overallSuccess;
    }

    public void setOverallSuccess(boolean overallSuccess) {
        this.overallSuccess = overallSuccess;
    }

    public String getActualPageTitleAfterSubmit() {
        return actualPageTitleAfterSubmit;
    }

    public void setActualPageTitleAfterSubmit(String actualPageTitleAfterSubmit) {
        this.actualPageTitleAfterSubmit = actualPageTitleAfterSubmit;
    }

    public long getSubmitToResultDurationMs() {
        return submitToResultDurationMs;
    }

    public void setSubmitToResultDurationMs(long submitToResultDurationMs) {
        this.submitToResultDurationMs = submitToResultDurationMs;
    }

    public String getVerificationSummary() {
        return verificationSummary;
    }

    public void setVerificationSummary(String verificationSummary) {
        this.verificationSummary = verificationSummary;
    }
}

