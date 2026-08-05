package com.miniautomation.backend.recording;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Page;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/**
 * EventListenerInjector — Wires the JavaScript-to-Java recording bridge.
 *
 * Contract (must be called in this order):
 *   1. exposeFunction "__miniAutoOnEvent" on the blank page  (via Playwright CDP)
 *   2. addInitScript   so the DOM listeners re-attach on every navigation
 *   3. evaluate        so listeners are active on the current blank page too
 *      (the blank page won't trigger addInitScript because it's already loaded)
 *
 * NOTE: This component is stateless; it can be used per-session safely.
 */
@Component
public class EventListenerInjector {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public void injectListeners(Page page, Consumer<CapturedEvent> eventConsumer) {

        System.out.println("[EventListenerInjector] Registering Java bridge '__miniAutoOnEvent'...");

        // Step 1 — Expose the Java callback into the browser's window object.
        // This MUST happen before navigation so the function is available
        // the moment the page starts executing JavaScript.
        page.exposeFunction("__miniAutoOnEvent", (Object[] args) -> {
            if (args != null && args.length > 0 && args[0] != null) {
                try {
                    String json = args[0].toString();
                    System.out.println("[EventListenerInjector] RAW event received: " + json);
                    CapturedEvent event = MAPPER.readValue(json, CapturedEvent.class);
                    System.out.println("[EventListenerInjector] Parsed event: type=" + event.getEventType()
                            + " selector=" + event.getSelector());
                    eventConsumer.accept(event);
                } catch (Exception e) {
                    System.out.println("[EventListenerInjector WARNING] Failed to parse event JSON: " + e.getMessage());
                }
            }
            return null;
        });
        System.out.println("[EventListenerInjector] exposeFunction registered OK.");

        // Step 2 — Build the JS injection script.
        // This script:
        //   a) Guards against double-injection with window.__miniAutoInjected
        //   b) Extracts rich metadata from every interacted element
        //   c) Builds a robust, unique CSS selector (prefers id > name > nth-of-type)
        //   d) Calls window.__miniAutoOnEvent(json) to push events to Java
        String script = "(() => {" +
            "  if (window.__miniAutoInjected) {" +
            "    console.log('[MiniAuto] Already injected — skipping.');" +
            "    return;" +
            "  }" +
            "  window.__miniAutoInjected = true;" +
            "  console.log('[MiniAuto] Injecting listeners on: ' + location.href);" +

            // ── Metadata extractor ───────────────────────────────────────────
            "  function extractMeta(el) {" +
            "    if (!el || el.nodeType !== 1) return null;" +
            "    var tag      = (el.tagName || '').toLowerCase();" +
            "    var elId     = el.id || '';" +
            "    var elName   = el.getAttribute('name') || '';" +
            "    var elType   = el.getAttribute('type') || '';" +
            "    var elRole   = el.getAttribute('role') || tag;" +
            "    var ph       = el.getAttribute('placeholder') || '';" +
            "    var val      = el.value || '';" +
            "    var txt      = (el.innerText || el.textContent || '').trim().substring(0, 120);" +
            // label resolution
            "    var labelTxt = '';" +
            "    if (elId) {" +
            "      var lbl = document.querySelector('label[for=\"' + elId + '\"]');" +
            "      if (lbl) labelTxt = lbl.innerText.trim();" +
            "    }" +
            "    if (!labelTxt && el.closest) {" +
            "      var pLbl = el.closest('label');" +
            "      if (pLbl) labelTxt = pLbl.innerText.trim();" +
            "    }" +
            // selector building — prefer id, then name, then nth-of-type fallback
            "    var selector;" +
            "    if (elId) {" +
            "      selector = '#' + CSS.escape(elId);" +
            "    } else if (elName) {" +
            "      selector = tag + '[name=\"' + elName + '\"]';" +
            "    } else {" +
            "      var parent = el.parentElement;" +
            "      var siblings = parent ? Array.from(parent.querySelectorAll(':scope > ' + tag)) : [];" +
            "      var idx = siblings.indexOf(el) + 1;" +
            "      selector = tag + (idx > 0 ? ':nth-of-type(' + idx + ')' : '');" +
            "    }" +
            "    return {" +
            "      tag:       tag," +
            "      elementId: elId," +
            "      name:      elName," +
            "      type:      elType," +
            "      role:      elRole," +
            "      labelText: labelTxt," +
            "      selector:  selector," +
            "      placeholder: ph," +
            "      text:      txt," +
            "      value:     val" +
            "    };" +
            "  }" +

            // ── Event sender ─────────────────────────────────────────────────
            "  function sendEvent(eventType, el) {" +
            "    if (!el || el === document || el === window) return;" +
            // skip body/html/head — too broad
            "    var tag = (el.tagName || '').toLowerCase();" +
            "    if (tag === 'body' || tag === 'html' || tag === 'head') return;" +
            "    var meta = extractMeta(el);" +
            "    if (!meta) return;" +
            "    meta.eventType = eventType;" +
            "    if (typeof window.__miniAutoOnEvent !== 'function') {" +
            "      console.warn('[MiniAuto] Bridge not ready — event dropped:', eventType, el);" +
            "      return;" +
            "    }" +
            "    console.log('[MiniAuto] Sending event:', eventType, meta.selector);" +
            "    window.__miniAutoOnEvent(JSON.stringify(meta))" +
            "      .catch(function(err) { console.warn('[MiniAuto] Bridge error:', err); });" +
            "  }" +

            // ── Attach DOM listeners (capture phase = true for full coverage) ─
            "  document.addEventListener('click',  function(e) { sendEvent('click',  e.target); }, true);" +
            "  document.addEventListener('change', function(e) { sendEvent('change', e.target); }, true);" +
            // Only capture input events on actual <input> / <textarea>
            "  document.addEventListener('input',  function(e) {" +
            "    var t = e.target;" +
            "    if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.tagName === 'SELECT')) {" +
            "      sendEvent('input', t);" +
            "    }" +
            "  }, true);" +
            "  console.log('[MiniAuto] Listeners attached successfully.');" +
            "})();";

        // Step 3 — Register as an init script so it fires on every future navigation.
        page.addInitScript(script);
        System.out.println("[EventListenerInjector] addInitScript registered OK.");

        // Step 4 — Also evaluate immediately on the current (blank) page so the
        // bridge is live before the first navigation finishes.
        try {
            page.evaluate(script);
            System.out.println("[EventListenerInjector] Immediate evaluate() OK.");
        } catch (Exception e) {
            // Expected on about:blank in some Playwright versions — safe to ignore.
            System.out.println("[EventListenerInjector] Immediate evaluate() note: " + e.getMessage());
        }
    }
}
