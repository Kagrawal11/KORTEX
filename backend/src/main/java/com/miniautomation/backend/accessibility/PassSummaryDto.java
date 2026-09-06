package com.miniautomation.backend.accessibility;

import java.util.List;

/**
 * Lightweight summary of one PASSED axe rule — deliberately omits per-node
 * HTML/selector detail (unlike {@link RuleFindingDto}) since a page can pass
 * dozens of rules across hundreds of elements and nothing in the product
 * needs to inspect an individual passing check; only the rule identity and
 * how many elements passed it.
 */
public class PassSummaryDto {

    private String ruleId;
    private String description;
    private String impact;
    private List<String> tags;
    private int nodeCount;

    public PassSummaryDto() {
    }

    public String getRuleId() { return ruleId; }
    public void setRuleId(String ruleId) { this.ruleId = ruleId; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getImpact() { return impact; }
    public void setImpact(String impact) { this.impact = impact; }

    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }

    public int getNodeCount() { return nodeCount; }
    public void setNodeCount(int nodeCount) { this.nodeCount = nodeCount; }
}
