package com.miniautomation.backend.recording;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Page;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/**
 * EventListenerInjector — Wires the JavaScript-to-Java recording bridge.
 *
 * Contract (must be called in this order):
 * 1. exposeFunction "__miniAutoOnEvent" on the blank page (via Playwright CDP)
 * 2. addInitScript so the DOM listeners re-attach on every navigation
 * 3. evaluate so listeners are active on the current blank page too
 * (the blank page won't trigger addInitScript because it's already loaded)
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
        // id → name → aria-label → title → placeholder+type → text (links/buttons) →
        // positional fallback
        //
        // The positional fallback ALWAYS qualifies the tag with the element's
        // type attribute (e.g. input[type=text]) so we never generate a bare
        // 'input:nth-of-type(1)' that would match hidden __VIEWSTATE fields.
        //
        // IMPORTANT: :nth-of-type in CSS only matches by TAG NAME, not attribute.
        // So 'input[type=text]:nth-of-type(2)' is valid CSS and means:
        // "the 2nd <input> among its siblings that also has type=text"
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

                // ── Fragile/generated id detector ────────────────────────────────
                // Shared by both the top-level selector branch below and the
                // ancestor-walk fallback, so both stay in sync automatically.
                // Deliberately conservative: a generic "ends in N digits" rule
                // was considered and rejected because it would false-positive
                // on plenty of legitimate hand-authored ids (e.g. "section2",
                // "step1"). Each pattern below instead targets a SPECIFIC,
                // recognizable shape that generated ids actually take:
                //  - jQuery UI widget ids ("ui-id-5")
                //  - React useId()/Radix-style ids ("«r3»", ":r0:")
                //  - a bare UUID (v4-shaped hex-with-dashes)
                //  - a PURELY numeric id ("42") — narrower than "ends in
                //    digits", since a hand-authored id is essentially never
                //    ALL digits with nothing else
                //  - known volatile prefixes from popular component libraries
                //    that mint one id per mount (MUI, Radix, Headless UI,
                //    Ember, react-select)
                "  function isGeneratedId(id) {" +
                "    if (!id) return false;" +
                "    if (/^ui-id-\\d+$/.test(id)) return true;" +
                "    if (/^«.*»$/.test(id)) return true;" +
                "    if (/^:.*:$/.test(id)) return true;" +
                "    if (/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(id)) return true;" +
                "    if (/^\\d+$/.test(id)) return true;" +
                "    if (/^(mui-|radix-|headlessui-|ember\\d|react-select-)/i.test(id)) return true;" +
                "    return false;" +
                "  }" +

                // ── Same-origin iframe detection (computed once per frame) ───────
                // window.frameElement returns null both when this frame IS the
                // top-level document and when it is a CROSS-origin iframe (per
                // spec, it does not throw for cross-origin — it just yields
                // null), so a single null-check safely covers both cases we
                // cannot or don't need to handle.
                "  function computeFrameSelector() {" +
                "    try {" +
                "      if (window.self === window.top) return null;" +
                "      var fe = window.frameElement;" +
                "      if (!fe || (fe.tagName !== 'IFRAME' && fe.tagName !== 'FRAME')) return null;" +
                "      if (fe.id && !isGeneratedId(fe.id)) return '#' + CSS.escape(fe.id);" +
                "      var feName = fe.getAttribute('name');" +
                "      if (feName) return 'iframe[name=\"' + feName.replace(/\"/g, '\\\\\"') + '\"]';" +
                "      var feParent = fe.parentElement;" +
                "      var feSiblings = feParent ? Array.from(feParent.querySelectorAll(':scope > iframe')) : [];" +
                "      var feIdx = feSiblings.indexOf(fe) + 1;" +
                "      return feIdx > 0 ? 'iframe:nth-of-type(' + feIdx + ')' : 'iframe';" +
                "    } catch (e) { return null; }" +
                "  }" +
                "  var frameSelectorCache = computeFrameSelector();" +

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
                // innerText inserts a newline between block-level children, and .trim()
                // only strips the ends. A retained CRLF lands raw inside :has-text("…"),
                // and a newline inside a CSS string literal produces a BADSTRING token —
                // the selector then fails to PARSE, so no healing strategy can recover it.
                // Collapse all internal whitespace to single spaces before use.
                "    var txt      = (el.innerText || el.textContent || '').replace(/\\s+/g, ' ').trim().substring(0, 120);"
                +
                "    var ariaLabel = el.getAttribute('aria-label') || '';" +
                "    var titleAttr = el.getAttribute('title') || '';" +
                // data-testid family — checked on the element itself only
                // (see CapturedEvent.testId javadoc for why ancestors are
                // deliberately not consulted).
                "    var testIdAttrName = '';" +
                "    var testIdAttr = el.getAttribute('data-testid');" +
                "    if (testIdAttr) { testIdAttrName = 'data-testid'; }" +
                "    else { testIdAttr = el.getAttribute('data-test'); if (testIdAttr) testIdAttrName = 'data-test'; }" +
                "    if (!testIdAttr) { testIdAttr = el.getAttribute('data-cy'); if (testIdAttr) testIdAttrName = 'data-cy'; }" +
                "    if (!testIdAttr) { testIdAttr = el.getAttribute('data-qa'); if (testIdAttr) testIdAttrName = 'data-qa'; }" +
                "    testIdAttr = testIdAttr || '';" +
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
                // jQuery UI (autocomplete, dialog, menu, etc.) assigns ids like
                // "ui-id-5" to widget-generated elements (e.g. each <li> suggestion
                // in a scrip-search dropdown) from a page-load-global counter. The
                // same element can get a different number on every page load, so
                // an "#ui-id-N" selector is NOT stable across recording vs playback
                // and across separate playback runs. Skip it and fall through to a
                // stable text/attribute-based selector below instead.
                //
                // React's useId() hook (and similar id-generating hooks in other
                // frameworks) produces the same kind of per-session/per-request
                // volatile id, just in a different shape: "«r3»"/"«Rsvvriejj...»"
                // (guillemet-wrapped, seen on a real LinkedIn recording) or
                // ":r0:" (colon-wrapped, React 18 client-only form). No real
                // hand-authored HTML id starts with « or : — treat both shapes as
                // volatile too, for the same reason as ui-id-N above.
                "    if (testIdAttr) {" +
                "      selector = tag + '[' + testIdAttrName + '=\"' + testIdAttr.replace(/\"/g, '\\\\\"') + '\"]';" +
                "    } else if (elId && !isGeneratedId(elId)) {" +
                "      selector = '#' + CSS.escape(elId);" +
                "    } else if (elName) {" +
                "      selector = tag + '[name=\"' + elName + '\"]';" +
                "    } else if (ariaLabel) {" +
                "      selector = tag + '[aria-label=\"' + ariaLabel.replace(/\"/g, '\\\\\"') + '\"]';" +
                "    } else if (titleAttr) {" +
                "      selector = tag + '[title=\"' + titleAttr.replace(/\"/g, '\\\\\"') + '\"]';" +
                // For input elements with a placeholder, use placeholder+type — specific and
                // readable
                "    } else if (tag === 'input' && ph) {" +
                "      var typeAttr = elType ? '[type=\"' + elType + '\"]' : '';" +
                "      selector = 'input' + typeAttr + '[placeholder=\"' + ph.replace(/\"/g, '\\\\\"') + '\"]';" +
                "    } else if (txt && txt.length > 0 && txt.length <= 50 && (tag === 'a' || tag === 'button' || tag === 'span' || tag === 'li')) {"
                +
                "      selector = tag + ':has-text(\"' + txt.replace(/\"/g, '\\\\\"') + '\")';" +
                // A div/li carrying role="button" also deserves a text selector, but a bare
                // tag + :has-text matches every ANCESTOR containing that text (including
                // body), and nth=0 then resolves to the outermost wrapper. Qualifying with
                // the role attribute narrows it to the control the user actually clicked.
                "    } else if (txt && txt.length > 0 && txt.length <= 50 && elRole === 'button') {" +
                "      selector = tag + '[role=\"button\"]:has-text(\"' + txt.replace(/\"/g, '\\\\\"') + '\")';" +
                // Button-like inputs (<input type="button|submit|reset">) frequently have
                // no id and no name — the ONLY thing identifying them is their value, i.e.
                // the visible label ("Yes", "Create GTT", "Confirm"). Falling through to the
                // positional branch below discards that and yields a bare
                // input:nth-of-type(1)[type="button"], which matches the first button-type
                // input in WHATEVER document is loaded. That is how playback clicked
                // btnOAOSiteLogin (value="Login") and navigated the whole session away.
                // Anchoring on value keeps the selector portable and, crucially, makes it
                // fail cleanly on the wrong page instead of clicking something destructive.
                "    } else if (tag === 'input' && (elType === 'button' || elType === 'submit' || elType === 'reset') && val) {"
                +
                "      selector = 'input[type=\"' + elType + '\"][value=\"' + val.replace(/\"/g, '\\\\\"') + '\"]';" +
                "    } else {" +
                // Positional fallback — SCOPED to a bounded ancestor chain.
                //
                // The old version emitted a bare 'tag:nth-of-type(idx)' with no
                // ancestor context at all. Playwright then matched ANY element
                // in the whole document satisfying "Nth tag-child of its own
                // parent" — on the ICICI GTT order form this once resolved to
                // an unrelated <div class="aspNetHidden"> instead of the actual
                // clicked element.
                //
                // Fix: walk up from the element (max 4 levels) building
                // 'tag:nth-of-type(idx)' at each level, same as before, but
                // joined with '>' so the chain is scoped to real parent-child
                // relationships. If a stable (non jQuery-UI-generated) id is
                // found on any ancestor within those 4 levels, the chain
                // anchors there and stops early — e.g.
                // '#orderPanel > div:nth-of-type(2) > span:nth-of-type(1)'.
                // If no id is found, the bounded 4-level chain is still used —
                // strictly more specific than the old single-segment selector,
                // never less.
                "      var typeAttrFilter = (tag === 'input' && elType) ? '[type=\"' + elType + '\"]' : '';" +
                "      var segments = [];" +
                "      var node = el;" +
                "      var isLeaf = true;" +
                "      var levels = 0;" +
                "      while (node && node.nodeType === 1 && levels < 4) {" +
                "        var nodeTag = (node.tagName || '').toLowerCase();" +
                "        var nodeId = node.id || '';" +
                "        if (nodeId && !isGeneratedId(nodeId)) {" +
                "          segments.unshift('#' + CSS.escape(nodeId));" +
                "          break;" +
                "        }" +
                "        var nodeParent = node.parentElement;" +
                "        var nodeSiblings = nodeParent ? Array.from(nodeParent.querySelectorAll(':scope > ' + nodeTag)) : [];"
                +
                "        var nodeIdx = nodeSiblings.indexOf(node) + 1;" +
                "        var seg = nodeTag + (nodeIdx > 0 ? ':nth-of-type(' + nodeIdx + ')' : '');" +
                "        if (isLeaf) {" +
                "          seg += typeAttrFilter;" +
                "          isLeaf = false;" +
                "        }" +
                "        segments.unshift(seg);" +
                "        node = nodeParent;" +
                "        levels++;" +
                "      }" +
                "      selector = segments.join(' > ');" +
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
                "      value:     val," +
                "      testId:    testIdAttr," +
                "      ariaLabel: ariaLabel," +
                "      frameSelector: frameSelectorCache" +
                "    };" +
                "  }" +

                // ── Event sender ─────────────────────────────────────────────────
                // keyName is only passed for keyboard-driven events (e.g. 'Enter')
                // and is omitted from the payload otherwise, same as every other
                // optional metadata field extractMeta produces.
                "  function sendEvent(eventType, el, keyName) {" +
                "    if (!el || el === document || el === window) return;" +
                "    var tag = (el.tagName || '').toLowerCase();" +
                "    if (tag === 'body' || tag === 'html' || tag === 'head') return;" +
                // Skip hidden inputs — never intentional user interactions
                "    if (tag === 'input' && el.getAttribute('type') === 'hidden') return;" +
                "    var meta = extractMeta(el);" +
                "    if (!meta) return;" +
                "    meta.eventType = eventType;" +
                "    if (keyName) meta.key = keyName;" +
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
                // The click/change/input listeners above cannot capture a keyboard-
                // only action like pressing Enter to submit a search box — there is
                // no click and, since Enter doesn't insert a character, no input
                // event either. Confirmed missing on a real recording: typing a
                // name then pressing Enter to reach LinkedIn's search results page
                // produced a "type" step but nothing for the Enter itself, so
                // playback never navigated and every step after it operated on the
                // wrong page. Scoped to Enter only (not every key) and to
                // text-entry elements only, to avoid capturing Enter presses that
                // are just re-triggering a button's own click (already recorded
                // separately) and to avoid flooding the event stream with every
                // keystroke — e.repeat is also excluded so holding Enter down
                // doesn't record a burst of duplicate steps.
                "  document.addEventListener('keydown', function(e) {" +
                "    if (e.key !== 'Enter' || e.repeat) return;" +
                "    var t = e.target;" +
                "    if (t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.isContentEditable)) {" +
                "      sendEvent('keydown', t, 'Enter');" +
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
