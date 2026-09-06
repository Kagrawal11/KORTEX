package com.miniautomation.backend.datadriven;

import com.miniautomation.backend.entity.TestStepEntity;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * FieldMappingService — Determines which dataset column maps to which recorded
 * input step using the following deterministic priority chain:
 *
 *   Priority 1 — Exact match on recorded labelText
 *   Priority 2 — Exact match on HTML name attribute
 *   Priority 3 — Exact match on HTML id (elementId)
 *   Priority 4 — Exact match on placeholder
 *   Priority 5 — Normalised (lowercase, trimmed) comparison of any of the above
 *   Priority 6 — Match/label against the nearest PRECEDING step's own recorded
 *                text (e.g. a dropdown's opening trigger, "Select Cancellation
 *                Reason") — a click-based dropdown OPTION step never has a
 *                field label of its own, only the sample value chosen during
 *                recording, so this is often the only usable signal for it
 *   Priority 7 — AI/semantic matching (reserved; not used here as no LLM key needed)
 *
 * Mapping is NEVER silent — any ambiguity is surfaced as a validation error so
 * the user can provide a manual override before execution starts.
 */
@Component
public class FieldMappingService {

    /**
     * Represents one field→column mapping entry.
     */
    public static class FieldMapping {
        private final long stepId;
        private final int stepOrder;
        private final String fieldLabel;       // Human-readable description of the recorded field
        private final String datasetColumn;    // The matching column header from the dataset

        public FieldMapping(long stepId, int stepOrder, String fieldLabel, String datasetColumn) {
            this.stepId = stepId;
            this.stepOrder = stepOrder;
            this.fieldLabel = fieldLabel;
            this.datasetColumn = datasetColumn;
        }

        public long getStepId()       { return stepId; }
        public int getStepOrder()     { return stepOrder; }
        public String getFieldLabel() { return fieldLabel; }
        public String getDatasetColumn() { return datasetColumn; }
    }

    /**
     * Result of a mapping validation — contains resolved mappings AND any
     * unresolved fields that need manual correction.
     */
    public static class MappingResult {
        private final List<FieldMapping> resolved;
        private final List<String> unresolvedFields;   // human-readable field descriptions
        private final boolean valid;

        public MappingResult(List<FieldMapping> resolved, List<String> unresolvedFields) {
            this.resolved = Collections.unmodifiableList(resolved);
            this.unresolvedFields = Collections.unmodifiableList(unresolvedFields);
            this.valid = unresolvedFields.isEmpty();
        }

        public List<FieldMapping> getResolved()      { return resolved; }
        public List<String> getUnresolvedFields()    { return unresolvedFields; }
        public boolean isValid()                      { return valid; }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Returns inputStepsInRange plus any additional step isInputStep() misses
     * but that qualifies via isColumnDrivenCandidate or a value match — the
     * full set of steps a mapping (auto-resolved OR manually chosen by the
     * user) should be allowed to target.
     *
     * Exposed publicly and used by BOTH resolveMapping (for auto-resolve)
     * and the caller's own manual-mapping validation (e.g.
     * DataDrivenService.validateClientMappings) — a step correctly offered
     * as mappable on the UI but missing from THIS list would otherwise be
     * rejected as "not an input step in the selected range" the moment the
     * user actually tries to run it, even though the mapping screen showed
     * it as valid. Confirmed as a real gap while fixing the underlying
     * classification issue: the two call sites must use the identical
     * candidate set or they silently diverge.
     */
    public List<TestStepEntity> buildCandidateSteps(
            List<TestStepEntity> inputStepsInRange,
            List<TestStepEntity> allStepsInRange,
            List<Map<String, String>> sampleRows,
            List<String> datasetHeaders) {

        List<TestStepEntity> candidates = new ArrayList<>(inputStepsInRange);
        if (allStepsInRange == null) {
            return candidates;
        }

        Set<Long> alreadyIncluded = new HashSet<>();
        for (TestStepEntity s : inputStepsInRange) {
            if (s.getId() != null) alreadyIncluded.add(s.getId());
        }
        for (TestStepEntity step : allStepsInRange) {
            if (step.getId() != null && alreadyIncluded.contains(step.getId())) continue;
            boolean isCandidate = isColumnDrivenCandidate(step, allStepsInRange, datasetHeaders)
                    || valueMatchedColumn(step, sampleRows, datasetHeaders) != null;
            if (isCandidate) {
                candidates.add(step);
                if (step.getId() != null) alreadyIncluded.add(step.getId());
            }
        }
        candidates.sort(Comparator.comparingInt(TestStepEntity::getStepOrder));
        return candidates;
    }

    /**
     * Automatically resolves mappings between dataset columns and input steps
     * within the selected step range using the priority chain described above.
     *
     * @param inputStepsInRange  Only the steps within [startStepOrder, endStepOrder]
     *                           that have actionType input/change/type/fill.
     * @param datasetHeaders     Column headers from the parsed dataset.
     * @param manualOverrides    Optional user-provided column name per stepId —
     *                           e.g. {"42": "Email Address"}.  Takes highest priority.
     */
    public MappingResult resolveMapping(
            List<TestStepEntity> inputStepsInRange,
            List<String> datasetHeaders,
            Map<String, String> manualOverrides) {
        return resolveMapping(inputStepsInRange, inputStepsInRange, null, datasetHeaders, manualOverrides);
    }

    /**
     * @param allStepsInRange ALL steps (input-classified or not) in the same
     *                        loop range as inputStepsInRange, used only to
     *                        look up context for a step whose own recorded
     *                        metadata is blank — see findPrecedingDescriptiveText.
     *                        Pass the same list as inputStepsInRange if that
     *                        context isn't available; matching/labelling
     *                        simply falls back to the step's own fields only.
     */
    public MappingResult resolveMapping(
            List<TestStepEntity> inputStepsInRange,
            List<TestStepEntity> allStepsInRange,
            List<String> datasetHeaders,
            Map<String, String> manualOverrides) {
        return resolveMapping(inputStepsInRange, allStepsInRange, null, datasetHeaders, manualOverrides);
    }

    /**
     * @param sampleRows Actual dataset row values (a few rows is enough —
     *                    the preview subset, not necessarily every row), used
     *                    to recognise a step whose recorded SAMPLE value
     *                    (e.g. "Borrower Deathh") matches an actual cell
     *                    value in one of the columns — see
     *                    isValueDrivenCandidate. Pass null/empty if
     *                    unavailable; matching simply skips this signal.
     */
    public MappingResult resolveMapping(
            List<TestStepEntity> inputStepsInRange,
            List<TestStepEntity> allStepsInRange,
            List<Map<String, String>> sampleRows,
            List<String> datasetHeaders,
            Map<String, String> manualOverrides) {

        List<FieldMapping> resolved   = new ArrayList<>();
        List<String> unresolved       = new ArrayList<>();

        // Build a case-insensitive lookup set of column names
        Map<String, String> columnByNormalised = buildNormalisedColumnMap(datasetHeaders);

        List<TestStepEntity> candidates =
                buildCandidateSteps(inputStepsInRange, allStepsInRange, sampleRows, datasetHeaders);

        for (TestStepEntity step : candidates) {
            boolean isColumnCandidate = isColumnDrivenCandidate(step, allStepsInRange, datasetHeaders);
            String valueMatch = valueMatchedColumn(step, sampleRows, datasetHeaders);
            if (!isInputStep(step) && !isColumnCandidate && valueMatch == null) continue;

            String contextualLabel = findPrecedingDescriptiveText(step, allStepsInRange);
            String fieldLabel = buildFieldLabel(step, contextualLabel);
            String stepKey    = String.valueOf(step.getId());

            // ── Priority 0: manual override ──────────────────────────────
            if (manualOverrides != null && manualOverrides.containsKey(stepKey)) {
                String colName = manualOverrides.get(stepKey);
                if (datasetHeaders.contains(colName)) {
                    resolved.add(new FieldMapping(step.getId(), step.getStepOrder(), fieldLabel, colName));
                    continue;
                }
            }

            // ── Priority 0.5: the step's own recorded sample text matches an
            // actual cell value in this column — the most direct signal
            // available, since it doesn't depend on any OTHER step having
            // captured usable text at all.
            if (valueMatch != null) {
                resolved.add(new FieldMapping(step.getId(), step.getStepOrder(), fieldLabel, valueMatch));
                continue;
            }

            String matched = tryMatch(step, contextualLabel, datasetHeaders, columnByNormalised);
            if (matched != null) {
                resolved.add(new FieldMapping(step.getId(), step.getStepOrder(), fieldLabel, matched));
            } else {
                unresolved.add(fieldLabel + " (step " + step.getStepOrder() + ")");
            }
        }

        return new MappingResult(resolved, unresolved);
    }

    /**
     * A click-based dropdown OPTION step (e.g. the actual cancellation
     * reason clicked in a list) never has a field label of its own — its
     * recorded labelText/name/elementId/placeholder are all blank, because
     * the only thing the recorder captured for it is the SAMPLE value that
     * happened to be clicked (e.g. "Borrower Deathh"), not a field name.
     * Confirmed on a real CGT recording: this left the field showing as a
     * raw CSS selector in the mapping UI and impossible to auto-match,
     * which looked to the user like the field had vanished entirely even
     * though the underlying step was correctly classified as mappable.
     *
     * The IMMEDIATELY PRECEDING step (by stepOrder) is very often the
     * dropdown's own opening trigger (e.g. "Select Cancellation Reason",
     * clicked immediately before the option list appears) — a much more
     * useful label, and, when the dataset column happens to share that
     * exact name, a genuine correct auto-match too.
     *
     * Only that ONE immediate predecessor is consulted — never a more
     * distant earlier step. An earlier version of this method skipped over
     * any preceding step with blank recorded text to find the nearest one
     * with SOME non-blank text, no matter how far back that was. Confirmed
     * on a real recording that this actively misfires: a PrimeNG dropdown
     * trigger whose visible label is empty until a value is chosen (so its
     * own recorded text is blank) caused the lookup to walk straight past
     * it to an unrelated, several-steps-earlier menu click (e.g.
     * "Cancellation / Surrender") and treat THAT text as this option's
     * context — wrong context is worse than no context, since it can
     * silently suppress a correct manual-mapping opportunity or, on an
     * unlucky dataset, produce a bogus auto-match. If the immediate
     * predecessor's own text is blank, its labelText/ariaLabel/placeholder
     * are tried instead (still anchored to that SAME step); if all of
     * those are blank too, this returns null rather than guessing further.
     */
    private String findPrecedingDescriptiveText(TestStepEntity step, List<TestStepEntity> allStepsInRange) {
        if (allStepsInRange == null) return null;
        TestStepEntity immediatePredecessor = null;
        for (TestStepEntity candidate : allStepsInRange) {
            if (candidate == null || candidate.getId() == null) continue;
            if (Objects.equals(candidate.getId(), step.getId())) continue;
            if (candidate.getStepOrder() >= step.getStepOrder()) continue;
            if (immediatePredecessor == null || candidate.getStepOrder() > immediatePredecessor.getStepOrder()) {
                immediatePredecessor = candidate;
            }
        }
        if (immediatePredecessor == null) return null;
        if (immediatePredecessor.getText() != null && !immediatePredecessor.getText().isBlank()) {
            return immediatePredecessor.getText().trim();
        }
        if (immediatePredecessor.getLabelText() != null && !immediatePredecessor.getLabelText().isBlank()) {
            return immediatePredecessor.getLabelText().trim();
        }
        if (immediatePredecessor.getAriaLabel() != null && !immediatePredecessor.getAriaLabel().isBlank()) {
            return immediatePredecessor.getAriaLabel().trim();
        }
        if (immediatePredecessor.getPlaceholder() != null && !immediatePredecessor.getPlaceholder().isBlank()) {
            return immediatePredecessor.getPlaceholder().trim();
        }
        return null;
    }

    /**
     * Catches a dropdown option step that isInputStep()'s role/selector-shape
     * checks miss entirely. Confirmed on two real recordings of the SAME
     * logical action ("click the Borrower Death reason in the dropdown"):
     * one captured it as {@code <li role="option">} (isInputStep() correctly
     * matches via the role check), the other — depending on exactly which
     * DOM element inside the option received the click — captured it as a
     * bare {@code <span>} with no ARIA role and no vendor-specific selector
     * shape at all, which isInputStep() cannot distinguish from a genuinely
     * fixed, always-the-same menu click (e.g. "Cancellation / Surrender",
     * recorded identically as a plain {@code <span>}/{@code <li>}). No
     * amount of tweaking the structural heuristic can reliably tell these
     * apart from recorded metadata alone.
     *
     * The dataset's own column names are a much stronger, low-risk signal:
     * if this step's nearest PRECEDING step's text (its likely dropdown
     * trigger, e.g. "Select Cancellation Reason") matches a column name the
     * user has already told us about, this step is almost certainly the
     * per-row selection for that column — anchored to information the user
     * explicitly provided, not a generic structural guess, so it is very
     * unlikely to misfire on an unrelated fixed click (whose own preceding
     * trigger's text, if any, won't happen to match a column name).
     */
    boolean isColumnDrivenCandidate(TestStepEntity step,
                                     List<TestStepEntity> allStepsInRange,
                                     List<String> datasetHeaders) {
        if (step == null || isInputStep(step)) return false;
        if (!"click".equalsIgnoreCase(step.getActionType())) return false;
        if (datasetHeaders == null || datasetHeaders.isEmpty()) return false;

        String contextualLabel = findPrecedingDescriptiveText(step, allStepsInRange);
        if (contextualLabel == null || contextualLabel.isBlank()) return false;

        String normalisedContext = normaliseHeader(contextualLabel);
        for (String header : datasetHeaders) {
            if (header == null) continue;
            if (contextualLabel.equals(header.trim()) || normalisedContext.equals(normaliseHeader(header))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The most direct, robust signal available for a click-based dropdown
     * OPTION step: does its OWN recorded sample text (e.g. "Borrower
     * Deathh") match an actual cell VALUE somewhere in the dataset? Unlike
     * isColumnDrivenCandidate (which needs some nearby step to have
     * captured a column-NAME-like text), this needs nothing from any other
     * step — confirmed necessary on a real recording where even the
     * dropdown's own opening trigger was captured with blank text (likely
     * because the click landed on an element whose own innerText was
     * empty, e.g. an icon/arrow within the trigger). The recorded sample is
     * near-certainly one of the values that column can actually hold, so a
     * match here is very unlikely to be coincidental — column values tend
     * to be domain-specific (an ID-like "CG20256357590TL" for a CGPAN
     * column is not going to coincidentally equal a reason-text sample).
     *
     * Returns the matched column name, or null if the step's text is blank
     * or doesn't match any provided sample value. Only the first matching
     * column is returned if more than one happens to match — genuinely
     * ambiguous cases are rare enough not to warrant more than that.
     */
    private String valueMatchedColumn(TestStepEntity step,
                                       List<Map<String, String>> sampleRows,
                                       List<String> datasetHeaders) {
        if (step == null || step.getText() == null || step.getText().isBlank()) return null;
        if (sampleRows == null || sampleRows.isEmpty()) return null;
        if (datasetHeaders == null || datasetHeaders.isEmpty()) return null;

        String needle = step.getText().trim();
        String needleNormalised = normaliseHeader(needle);

        for (String header : datasetHeaders) {
            if (header == null) continue;
            for (Map<String, String> row : sampleRows) {
                if (row == null) continue;
                String cell = row.get(header);
                if (cell == null || cell.isBlank()) continue;
                String cellTrimmed = cell.trim();
                if (cellTrimmed.equalsIgnoreCase(needle) || normaliseHeader(cellTrimmed).equals(needleNormalised)) {
                    return header;
                }
            }
        }
        return null;
    }

    /**
     * Looks up the dataset column value for a given step from the resolved mapping list.
     * Returns null if the step has no mapping (non-input steps, excluded steps, etc.).
     */
    public String getColumnForStep(long stepId, List<FieldMapping> mappings) {
        for (FieldMapping m : mappings) {
            if (m.getStepId() == stepId) return m.getDatasetColumn();
        }
        return null;
    }

    // ──────────────────────────────────────────────────────────────────────
    // Matching logic
    // ──────────────────────────────────────────────────────────────────────

    private String tryMatch(TestStepEntity step,
                            String contextualLabel,
                            List<String> columns,
                            Map<String, String> normalisedMap) {

        // Priority 1 — exact labelText
        String match = exactMatch(step.getLabelText(), columns);
        if (match != null) return match;

        // Priority 2 — exact HTML name
        match = exactMatch(step.getName(), columns);
        if (match != null) return match;

        // Priority 3 — exact HTML id
        match = exactMatch(step.getElementId(), columns);
        if (match != null) return match;

        // Priority 4 — exact placeholder
        match = exactMatch(step.getPlaceholder(), columns);
        if (match != null) return match;

        // Priority 5 — normalised comparison (all of the above, case-insensitive, trimmed)
        match = normalisedMatch(step.getLabelText(), normalisedMap);
        if (match != null) return match;
        match = normalisedMatch(step.getName(), normalisedMap);
        if (match != null) return match;
        match = normalisedMatch(step.getElementId(), normalisedMap);
        if (match != null) return match;
        match = normalisedMatch(step.getPlaceholder(), normalisedMap);
        if (match != null) return match;

        // Priority 6 — the nearest preceding step's own recorded text (e.g. a
        // dropdown's opening trigger, "Select Cancellation Reason"). A click
        // step that IS the actual per-row selection never has a field label
        // of its own — only the sample value chosen during recording — so
        // this context is often the only way to auto-match it at all.
        match = exactMatch(contextualLabel, columns);
        if (match != null) return match;
        match = normalisedMatch(contextualLabel, normalisedMap);
        if (match != null) return match;

        // Priority 7 — normalised comparison against original input value (last resort heuristic)
        // (inputValue is the value typed during recording — not normally a field name,
        //  but a useful fallback for simple demos where the field label equals the sample value)
        // Intentionally NOT implemented — too risky, prefer manual mapping.

        return null; // unresolved
    }

    private String exactMatch(String fieldAttr, List<String> columns) {
        if (fieldAttr == null || fieldAttr.isBlank()) return null;
        String trimmed = fieldAttr.trim();
        for (String col : columns) {
            if (col.equals(trimmed)) return col;
        }
        return null;
    }

    private String normalisedMatch(String fieldAttr, Map<String, String> normalisedMap) {
        if (fieldAttr == null || fieldAttr.isBlank()) return null;
        String key = normaliseHeader(fieldAttr);
        return normalisedMap.get(key); // returns original column name or null
    }

    // ──────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────

    /** Builds a map from normalised column name → original column name. */
    private Map<String, String> buildNormalisedColumnMap(List<String> columns) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String col : columns) {
            map.put(normaliseHeader(col), col);
        }
        return map;
    }

    /**
     * Lowercase + strip non-alphanumeric characters for fuzzy matching.
     * Public/static so {@link DatasetParser}'s duplicate-header check can use
     * the exact same normalisation this class uses for column matching —
     * previously the parser only lowercased headers for its dedup check, so
     * two headers that passed that (looser) check could still collide once
     * this (stricter) normalisation ran during mapping.
     */
    public static String normaliseHeader(String s) {
        if (s == null) return "";
        return s.toLowerCase().replaceAll("[^a-z0-9]", "").trim();
    }

    /** Returns a human-readable label for a step's target field. */
    private String buildFieldLabel(TestStepEntity step, String contextualLabel) {
        if (step.getLabelText() != null && !step.getLabelText().isBlank()) return step.getLabelText().trim();
        if (step.getPlaceholder() != null && !step.getPlaceholder().isBlank()) return step.getPlaceholder().trim();
        if (step.getName() != null && !step.getName().isBlank()) return step.getName().trim();
        if (step.getElementId() != null && !step.getElementId().isBlank()) return step.getElementId().trim();
        boolean hasContext = contextualLabel != null && !contextualLabel.isBlank();
        boolean hasSample  = step.getText() != null && !step.getText().isBlank();
        if (hasContext && hasSample) return contextualLabel + " (e.g. " + step.getText().trim() + ")";
        if (hasContext) return contextualLabel;
        if (hasSample) return step.getText().trim();
        return "Field at selector: " + step.getPrimarySelector();
    }

    /** Returns true if the step's action type is a data-injectable action.
     *  Includes: text-input types AND click-based dropdown/select steps
     *  (identified by having a non-null inputValue on a click step, or by a
     *  selector that structurally looks like a dropdown/listbox option).
     */
    boolean isInputStep(TestStepEntity step) {
        if (step == null || step.getActionType() == null) return false;
        String action = step.getActionType().toLowerCase();
        if (action.equals("input") || action.equals("change")
                || action.equals("type")  || action.equals("fill")) {
            return true;
        }
        // Treat click steps as input steps when they are clearly dropdown-option
        // selections. Previously this also matched any click whose selector,
        // label, or placeholder merely CONTAINED the word "select" — a loose
        // string check that both (a) misclassified ordinary buttons with
        // "select" somewhere in an id/label as data-mappable, and (b) had
        // nothing to do with whether the step was actually a dropdown option.
        // PlaybackEngine.cloneWithOverride() now unconditionally rewrites the
        // selector for anything this method returns true for, so tightening
        // the check here is what actually prevents a plain button from being
        // silently rewired into a dropdown-option click.
        if (action.equals("click")) {
            if (step.getInputValue() != null && !step.getInputValue().isBlank()) {
                return true;
            }
            // Strongest, most reliable signal: the browser itself marked this
            // element with the ARIA "option" role at recording time, meaning
            // it is a genuine, semantically-selectable choice within a
            // listbox/combobox — not just an element that happens to LOOK
            // like one structurally. This is what actually distinguishes a
            // per-row VARYING field (e.g. "Select Cancellation Reason" →
            // a specific reason, different per data row) from a FIXED,
            // always-the-same workflow choice rendered via a similarly-
            // shaped element (e.g. an action-type menu item like
            // "Cancellation / Surrender", whose own recorded role was just
            // "li" — no real ARIA role). A bare selector-shape check alone
            // cannot tell these apart, since both can be recorded as an
            // <li> with a li:has-text(...) selector — exactly the false
            // positive that previously caused a 2-column dataset to be
            // offered 3 mapping slots (the fixed menu choice got treated as
            // a mappable field and, once the user worked around the extra
            // slot by mapping *something* to it, received a per-row data
            // override it was never meant to have).
            if ("option".equalsIgnoreCase(step.getRole())) {
                return true;
            }
            return looksLikeDropdownOptionSelector(step.getPrimarySelector());
        }
        return false;
    }

    /**
     * Fallback structural check, used only when {@code isInputStep} couldn't
     * rely on the step's recorded ARIA role (see there). Deliberately does
     * NOT match bare {@code li[...]}/{@code li:has-text(...)} or bare
     * {@code p-dropdown}/{@code p-multiselect} — those match an ordinary
     * fixed-choice action-menu item (e.g. "Cancellation / Surrender") just as
     * readily as a real dropdown option, which is exactly the false positive
     * that previously caused a 2-column dataset to be offered a 3rd, bogus
     * mapping slot. Only class names that are specific to option/item/panel
     * CONTENT (never a component's own trigger wrapper) are matched here.
     *
     * The tradeoff: a real dropdown option recorded with none of these shapes
     * AND no ARIA "option" role (a custom, unstyled option element) will not
     * be auto-detected; such a step currently has no manual-mapping escape
     * hatch either (getInputStepsInRange filters candidates through this same
     * check before they reach the mapping UI), which is a known follow-up,
     * not something this method can fix alone.
     *
     * Confirmed missing on a real recording: PrimeNG renders each dropdown
     * option as a {@code <p-dropdownitem>} custom element (no hyphen between
     * "dropdown" and "item" — it's a component tag name, not a CSS class),
     * which none of the hyphenated patterns above match. Added the
     * unhyphenated forms for the whole PrimeNG option-bearing component
     * family (Dropdown/MultiSelect/Listbox/SelectItem all follow the same
     * `p-<widget>item` custom-tag convention) so any of that library's list-
     * style widgets are recognised, not just the one hit so far.
     */
    private boolean looksLikeDropdownOptionSelector(String selector) {
        if (selector == null || selector.isBlank()) return false;
        String s = selector.toLowerCase();
        return s.contains("mat-option")
            || s.contains("dropdown-item") || s.contains("dropdownitem")
            || s.contains("p-multiselect-item") || s.contains("multiselectitem")
            || s.contains("selectitem") || s.contains("listboxitem")
            || s.contains("role='option'") || s.contains("role=\"option\"")
            || s.contains("listbox")
            || s.contains("option[") || s.startsWith("option:");
    }
}
