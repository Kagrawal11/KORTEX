package com.miniautomation.backend.accessibility;

/**
 * One affected DOM element for a violation/incomplete rule finding — the
 * detail needed to actually locate and fix the problem: the element's HTML,
 * its CSS selector path, and axe's plain-English failure summary.
 */
public class RuleNodeDto {

    private String html;
    private String target;
    private String failureSummary;

    public RuleNodeDto() {
    }

    public RuleNodeDto(String html, String target, String failureSummary) {
        this.html = html;
        this.target = target;
        this.failureSummary = failureSummary;
    }

    public String getHtml() { return html; }
    public void setHtml(String html) { this.html = html; }

    public String getTarget() { return target; }
    public void setTarget(String target) { this.target = target; }

    public String getFailureSummary() { return failureSummary; }
    public void setFailureSummary(String failureSummary) { this.failureSummary = failureSummary; }
}
