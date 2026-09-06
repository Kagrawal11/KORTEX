package com.miniautomation.backend.recording;

import com.miniautomation.backend.browser.BrowserManager;
import com.miniautomation.backend.repository.TestScenarioRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Coverage for RecordingSession.smartDeduplicate()'s handling of "keydown"
 * events — added because the recorder previously had no keyboard listener at
 * all, so pressing Enter to submit a search box (e.g. LinkedIn's search)
 * produced no step whatsoever, and playback silently diverged from the
 * recorded flow once it needed the page Enter navigates to.
 *
 * smartDeduplicate() is private and pure (a List in, a List out — no
 * Playwright/DB I/O), so reflection is used to test it directly, matching
 * the same pattern used for PlaybackEngine.cloneWithOverride().
 */
class RecordingSessionTest {

    private final RecordingSession session = new RecordingSession(
            mock(BrowserManager.class),
            mock(EventListenerInjector.class),
            mock(ElementMetadataExtractor.class),
            mock(TestScenarioRepository.class));

    @SuppressWarnings("unchecked")
    private List<CapturedEvent> smartDeduplicate(List<CapturedEvent> events) throws Exception {
        Method m = RecordingSession.class.getDeclaredMethod("smartDeduplicate", List.class);
        m.setAccessible(true);
        return (List<CapturedEvent>) m.invoke(session, events);
    }

    private CapturedEvent inputKeystroke(String selector, String value) {
        CapturedEvent evt = new CapturedEvent();
        evt.setEventType("input");
        evt.setSelector(selector);
        evt.setValue(value);
        return evt;
    }

    private CapturedEvent keydown(String selector, String key) {
        CapturedEvent evt = new CapturedEvent();
        evt.setEventType("keydown");
        evt.setSelector(selector);
        evt.setKey(key);
        return evt;
    }

    @Test
    void keydownEnterStep_isKeptAsItsOwnStep_afterTheTypedValue() throws Exception {
        List<CapturedEvent> result = smartDeduplicate(List.of(
                inputKeystroke("#search", "Sneha Iyer"),
                keydown("#search", "Enter")));

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getEventType()).isEqualTo("type"); // renamed by existing Rule 1
        assertThat(result.get(0).getValue()).isEqualTo("Sneha Iyer");
        assertThat(result.get(1).getEventType()).isEqualTo("keydown");
        assertThat(result.get(1).getKey()).isEqualTo("Enter");
    }

    @Test
    void consecutiveDuplicateEnterPresses_onSameSelector_areCollapsedToOne() throws Exception {
        List<CapturedEvent> result = smartDeduplicate(List.of(
                keydown("#search", "Enter"),
                keydown("#search", "Enter")));

        assertThat(result).hasSize(1);
    }

    @Test
    void enterPressesOnDifferentSelectors_areBothKept() throws Exception {
        List<CapturedEvent> result = smartDeduplicate(List.of(
                keydown("#search", "Enter"),
                keydown("#other-field", "Enter")));

        assertThat(result).hasSize(2);
    }
}
