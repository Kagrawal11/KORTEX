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
        //
        // Selector priority (most-specific first):
        //   id → name → aria-label → title → placeholder+type → text (links/buttons) → positional fallback
        //
        // The positional fallback ALWAYS qualifies the tag with the element's
        // type attribute (e.g. input[type=text]) so we never generate a bare
        // 'input:nth-of-type(1)' that would match hidden __VIEWSTATE fields.
        //
        // IMPORTANT: :nth-of-type in CSS only matches by TAG NAME, not attribute.
        // So 'input[type=text]:nth-of-type(2)' is valid CSS and means:
        //   "the 2nd <input> among its siblings that also has type=text"
        // We count siblings using plain querySelectorAll(':scope > input') and
        // indexOf, then generate 'input:nth-of-type(idx)' which is valid.
        // The [type=text] qualifier is appended as an additional attribute filter,
        // NOT as part of the :nth-of-type expression, so CSS remains valid.
        //
        // Hidden inputs are skipped entirely in sendEvent() before we even
        // call extractMeta — they are never intentional user interactions.
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
            "    var ariaLabel = el.getAttribute('aria-label') || '';" +
            "    var titleAttr = el.getAttribute('title') || '';" +
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

            // ── Selector building ─────────────────────────────────────────────
            "    var selector;" +
            "    if (elId) {" +
            "      selector = '#' + CSS.escape(elId);" +
            "    } else if (elName) {" +
            "      selector = tag + '[name=\"' + elName + '\"]';" +
            "    } else if (ariaLabel) {" +
            "      selector = tag + '[aria-label=\"' + ariaLabel.replace(/\"/g, '\\\\\"') + '\"]';" +
            "    } else if (titleAttr) {" +
            "      selector = tag + '[title=\"' + titleAttr.replace(/\"/g, '\\\\\"') + '\"]';" +
            // For input elements with a placeholder, use placeholder+type — specific and readable
            "    } else if (tag === 'input' && ph) {" +
            "      var typeAttr = elType ? '[type=\"' + elType + '\"]' : '';" +
            "      selector = 'input' + typeAttr + '[placeholder=\"' + ph.replace(/\"/g, '\\\\\"') + '\"]';" +
            "    } else if (txt && txt.length > 0 && txt.length <= 50 && (tag === 'a' || tag === 'button' || tag === 'span' || elRole === 'button' || tag === 'li')) {" +
            "      selector = tag + ':has-text(\"' + txt.replace(/\"/g, '\\\\\"') + '\")';" +
            "    } else {" +
            // Positional fallback. We always use plain ':scope > TAG' to count siblings
            // (CSS nth-of-type only understands tag name, not attributes).
            // We then append the type attribute as a SEPARATE filter so the final
            // selector reads: input:nth-of-type(2)[type=\"text\"]  ← valid CSS.
            "      var parent = el.parentElement;" +
            "      var siblings = parent ? Array.from(parent.querySelectorAll(':scope > ' + tag)) : [];" +
            "      var idx = siblings.indexOf(el) + 1;" +
            "      var typeAttrFilter = (tag === 'input' && elType) ? '[type=\"' + elType + '\"]' : '';" +
            "      selector = tag + (idx > 0 ? ':nth-of-type(' + idx + ')' : '') + typeAttrFilter;" +
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
            "    var tag = (el.tagName || '').toLowerCase();" +
            "    if (tag === 'body' || tag === 'html' || tag === 'head') return;" +
            // Skip hidden inputs — never intentional user interactions
            "    if (tag === 'input' && el.getAttribute('type') === 'hidden') return;" +
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
            "  document.addEventListener('input',  function(e) {" +
            "    var t = e.target;" +
            "    if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.tagName === 'SELECT')) {" +
            "      if (t.getAttribute('type') === 'hidden') return;" +
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
