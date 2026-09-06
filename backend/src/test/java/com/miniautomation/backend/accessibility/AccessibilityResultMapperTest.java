package com.miniautomation.backend.accessibility;

import com.deque.html.axecore.results.CheckedNode;
import com.deque.html.axecore.results.Rule;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage for AccessibilityResultMapper — the layer that turns raw axe-core
 * Rule/CheckedNode results into this app's persisted DTOs. Uses real
 * Rule/CheckedNode instances (plain Jackson POJOs, not axe-engine behaviour)
 * rather than mocks, since constructing them directly is simpler and more
 * realistic than mocking a data class.
 */
class AccessibilityResultMapperTest {

    private AccessibilityResultMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new AccessibilityResultMapper(new ObjectMapper());
    }

    private Rule ruleWithNodes(String id, String impact, CheckedNode... nodes) {
        Rule rule = new Rule();
        rule.setId(id);
        rule.setDescription("Description for " + id);
        rule.setHelp("Help for " + id);
        rule.setHelpUrl("https://dequeuniversity.com/rules/axe/" + id);
        rule.setImpact(impact);
        rule.setTags(List.of("wcag2a", "wcag412"));
        rule.setNodes(List.of(nodes));
        return rule;
    }

    private CheckedNode node(String html, Object target, String failureSummary) {
        CheckedNode node = new CheckedNode();
        node.setHtml(html);
        node.setTarget(target);
        node.setFailureSummary(failureSummary);
        return node;
    }

    // ── mapFindings ──────────────────────────────────────────────────────

    @Test
    void mapFindings_translatesRuleFieldsAndSingleAffectedNode() {
        Rule rule = ruleWithNodes("button-name", "critical",
                node("<button></button>", List.of("#submit-btn"), "Fix any of the following: Element has no text"));

        List<RuleFindingDto> result = mapper.mapFindings(List.of(rule));

        assertThat(result).hasSize(1);
        RuleFindingDto dto = result.get(0);
        assertThat(dto.getRuleId()).isEqualTo("button-name");
        assertThat(dto.getImpact()).isEqualTo("critical");
        assertThat(dto.getHelpUrl()).contains("button-name");
        assertThat(dto.getTags()).contains("wcag2a", "wcag412");
        assertThat(dto.getNodes()).hasSize(1);
        assertThat(dto.getNodes().get(0).getHtml()).isEqualTo("<button></button>");
        assertThat(dto.getNodes().get(0).getTarget()).isEqualTo("#submit-btn");
        assertThat(dto.getNodes().get(0).getFailureSummary()).contains("no text");
    }

    @Test
    void mapFindings_withMultipleAffectedNodes_mapsEveryNode() {
        Rule rule = ruleWithNodes("color-contrast", "serious",
                node("<p>a</p>", List.of("#a"), "contrast too low"),
                node("<p>b</p>", List.of("#b"), "contrast too low"),
                node("<p>c</p>", List.of("#c"), "contrast too low"));

        List<RuleFindingDto> result = mapper.mapFindings(List.of(rule));

        assertThat(result.get(0).getNodes()).hasSize(3);
        assertThat(result.get(0).getNodes()).extracting(RuleNodeDto::getTarget)
                .containsExactly("#a", "#b", "#c");
    }

    @Test
    void mapFindings_flattensNestedIframeTargetIntoOneSelectorString() {
        // axe represents an element inside an iframe as a nested selector list,
        // e.g. [["iframe#outer"], "#inner-el"] — Jackson deserialises this as
        // nested List<Object>. The mapper must flatten it into one readable string.
        Rule rule = ruleWithNodes("label", "moderate",
                node("<input>", List.of(List.of("iframe#outer"), "#inner-el"), "Form element has no label"));

        List<RuleFindingDto> result = mapper.mapFindings(List.of(rule));

        assertThat(result.get(0).getNodes().get(0).getTarget()).isEqualTo("iframe#outer > #inner-el");
    }

    @Test
    void mapFindings_onNullOrEmptyList_returnsEmptyList() {
        assertThat(mapper.mapFindings(null)).isEmpty();
        assertThat(mapper.mapFindings(List.of())).isEmpty();
    }

    // ── mapPasses ────────────────────────────────────────────────────────

    @Test
    void mapPasses_omitsNodeDetailButKeepsNodeCount() {
        Rule rule = ruleWithNodes("html-has-lang", "minor",
                node("<html>", List.of("html"), null),
                node("<html>", List.of("html"), null));

        List<PassSummaryDto> result = mapper.mapPasses(List.of(rule));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getRuleId()).isEqualTo("html-has-lang");
        assertThat(result.get(0).getNodeCount()).isEqualTo(2);
    }

    // ── countSeverity ────────────────────────────────────────────────────

    @Test
    void countSeverity_bucketsByImpactAcrossAllFourLevels() {
        List<RuleFindingDto> violations = mapper.mapFindings(List.of(
                ruleWithNodes("r1", "critical", node("<a>", List.of("#1"), null)),
                ruleWithNodes("r2", "critical", node("<a>", List.of("#2"), null)),
                ruleWithNodes("r3", "serious", node("<a>", List.of("#3"), null)),
                ruleWithNodes("r4", "moderate", node("<a>", List.of("#4"), null)),
                ruleWithNodes("r5", "minor", node("<a>", List.of("#5"), null))));

        AccessibilityResultMapper.SeverityCounts counts = mapper.countSeverity(violations);

        assertThat(counts.critical).isEqualTo(2);
        assertThat(counts.serious).isEqualTo(1);
        assertThat(counts.moderate).isEqualTo(1);
        assertThat(counts.minor).isEqualTo(1);
    }

    @Test
    void countSeverity_nullOrUnrecognisedImpact_fallsBackToModerate() {
        List<RuleFindingDto> violations = mapper.mapFindings(List.of(
                ruleWithNodes("r1", null, node("<a>", List.of("#1"), null)),
                ruleWithNodes("r2", "unexpected-value", node("<a>", List.of("#2"), null))));

        AccessibilityResultMapper.SeverityCounts counts = mapper.countSeverity(violations);

        assertThat(counts.moderate).isEqualTo(2);
        assertThat(counts.critical).isZero();
    }

    // ── toJson ───────────────────────────────────────────────────────────

    @Test
    void toJson_serializesMappedFindingsToValidJsonArray() {
        List<RuleFindingDto> violations = mapper.mapFindings(List.of(
                ruleWithNodes("button-name", "critical", node("<button></button>", List.of("#btn"), "no text"))));

        String json = mapper.toJson(violations, "[]");

        assertThat(json).contains("\"ruleId\":\"button-name\"").contains("\"impact\":\"critical\"");
    }

    @Test
    void toJson_onSerializationFailure_returnsFallback() {
        // A self-referencing map is not serialisable and triggers a real
        // Jackson exception, exercising the fallback path.
        java.util.Map<String, Object> cyclic = new java.util.HashMap<>();
        cyclic.put("self", cyclic);

        assertThat(mapper.toJson(cyclic, "[]")).isEqualTo("[]");
    }
}
