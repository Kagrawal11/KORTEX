package com.miniautomation.backend.recording;

import java.util.HashMap;
import java.util.Map;

public class CapturedEvent {

    private String eventType;
    private String tag;
    private String elementId;
    private String name;
    private String type;
    private String role;
    private String labelText;
    private String selector;
    private String value;
    private String text;
    private String placeholder;
    private String surroundingContext;
    private Map<String, String> attributes = new HashMap<>();

    /**
     * The keyboard key that triggered this event (e.g. "Enter"). Only set for
     * eventType "keydown" — every other event type leaves this null.
     */
    private String key;

    /**
     * Value of a data-testid/data-test/data-cy/data-qa attribute on the
     * element itself (ancestors are deliberately not checked — a testid on a
     * wrapping container does not identify the child the user actually
     * interacted with). This is the single most reliable signal available —
     * authored specifically to identify an element for tests, so it survives
     * id/class/markup churn that would break every other selector. Empty
     * string (not null, matching every other optional field on this class)
     * when no such attribute exists.
     */
    private String testId;

    /**
     * The element's aria-label, captured as its own field (previously only
     * used transiently to help build {@link #selector}, never stored) so
     * playback can use it as an accessible-name input to Playwright's
     * getByRole() — a real semantic locator, not a CSS/text guess. Empty
     * string (matching every other optional field on this class) when the
     * element has no aria-label.
     */
    private String ariaLabel;

    /**
     * CSS selector identifying the same-origin iframe this element lives
     * inside (e.g. "#payment-frame", "iframe[name='widget']"), or null when
     * the element is in the page's main document. Only same-origin iframes
     * can be detected this way — cross-origin iframes block the frameElement
     * access needed to build this selector, so this stays null for them
     * (playback simply falls back to searching the main frame, exactly as
     * before this field existed).
     */
    private String frameSelector;

    public CapturedEvent() {
    }

    public String getEventType() {
        return eventType;
    }

    public void setEventType(String eventType) {
        this.eventType = eventType;
    }

    public String getTag() {
        return tag;
    }

    public void setTag(String tag) {
        this.tag = tag;
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

    public String getSelector() {
        return selector;
    }

    public void setSelector(String selector) {
        this.selector = selector;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
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

    public String getSurroundingContext() {
        return surroundingContext;
    }

    public void setSurroundingContext(String surroundingContext) {
        this.surroundingContext = surroundingContext;
    }

    public Map<String, String> getAttributes() {
        return attributes;
    }

    public void setAttributes(Map<String, String> attributes) {
        this.attributes = attributes;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
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
}
