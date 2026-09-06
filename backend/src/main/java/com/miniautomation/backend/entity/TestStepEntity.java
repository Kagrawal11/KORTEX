package com.miniautomation.backend.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "test_steps")
public class TestStepEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private int stepOrder;
    private String actionType;
    private String primarySelector;
    private String elementId;
    private String name;
    private String type;

    /**
     * Recorded HTML tag name (lowercase, e.g. "select", "input", "a"). Captured
     * during recording ({@link com.miniautomation.backend.recording.CapturedEvent#getTag()})
     * but historically never persisted here — without it, playback had no way
     * to tell a native {@code <select>} apart from a text {@code <input>}, so a
     * dropdown selection recorded as an "input"/"change" event was replayed via
     * fill()/pressSequentially() (which Playwright does not support on
     * {@code <select>} elements) instead of selectOption().
     */
    private String tag;

    /**
     * Keyboard key this step presses (e.g. "Enter"), for actionType "keydown"
     * only. Captured because the recorder previously only listened for
     * click/change/input DOM events, so a keyboard-driven action (e.g.
     * pressing Enter in a search box to submit/navigate) was never recorded
     * at all, silently breaking any playback step that depended on the page
     * it navigates to.
     *
     * Mapped to column "step_key", NOT "key" — KEY is a reserved word in
     * MySQL (used in PRIMARY KEY / index definitions), so leaving the column
     * name as the bare Java field name broke every query against this
     * entity with "Unknown column 's1_0.key' in 'field list'", not just
     * steps that use this field.
     */
    @Column(name = "step_key")
    private String key;

    private String role;
    private String labelText;
    private String text;
    private String placeholder;

    @Column(length = 2000)
    private String inputValue;

    @Column(length = 1000)
    private String aiDescription;

    /**
     * Value of a data-testid/data-test/data-cy/data-qa attribute captured at
     * recording time. The single highest-priority signal for playback
     * resolution when present — authored specifically to identify an element
     * for tests, so it survives id/class/markup churn that breaks every
     * other selector. Null for steps recorded before this field existed, and
     * for elements that never had one; playback falls back to the existing
     * chain exactly as before in that case.
     */
    private String testId;

    /**
     * The element's aria-label, captured as its own field so playback can
     * use it as the accessible-name input to a real Playwright
     * getByRole(role, {name}) locator instead of only a CSS/text guess. Null
     * when the element has no aria-label or for steps recorded before this
     * field existed.
     */
    private String ariaLabel;

    /**
     * CSS selector identifying the same-origin iframe this element lives
     * inside (e.g. "#payment-frame"), or null when the element is in the
     * page's main document (the overwhelming majority of steps) or lives
     * inside a cross-origin iframe that recording could not introspect.
     */
    private String frameSelector;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "scenario_id")
    @JsonIgnore
    private TestScenarioEntity scenario;

    public TestStepEntity() {
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public int getStepOrder() {
        return stepOrder;
    }

    public void setStepOrder(int stepOrder) {
        this.stepOrder = stepOrder;
    }

    public String getActionType() {
        return actionType;
    }

    public void setActionType(String actionType) {
        this.actionType = actionType;
    }

    public String getPrimarySelector() {
        return primarySelector;
    }

    public void setPrimarySelector(String primarySelector) {
        this.primarySelector = primarySelector;
    }

    public String getElementId() {
        return elementId;
    }

    public void setElementId(String elementId) {
        this.elementId = elementId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getTag() {
        return tag;
    }

    public void setTag(String tag) {
        this.tag = tag;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getLabelText() {
        return labelText;
    }

    public void setLabelText(String labelText) {
        this.labelText = labelText;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getPlaceholder() {
        return placeholder;
    }

    public void setPlaceholder(String placeholder) {
        this.placeholder = placeholder;
    }

    public String getInputValue() {
        return inputValue;
    }

    public void setInputValue(String inputValue) {
        this.inputValue = inputValue;
    }

    public String getAiDescription() {
        return aiDescription;
    }

    public void setAiDescription(String aiDescription) {
        this.aiDescription = aiDescription;
    }

    public String getTestId() {
        return testId;
    }

    public void setTestId(String testId) {
        this.testId = testId;
    }

    public String getAriaLabel() {
        return ariaLabel;
    }

    public void setAriaLabel(String ariaLabel) {
        this.ariaLabel = ariaLabel;
    }

    public String getFrameSelector() {
        return frameSelector;
    }

    public void setFrameSelector(String frameSelector) {
        this.frameSelector = frameSelector;
    }

    public TestScenarioEntity getScenario() {
        return scenario;
    }

    public void setScenario(TestScenarioEntity scenario) {
        this.scenario = scenario;
    }
}
