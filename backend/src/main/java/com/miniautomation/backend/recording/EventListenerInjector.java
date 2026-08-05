package com.miniautomation.backend.recording;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Page;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

@Component
public class EventListenerInjector {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public void injectListeners(Page page, Consumer<CapturedEvent> eventConsumer) {
        System.out.println("[EventListenerInjector] Exposing Java callback '__miniAutoOnEvent' and injecting DOM event listeners...");

        page.exposeFunction("__miniAutoOnEvent", (Object[] args) -> {
            if (args != null && args.length > 0 && args[0] != null) {
                try {
                    String json = args[0].toString();
                    CapturedEvent event = objectMapper.readValue(json, CapturedEvent.class);
                    eventConsumer.accept(event);
                } catch (Exception e) {
                    System.out.println("[EventListenerInjector Warning] Failed to parse captured event JSON: " + e.getMessage());
                }
            }
            return null;
        });

        String script = "(() => {" +
                "  if (window.__miniAutoInjected) return;" +
                "  window.__miniAutoInjected = true;" +
                "  function extractMeta(el) {" +
                "    if (!el || el === document) return {};" +
                "    var id = el.id || '';" +
                "    var name = el.getAttribute('name') || '';" +
                "    var type = el.getAttribute('type') || '';" +
                "    var role = el.getAttribute('role') || (el.tagName ? el.tagName.toLowerCase() : '');" +
                "    var placeholder = el.getAttribute('placeholder') || '';" +
                "    var text = (el.innerText || el.textContent || el.value || '').trim().substring(0, 100);" +
                "    var labelText = '';" +
                "    if (id) { var lbl = document.querySelector(\"label[for='\" + id + \"']\"); if (lbl) labelText = lbl.innerText.trim(); }" +
                "    if (!labelText && el.closest) { var parentLbl = el.closest('label'); if (parentLbl) labelText = parentLbl.innerText.trim(); }" +
                "    var selector = id ? '[id=\"' + id + '\"]' : (name ? el.tagName.toLowerCase() + '[name=\"' + name + '\"]' : (el.tagName ? el.tagName.toLowerCase() : ''));" +
                "    return {" +
                "      tag: el.tagName ? el.tagName.toLowerCase() : ''," +
                "      elementId: id," +
                "      name: name," +
                "      type: type," +
                "      role: role," +
                "      labelText: labelText," +
                "      selector: selector," +
                "      placeholder: placeholder," +
                "      text: text," +
                "      value: el.value || ''" +
                "    };" +
                "  }" +
                "  function sendEvt(type, e, customTarget) {" +
                "    var targetEl = customTarget || e.target;" +
                "    if (!targetEl) return;" +
                "    var meta = extractMeta(targetEl);" +
                "    meta.eventType = type;" +
                "    if (window.__miniAutoOnEvent) window.__miniAutoOnEvent(JSON.stringify(meta));" +
                "  }" +
                "  document.addEventListener('click', function(e) { sendEvt('click', e); }, true);" +
                "  document.addEventListener('change', function(e) { sendEvt('change', e); }, true);" +
                "  document.addEventListener('input', function(e) { if (e.target && e.target.tagName && e.target.tagName.toLowerCase() === 'input') sendEvt('input', e); }, true);" +
                "  document.addEventListener('scroll', function(e) { sendEvt('scroll', e); }, true);" +
                "  document.addEventListener('dragstart', function(e) { sendEvt('dragstart', e); }, true);" +
                "  document.addEventListener('drop', function(e) { sendEvt('drop', e); }, true);" +
                "})();";

        page.addInitScript(script);
        try {
            page.evaluate(script);
        } catch (Exception ignored) {}
    }
}
