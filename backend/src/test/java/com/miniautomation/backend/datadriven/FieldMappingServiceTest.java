package com.miniautomation.backend.datadriven;

import com.miniautomation.backend.entity.TestStepEntity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage for FieldMappingService.isInputStep()'s tightened dropdown-click
 * heuristic (previously any click whose selector/label/placeholder merely
 * CONTAINED "select" was treated as data-mappable — a plain button with
 * "select" in its id was a false positive, and PlaybackEngine.cloneWithOverride
 * unconditionally trusts this classification to decide whether to rewrite a
 * step's selector) and for the column-matching priority chain.
 */
class FieldMappingServiceTest {

    private final FieldMappingService service = new FieldMappingService();

    private TestStepEntity step(Long id, String actionType) {
        TestStepEntity s = new TestStepEntity();
        s.setId(id);
        s.setStepOrder(id != null ? id.intValue() : 0);
        s.setActionType(actionType);
        return s;
    }

    // ── isInputStep ─────────────────────────────────────────────────────────

    @Test
    void textEntryActionTypes_areAlwaysInputSteps() {
        for (String action : List.of("input", "change", "type", "fill")) {
            TestStepEntity s = step(1L, action);
            assertThat(service.isInputStep(s)).as("action=" + action).isTrue();
        }
    }

    @Test
    void clickWithNonBlankInputValue_isInputStep() {
        TestStepEntity s = step(1L, "click");
        s.setInputValue("Savings Account");
        s.setPrimarySelector("div.trigger");

        assertThat(service.isInputStep(s)).isTrue();
    }

    @Test
    void clickWithAriaOptionRole_isInputStep_evenWithoutInputValue() {
        // Matches how a real dropdown OPTION is actually recorded: the
        // browser marks it with role="option" (e.g. li[aria-label="Borrower
        // Death"], role="option") — the strongest, most reliable signal that
        // this is a genuine per-row-selectable choice.
        TestStepEntity s = step(1L, "click");
        s.setPrimarySelector("li[aria-label=\"Borrower Death\"]");
        s.setRole("option");

        assertThat(service.isInputStep(s)).isTrue();
    }

    @Test
    void clickOnFixedActionMenuItem_isNotInputStep_evenWithLiHasTextSelector() {
        // Regression test for a real production bug: a FIXED, always-the-
        // same workflow-choice menu item (e.g. selecting "Cancellation /
        // Surrender" as the type of action to perform) can be recorded with
        // the exact same li:has-text(...) selector shape as a genuine
        // per-row dropdown OPTION — the only difference observed in practice
        // was the recorded role: a real option gets role="option", while this
        // kind of plain action-menu item gets role="li" (just the tag name,
        // no real ARIA semantics). Previously the bare selector-shape match
        // alone caused this to be misclassified as a mappable field, which
        // surfaced as a 2-column dataset being offered 3 mapping slots.
        TestStepEntity s = step(1L, "click");
        s.setPrimarySelector("li:has-text(\"Cancellation / Surrender\")");
        s.setRole("li");
        s.setText("Cancellation / Surrender");

        assertThat(service.isInputStep(s)).isFalse();
    }

    @Test
    void clickOnAriaOptionRole_isInputStep() {
        TestStepEntity s = step(1L, "click");
        s.setPrimarySelector("[role='option']:has-text(\"Gold\")");

        assertThat(service.isInputStep(s)).isTrue();
    }

    @Test
    void clickOnMatOptionOrDropdownItem_isInputStep() {
        TestStepEntity matOption = step(1L, "click");
        matOption.setPrimarySelector("mat-option:has-text(\"Silver\")");
        assertThat(service.isInputStep(matOption)).isTrue();

        TestStepEntity dropdownItem = step(2L, "click");
        dropdownItem.setPrimarySelector(".dropdown-item:has-text(\"Bronze\")");
        assertThat(service.isInputStep(dropdownItem)).isTrue();
    }

    @Test
    void plainButtonWithSelectInLabel_isNoLongerFalselyClassifiedAsInputStep() {
        // Previously: any click whose selector/label/placeholder CONTAINED
        // "select" (e.g. a "Select Plan" button, id="selectPlanBtn") was
        // treated as a mappable dropdown-option step. This is exactly the
        // false-positive this heuristic was tightened to avoid.
        TestStepEntity s = step(1L, "click");
        s.setPrimarySelector("#selectPlanBtn");
        s.setLabelText("Select Plan");

        assertThat(service.isInputStep(s)).isFalse();
    }

    @Test
    void plainClickWithGenericSelector_isNotInputStep() {
        TestStepEntity s = step(1L, "click");
        s.setPrimarySelector("button#submit");

        assertThat(service.isInputStep(s)).isFalse();
    }

    @Test
    void nonInputActionTypes_areNeverInputSteps() {
        for (String action : List.of("navigate", "hover", "scroll", "dblclick")) {
            TestStepEntity s = step(1L, action);
            assertThat(service.isInputStep(s)).as("action=" + action).isFalse();
        }
    }

    @Test
    void nullStepOrNullActionType_isNotInputStep() {
        assertThat(service.isInputStep(null)).isFalse();
        assertThat(service.isInputStep(new TestStepEntity())).isFalse();
    }

    // ── resolveMapping priority chain ──────────────────────────────────────

    @Test
    void resolveMapping_matchesByExactLabelText_firstPriority() {
        TestStepEntity s = step(1L, "input");
        s.setLabelText("Email Address");
        s.setName("emailField"); // would also match Priority 2 if label didn't win

        FieldMappingService.MappingResult result = service.resolveMapping(
                List.of(s), List.of("Email Address", "emailField"), null);

        assertThat(result.isValid()).isTrue();
        assertThat(result.getResolved()).hasSize(1);
        assertThat(result.getResolved().get(0).getDatasetColumn()).isEqualTo("Email Address");
    }

    @Test
    void resolveMapping_fallsBackToNormalisedMatch_whenNoExactMatch() {
        TestStepEntity s = step(1L, "input");
        s.setLabelText("Phone Number");

        FieldMappingService.MappingResult result = service.resolveMapping(
                List.of(s), List.of("Phone-Number "), null);

        assertThat(result.isValid()).isTrue();
        assertThat(result.getResolved().get(0).getDatasetColumn()).isEqualTo("Phone-Number ");
    }

    @Test
    void resolveMapping_manualOverride_takesPriorityOverAutoMatch() {
        TestStepEntity s = step(42L, "input");
        s.setLabelText("Email Address");

        FieldMappingService.MappingResult result = service.resolveMapping(
                List.of(s), List.of("Email Address", "Contact Email"), Map.of("42", "Contact Email"));

        assertThat(result.isValid()).isTrue();
        assertThat(result.getResolved().get(0).getDatasetColumn()).isEqualTo("Contact Email");
    }

    @Test
    void resolveMapping_unresolvedField_whenNoColumnMatches() {
        TestStepEntity s = step(1L, "input");
        s.setLabelText("Social Security Number");

        FieldMappingService.MappingResult result = service.resolveMapping(
                List.of(s), List.of("Name", "Email"), null);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getResolved()).isEmpty();
        assertThat(result.getUnresolvedFields()).hasSize(1);
    }

    @Test
    void getColumnForStep_returnsMappedColumn_orNullWhenUnmapped() {
        FieldMappingService.FieldMapping mapping =
                new FieldMappingService.FieldMapping(7L, 1, "Name", "Full Name");

        assertThat(service.getColumnForStep(7L, List.of(mapping))).isEqualTo("Full Name");
        assertThat(service.getColumnForStep(99L, List.of(mapping))).isNull();
    }

    // ── Preceding-step context (real CGT bug: "Select Cancellation Reason"
    // vanished from the mapping screen) ─────────────────────────────────────
    //
    // A click-based dropdown OPTION step (the actual per-row selection) never
    // has a labelText/name/elementId/placeholder of its own — the recorder
    // only ever captures the SAMPLE value that happened to be clicked (e.g.
    // "Borrower Deathh"). Confirmed against a real recording: step 18 opens
    // the dropdown ("Select Cancellation Reason", not itself mappable), step
    // 19 is the actual option (role="option", but blank on every direct
    // field). Without this context, step 19 fell back to a raw CSS selector
    // as its label and could never auto-match, which looked to the user like
    // the field had disappeared even though it was correctly classified as
    // mappable.

    private TestStepEntity dropdownTrigger(long id, int order, String text) {
        TestStepEntity s = new TestStepEntity();
        s.setId(id);
        s.setStepOrder(order);
        s.setActionType("click");
        s.setPrimarySelector("span:has-text(\"" + text + "\")");
        s.setText(text);
        return s;
    }

    private TestStepEntity dropdownOption(long id, int order, String sampleText) {
        TestStepEntity s = new TestStepEntity();
        s.setId(id);
        s.setStepOrder(order);
        s.setActionType("click");
        s.setPrimarySelector("li[aria-label=\"" + sampleText + "\"]");
        s.setRole("option");
        s.setText(sampleText);
        return s;
    }

    @Test
    void resolveMapping_dropdownOptionStep_matchesViaPrecedingTriggerText_whenOwnMetadataBlank() {
        TestStepEntity trigger = dropdownTrigger(18L, 18, "Select Cancellation Reason");
        TestStepEntity option  = dropdownOption(19L, 19, "Borrower Deathh");

        FieldMappingService.MappingResult result = service.resolveMapping(
                List.of(option), List.of(trigger, option),
                List.of("CGPAN", "Select Cancellation Reason"), null);

        assertThat(result.isValid()).isTrue();
        assertThat(result.getResolved()).hasSize(1);
        assertThat(result.getResolved().get(0).getDatasetColumn()).isEqualTo("Select Cancellation Reason");
    }

    @Test
    void resolveMapping_dropdownOptionStep_labelUsesContextAndSample_whenUnresolved() {
        TestStepEntity trigger = dropdownTrigger(18L, 18, "Select Cancellation Reason");
        TestStepEntity option  = dropdownOption(19L, 19, "Borrower Deathh");

        // Dataset column name doesn't match anything — forces "unresolved",
        // so we can inspect exactly what label the user would see instead of
        // a raw CSS selector.
        FieldMappingService.MappingResult result = service.resolveMapping(
                List.of(option), List.of(trigger, option),
                List.of("Some Other Column"), null);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getUnresolvedFields()).hasSize(1);
        assertThat(result.getUnresolvedFields().get(0))
                .contains("Select Cancellation Reason (e.g. Borrower Deathh)");
    }

    @Test
    void resolveMapping_dropdownOptionStep_stillResolvesByItsOwnRole_whenNoPrecedingContext() {
        // No preceding step at all in range — findPrecedingDescriptiveText
        // has nothing to work with, so this must fall back gracefully
        // (still correctly classified as mappable via role="option", just
        // unresolved without a matching column) rather than throwing.
        TestStepEntity option = dropdownOption(19L, 19, "Borrower Deathh");

        FieldMappingService.MappingResult result = service.resolveMapping(
                List.of(option), List.of(option),
                List.of("Unrelated Column"), null);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getUnresolvedFields()).hasSize(1);
        assertThat(result.getUnresolvedFields().get(0)).contains("Borrower Deathh");
    }

    @Test
    void resolveMapping_threeArgOverload_stillWorks_backwardCompatible() {
        // The pre-existing 3-arg signature (no allStepsInRange) must keep
        // working exactly as before — callers that haven't been updated to
        // pass the full range still get correct, if less context-aware,
        // behaviour rather than a compile break or NPE.
        TestStepEntity s = step(1L, "input");
        s.setLabelText("Email Address");

        FieldMappingService.MappingResult result = service.resolveMapping(
                List.of(s), List.of("Email Address"), null);

        assertThat(result.isValid()).isTrue();
        assertThat(result.getResolved().get(0).getDatasetColumn()).isEqualTo("Email Address");
    }

    // ── Column-name-driven candidate inclusion (real CGT bug, second round:
    // "Select Cancellation Reason" STILL missing) ───────────────────────────
    //
    // A second real recording of the exact same logical flow captured the
    // "Borrower Deathh" option as a bare <span> with role="span" — NOT
    // role="option" — because the click landed on a different descendant
    // element than the earlier recording. isInputStep()'s role/selector-shape
    // checks have no way to distinguish that from a genuinely fixed click
    // (e.g. "Cancellation / Surrender", ALSO recorded as a plain li/span).
    // The fix anchors to the dataset's own column names instead: if a click
    // step's nearest preceding step's text exactly matches a column name
    // ("Select Cancellation Reason"), the step immediately following it is
    // almost certainly that column's per-row value.

    private TestStepEntity plainSpanClick(long id, int order, String text, String role) {
        TestStepEntity s = new TestStepEntity();
        s.setId(id);
        s.setStepOrder(order);
        s.setActionType("click");
        s.setRole(role);
        s.setPrimarySelector(role + ":has-text(\"" + text + "\")");
        s.setText(text);
        return s;
    }

    @Test
    void resolveMapping_dropdownOptionRecordedAsPlainSpan_isIncludedViaColumnNameMatch() {
        // Mirrors the real second recording exactly: menu item (fixed),
        // dropdown trigger (fixed), then the actual option — all recorded
        // as plain <li>/<span> with no role="option" anywhere.
        TestStepEntity menuItem = plainSpanClick(15L, 15, "Cancellation / Surrender", "li");
        TestStepEntity trigger  = plainSpanClick(16L, 16, "Select Cancellation Reason", "span");
        TestStepEntity option   = plainSpanClick(17L, 17, "Borrower Deathh", "span");
        List<TestStepEntity> allSteps = List.of(menuItem, trigger, option);

        // isInputStep() alone would find NOTHING mappable in this range
        // besides a separate CGPAN text field (not included here) — confirm
        // that in isolation first.
        assertThat(service.isInputStep(option)).isFalse();

        FieldMappingService.MappingResult result = service.resolveMapping(
                List.of(), allSteps,
                List.of("CGPAN", "Select Cancellation Reason"), null);

        assertThat(result.isValid()).isTrue();
        assertThat(result.getResolved()).hasSize(1);
        assertThat(result.getResolved().get(0).getStepId()).isEqualTo(17L);
        assertThat(result.getResolved().get(0).getDatasetColumn()).isEqualTo("Select Cancellation Reason");
    }

    @Test
    void resolveMapping_fixedMenuItemRecordedAsPlainSpanOrLi_isNeverIncluded_noFalsePositive() {
        // Same shapes as the option above, but this one's own preceding
        // context ("Search", or nothing) never matches a column name — must
        // NOT be swept in just because it structurally resembles the real
        // option (both are li:has-text(...)/span:has-text(...) clicks).
        TestStepEntity search   = plainSpanClick(13L, 13, "Search", "span");
        TestStepEntity menuBtn  = plainSpanClick(14L, 14, "", "span"); // blank text, e.g. an icon-only "..." button
        TestStepEntity menuItem = plainSpanClick(15L, 15, "Cancellation / Surrender", "li");
        List<TestStepEntity> allSteps = List.of(search, menuBtn, menuItem);

        FieldMappingService.MappingResult result = service.resolveMapping(
                List.of(), allSteps,
                List.of("CGPAN", "Select Cancellation Reason"), null);

        assertThat(result.isValid()).isTrue(); // no unresolved fields — because nothing was added as a candidate
        assertThat(result.getResolved()).isEmpty();
    }

    @Test
    void isColumnDrivenCandidate_trueOnlyWhenPrecedingTextMatchesAColumnName() {
        TestStepEntity trigger = plainSpanClick(16L, 16, "Select Cancellation Reason", "span");
        TestStepEntity option  = plainSpanClick(17L, 17, "Borrower Deathh", "span");
        List<TestStepEntity> allSteps = List.of(trigger, option);
        List<String> headers = List.of("CGPAN", "Select Cancellation Reason");

        assertThat(service.isColumnDrivenCandidate(option, allSteps, headers)).isTrue();
        // The trigger itself has no PRECEDING match, so it must stay excluded.
        assertThat(service.isColumnDrivenCandidate(trigger, allSteps, headers)).isFalse();
    }

    @Test
    void isColumnDrivenCandidate_false_whenStepIsAlreadyAnInputStep() {
        // Already covered by the normal isInputStep() path — must not be
        // double-processed via this secondary mechanism too.
        TestStepEntity trigger = plainSpanClick(16L, 16, "Select Cancellation Reason", "span");
        TestStepEntity option  = dropdownOption(17L, 17, "Borrower Deathh"); // role="option" already
        List<TestStepEntity> allSteps = List.of(trigger, option);

        assertThat(service.isColumnDrivenCandidate(option, allSteps, List.of("Select Cancellation Reason")))
                .isFalse();
    }

    @Test
    void isColumnDrivenCandidate_false_whenActionTypeIsNotClick() {
        TestStepEntity trigger = plainSpanClick(16L, 16, "Select Cancellation Reason", "span");
        TestStepEntity typeStep = step(17L, "type"); // not a click — column-name context doesn't apply
        typeStep.setStepOrder(17);
        List<TestStepEntity> allSteps = List.of(trigger, typeStep);

        assertThat(service.isColumnDrivenCandidate(typeStep, allSteps, List.of("Select Cancellation Reason")))
                .isFalse();
    }

    @Test
    void isColumnDrivenCandidate_matchesViaNormalisation_notJustExactText() {
        // Column header has different casing/spacing than the recorded
        // trigger text — Priority 5-style normalised comparison must still
        // catch it, not just an exact string match.
        TestStepEntity trigger = plainSpanClick(16L, 16, "select cancellation reason", "span");
        TestStepEntity option  = plainSpanClick(17L, 17, "Borrower Deathh", "span");
        List<TestStepEntity> allSteps = List.of(trigger, option);

        assertThat(service.isColumnDrivenCandidate(option, allSteps, List.of("Select Cancellation Reason")))
                .isTrue();
    }

    // ── Value-driven candidate inclusion (real CGT bug, THIRD round: a fresh
    // recording where even the dropdown trigger's text came back blank) ────
    //
    // A third real recording of the exact same flow captured the p-dropdown
    // trigger click with NO text at all (likely the click landed on an
    // icon/arrow element inside the trigger whose own innerText is empty) —
    // isColumnDrivenCandidate has nothing to work with in that case, since
    // findPrecedingDescriptiveText finds no usable text anywhere nearby
    // (the step 2 positions back, "Cancellation / Surrender", exists but its
    // text doesn't match either column). Only the option's OWN recorded
    // sample text ("Borrower Deathh") — matched against the ACTUAL dataset
    // row values, not headers — can catch this.

    private List<Map<String, String>> cgtSampleRows() {
        return List.of(
                Map.of("CGPAN", "CG20256357590TL", "Select Cancellation Reason", "Borrower Deathh"),
                Map.of("CGPAN", "CG20256357591TL", "Select Cancellation Reason", "Borrower Disability"));
    }

    @Test
    void resolveMapping_dropdownOptionStep_matchesViaActualCellValue_whenTriggerTextIsBlank() {
        TestStepEntity menuItem = plainSpanClick(15L, 15, "Cancellation / Surrender", "li");
        TestStepEntity trigger  = plainSpanClick(16L, 16, "", "span"); // blank — the real bug
        TestStepEntity option   = plainSpanClick(17L, 17, "Borrower Deathh", "span");
        List<TestStepEntity> allSteps = List.of(menuItem, trigger, option);

        // Confirm neither of the earlier signals fires — this step is
        // genuinely invisible to everything except the value match.
        assertThat(service.isInputStep(option)).isFalse();
        assertThat(service.isColumnDrivenCandidate(option, allSteps, List.of("CGPAN", "Select Cancellation Reason")))
                .isFalse();

        FieldMappingService.MappingResult result = service.resolveMapping(
                List.of(), allSteps, cgtSampleRows(),
                List.of("CGPAN", "Select Cancellation Reason"), null);

        assertThat(result.isValid()).isTrue();
        assertThat(result.getResolved()).hasSize(1);
        assertThat(result.getResolved().get(0).getStepId()).isEqualTo(17L);
        assertThat(result.getResolved().get(0).getDatasetColumn()).isEqualTo("Select Cancellation Reason");
    }

    @Test
    void resolveMapping_fixedMenuItem_neverMatchesByValue_evenThoughBothAreClicksWithText() {
        // "Cancellation / Surrender" is not a value in ANY column of this
        // dataset — must never be swept in just because it's a click step
        // with recorded text, same shape as the real option.
        TestStepEntity menuItem = plainSpanClick(15L, 15, "Cancellation / Surrender", "li");
        List<TestStepEntity> allSteps = List.of(menuItem);

        FieldMappingService.MappingResult result = service.resolveMapping(
                List.of(), allSteps, cgtSampleRows(),
                List.of("CGPAN", "Select Cancellation Reason"), null);

        assertThat(result.getResolved()).isEmpty();
    }

    @Test
    void resolveMapping_manualMappingOnValueDrivenStep_isAcceptedByBuildCandidateSteps() {
        // Reproduces the second real gap found alongside this one: a step
        // only reachable via the new candidate signals must still be a
        // legal target for a MANUAL mapping at "Start Run" time, not just
        // on the mapping screen — buildCandidateSteps is what
        // DataDrivenService.validateClientMappings must use instead of the
        // raw isInputStep()-only list, or a step the mapping screen
        // correctly offered gets rejected as "not an input step" the moment
        // the user tries to actually run it.
        TestStepEntity trigger = plainSpanClick(16L, 16, "", "span");
        TestStepEntity option  = plainSpanClick(17L, 17, "Borrower Deathh", "span");
        List<TestStepEntity> allSteps = List.of(trigger, option);

        List<TestStepEntity> candidates = service.buildCandidateSteps(
                List.of(), allSteps, cgtSampleRows(), List.of("CGPAN", "Select Cancellation Reason"));

        assertThat(candidates).extracting(TestStepEntity::getId).containsExactly(17L);
    }

    @Test
    void valueMatchedColumn_returnsNull_whenStepTextIsBlank() {
        TestStepEntity blank = plainSpanClick(17L, 17, "", "span");
        List<TestStepEntity> allSteps = List.of(blank);

        FieldMappingService.MappingResult result = service.resolveMapping(
                List.of(), allSteps, cgtSampleRows(),
                List.of("CGPAN", "Select Cancellation Reason"), null);

        assertThat(result.getResolved()).isEmpty();
    }

    // ── Fourth real-world round ("CGT RE" fresh recording): PrimeNG renders
    // its dropdown OPTION as a <p-dropdownitem> custom element (no hyphen —
    // it's a component tag name, not a CSS class) AND its dropdown TRIGGER
    // with completely blank text/labelText/ariaLabel/placeholder until a
    // value is chosen. Neither gap was covered by the first three rounds
    // above: isInputStep's structural check didn't recognise the unhyphenated
    // PrimeNG tag, and findPrecedingDescriptiveText used to skip straight
    // past a blank-text immediate predecessor to whatever earlier step
    // happened to have non-blank text — which is a DIFFERENT, unrelated
    // workflow step in a real recording, not a stand-in for the missing
    // trigger label.

    private TestStepEntity primeNgDropdownItemClick(long id, int order, String optionText) {
        TestStepEntity s = new TestStepEntity();
        s.setId(id);
        s.setStepOrder(order);
        s.setActionType("click");
        s.setRole("span");
        s.setPrimarySelector("#pr_id_16_list > p-dropdownitem:nth-of-type(5) > li:nth-of-type(1) > span:nth-of-type(1)");
        s.setText(optionText);
        return s;
    }

    private TestStepEntity blankPrimeNgTrigger(long id, int order) {
        TestStepEntity s = new TestStepEntity();
        s.setId(id);
        s.setStepOrder(order);
        s.setActionType("click");
        s.setRole("span");
        s.setPrimarySelector("p-dropdown:nth-of-type(1) > div:nth-of-type(1) > div:nth-of-type(2) > span:nth-of-type(1)");
        s.setText("");
        s.setLabelText("");
        s.setAriaLabel("");
        s.setPlaceholder("");
        return s;
    }

    @Test
    void isInputStep_recognizesPrimeNgDropdownItemCustomTag_evenWithoutAriaOptionRole() {
        TestStepEntity option = primeNgDropdownItemClick(18L, 18,
                "Guarantee has reached its Validity end date and is marked as closed");

        assertThat(service.isInputStep(option)).isTrue();
    }

    @Test
    void buildCandidateSteps_includesPrimeNgDropdownOption_reproducingCgtReBugReport() {
        // Exact shape of the real "CGT RE" recording: a menu click, then a
        // PrimeNG dropdown trigger with blank text, then the option itself.
        TestStepEntity menuItem = plainSpanClick(16L, 16, "Cancellation / Surrender", "li");
        TestStepEntity trigger  = blankPrimeNgTrigger(17L, 17);
        TestStepEntity option   = primeNgDropdownItemClick(18L, 18,
                "Guarantee has reached its Validity end date and is marked as closed");
        List<TestStepEntity> allSteps = List.of(menuItem, trigger, option);

        // Mirrors DataDrivenService.getInputStepsInRange's own
        // isInputStep()-filter. Before the fix this filter produced NOTHING
        // for the option (isInputStep didn't recognise PrimeNG's unhyphenated
        // <p-dropdownitem> tag), so it never reached the mapping screen at
        // all — exactly what was reported.
        List<TestStepEntity> inputSteps = allSteps.stream()
                .filter(service::isInputStep)
                .collect(java.util.stream.Collectors.toList());
        assertThat(inputSteps).extracting(TestStepEntity::getId).containsExactly(18L);

        List<TestStepEntity> candidates = service.buildCandidateSteps(
                inputSteps, allSteps, cgtSampleRows(), List.of("CGPAN", "Select Cancellation Reason"));

        assertThat(candidates).extracting(TestStepEntity::getId).containsExactly(18L);
    }

    @Test
    void isColumnDrivenCandidate_doesNotReachPastBlankImmediatePredecessor_toADistantUnrelatedMatch() {
        // Adversarial case proving WHY reaching back past a blank-text
        // immediate predecessor was actively unsafe, not just cosmetically
        // incomplete: the more distant step's text ("CGPAN") coincidentally
        // equals a REAL column header that has nothing to do with this
        // option. The old findPrecedingDescriptiveText would have walked
        // straight past the blank trigger and used "CGPAN" as context,
        // silently auto-mapping the cancellation-reason option to the wrong
        // column. Uses a plain (non-PrimeNG-tagged) selector shape so this
        // is isolated to the context-lookup fix alone, independent of the
        // isInputStep structural-recognition fix covered above.
        TestStepEntity distantUnrelated = plainSpanClick(15L, 15, "CGPAN", "li");
        TestStepEntity trigger = blankPrimeNgTrigger(16L, 16);
        TestStepEntity option = plainSpanClick(17L, 17,
                "Guarantee has reached its Validity end date and is marked as closed", "span");
        List<TestStepEntity> allSteps = List.of(distantUnrelated, trigger, option);

        assertThat(service.isColumnDrivenCandidate(option, allSteps, List.of("CGPAN", "Select Cancellation Reason")))
                .isFalse();
    }

    @Test
    void resolveMapping_primeNgDropdownOption_surfacesAsUnresolved_availableForManualMapping() {
        // End-to-end: with both fixes in place, the option is included as a
        // candidate (via the isInputStep structural fix) but correctly left
        // UNRESOLVED (via the context-lookup fix, since neither the blank
        // trigger nor the recorded option text matches this dataset) rather
        // than vanishing or being silently mismatched — the user maps it by
        // hand once, exactly like any other genuinely ambiguous field.
        TestStepEntity menuItem = plainSpanClick(16L, 16, "Cancellation / Surrender", "li");
        TestStepEntity trigger  = blankPrimeNgTrigger(17L, 17);
        TestStepEntity option   = primeNgDropdownItemClick(18L, 18,
                "Guarantee has reached its Validity end date and is marked as closed");
        List<TestStepEntity> allSteps = List.of(menuItem, trigger, option);
        List<TestStepEntity> inputSteps = List.of(option);

        FieldMappingService.MappingResult result = service.resolveMapping(
                inputSteps, allSteps, cgtSampleRows(),
                List.of("CGPAN", "Select Cancellation Reason"), null);

        assertThat(result.isValid()).isFalse();
        assertThat(result.getResolved()).isEmpty();
        assertThat(result.getUnresolvedFields()).hasSize(1);
        assertThat(result.getUnresolvedFields().get(0)).contains("Guarantee has reached");
    }

    @Test
    void findPrecedingDescriptiveText_fallsBackToImmediatePredecessorsOtherAttributes_whenItsTextIsBlank() {
        // The immediate predecessor's OWN text is blank, but it does carry a
        // usable ariaLabel — that must still be used in preference to
        // reaching back to a different, more distant step. Plain selector
        // shape again, to isolate this from the isInputStep fix.
        TestStepEntity trigger = blankPrimeNgTrigger(16L, 16);
        trigger.setAriaLabel("Select Cancellation Reason");
        TestStepEntity option = plainSpanClick(17L, 17, "Borrower Deathh", "span");
        List<TestStepEntity> allSteps = List.of(trigger, option);

        assertThat(service.isColumnDrivenCandidate(option, allSteps, List.of("CGPAN", "Select Cancellation Reason")))
                .isTrue();
    }

    @Test
    void resolveMapping_manualOverride_stillWorksForPrimeNgDropdownOption() {
        // Even when auto-detection can't resolve it, the manual-mapping
        // escape hatch (already proven for the earlier three rounds) must
        // keep working for this fourth shape too.
        TestStepEntity trigger = blankPrimeNgTrigger(17L, 17);
        TestStepEntity option = primeNgDropdownItemClick(18L, 18,
                "Guarantee has reached its Validity end date and is marked as closed");
        List<TestStepEntity> allSteps = List.of(trigger, option);

        FieldMappingService.MappingResult result = service.resolveMapping(
                List.of(option), allSteps, cgtSampleRows(),
                List.of("CGPAN", "Select Cancellation Reason"),
                Map.of("18", "Select Cancellation Reason"));

        assertThat(result.isValid()).isTrue();
        assertThat(result.getResolved()).hasSize(1);
        assertThat(result.getResolved().get(0).getDatasetColumn()).isEqualTo("Select Cancellation Reason");
    }
}
