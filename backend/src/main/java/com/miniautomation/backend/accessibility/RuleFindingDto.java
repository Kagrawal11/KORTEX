package com.miniautomation.backend.accessibility;

import java.util.List;

/**
 * A single axe-core rule result — used for both "violations" and
 * "incomplete" (needs manual review), which share the exact same shape in
 * axe's own output. {@code impact} is one of critical/serious/moderate/minor
 * for violations, and may be null for some incomplete results (axe does not
 * always assign an impact before a human confirms the finding).
 */
public class RuleFindingDto {

    private String ruleId;
    private String description;
    private String help;
    private String helpUrl;
    private String impact;
    private List<String> tags;
    private List<RuleNodeDto> nodes;

    public RuleFindingDto() {
    }

    public String getRuleId() { return ruleId; }
    public void setRuleId(String ruleId) { this.ruleId = ruleId; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getHelp() { return help; }
    public void setHelp(String help) { this.help = help; }

    public String getHelpUrl() { return helpUrl; }
    public void setHelpUrl(String helpUrl) { this.helpUrl = helpUrl; }

    public String getImpact() { return impact; }
    public void setImpact(String impact) { this.impact = impact; }

    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }

    public List<RuleNodeDto> getNodes() { return nodes; }
    public void setNodes(List<RuleNodeDto> nodes) { this.nodes = nodes; }
}
