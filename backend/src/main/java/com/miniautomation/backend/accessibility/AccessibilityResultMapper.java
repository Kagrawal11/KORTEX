package com.miniautomation.backend.accessibility;

import com.deque.html.axecore.results.CheckedNode;
import com.deque.html.axecore.results.Rule;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Converts raw axe-core {@link Rule} results into this app's DTOs, computes
 * severity counts, and serialises to the JSON strings persisted on
 * {@code AccessibilityScanRunEntity}.
 *
 * Severity counts are RULE counts (how many distinct axe rules were violated
 * at each impact level), not a sum of affected elements — each individual
 * violation already shows its own affected-element count on the node list,
 * so the summary card answers "how many distinct problems", not "how many
 * elements are touched".
 */
@Component
public class AccessibilityResultMapper {

    private final ObjectMapper objectMapper;

    public AccessibilityResultMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<RuleFindingDto> mapFindings(List<Rule> rules) {
        List<RuleFindingDto> result = new ArrayList<>();
        if (rules == null) {
            return result;
        }
        for (Rule rule : rules) {
            RuleFindingDto dto = new RuleFindingDto();
            dto.setRuleId(rule.getId());
            dto.setDescription(rule.getDescription());
            dto.setHelp(rule.getHelp());
            dto.setHelpUrl(rule.getHelpUrl());
            dto.setImpact(rule.getImpact());
            dto.setTags(rule.getTags() != null ? rule.getTags() : Collections.emptyList());

            List<RuleNodeDto> nodes = new ArrayList<>();
            if (rule.getNodes() != null) {
                for (CheckedNode node : rule.getNodes()) {
                    nodes.add(new RuleNodeDto(
                            node.getHtml(),
                            flattenTarget(node.getTarget()),
                            node.getFailureSummary()));
                }
            }
            dto.setNodes(nodes);
            result.add(dto);
        }
        return result;
    }

    public List<PassSummaryDto> mapPasses(List<Rule> rules) {
        List<PassSummaryDto> result = new ArrayList<>();
        if (rules == null) {
            return result;
        }
        for (Rule rule : rules) {
            PassSummaryDto dto = new PassSummaryDto();
            dto.setRuleId(rule.getId());
            dto.setDescription(rule.getDescription());
            dto.setImpact(rule.getImpact());
            dto.setTags(rule.getTags() != null ? rule.getTags() : Collections.emptyList());
            dto.setNodeCount(rule.getNodes() != null ? rule.getNodes().size() : 0);
            result.add(dto);
        }
        return result;
    }

    /** Rule-count breakdown of a mapped violations list by impact level. */
    public SeverityCounts countSeverity(List<RuleFindingDto> violations) {
        SeverityCounts counts = new SeverityCounts();
        if (violations == null) {
            return counts;
        }
        for (RuleFindingDto finding : violations) {
            String impact = finding.getImpact() != null ? finding.getImpact().toLowerCase() : "moderate";
            switch (impact) {
                case "critical" -> counts.critical++;
                case "serious" -> counts.serious++;
                case "minor" -> counts.minor++;
                default -> counts.moderate++; // "moderate" and any unrecognised value
            }
        }
        return counts;
    }

    /**
     * axe-core's node "target" is a CSS selector path — normally a single
     * string wrapped in a list (e.g. {@code ["#login-button"]}), but can be a
     * nested list of selector "frames" when the element lives inside an
     * iframe. Jackson deserialises this loosely-typed field as nested
     * List/String structures; this flattens whatever shape comes back into
     * one human-readable selector string instead of exposing the raw
     * structure in the UI.
     */
    @SuppressWarnings("unchecked")
    private String flattenTarget(Object target) {
        if (target == null) {
            return "";
        }
        if (target instanceof List<?> list) {
            List<String> parts = new ArrayList<>();
            for (Object item : list) {
                String flattened = flattenTarget(item);
                if (!flattened.isBlank()) {
                    parts.add(flattened);
                }
            }
            return String.join(" > ", parts);
        }
        return String.valueOf(target);
    }

    public String toJson(Object value, String fallback) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    /** Plain rule-count holder — deliberately not a record to match this codebase's Lombok/record-free style. */
    public static class SeverityCounts {
        public int critical;
        public int serious;
        public int moderate;
        public int minor;
    }
}
