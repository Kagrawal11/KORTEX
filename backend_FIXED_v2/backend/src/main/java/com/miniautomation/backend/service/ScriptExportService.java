package com.miniautomation.backend.service;

import com.miniautomation.backend.entity.TestScenarioEntity;
import com.miniautomation.backend.entity.TestStepEntity;
import org.springframework.stereotype.Service;

/**
 * ScriptExportService — Converts a recorded TestScenarioEntity into
 * a runnable Playwright Java test script.
 */
@Service
public class ScriptExportService {

    /**
     * Generate a Playwright Java test script for a given scenario.
     */
    public String generatePlaywrightScript(TestScenarioEntity scenario) {
        String className = toClassName(scenario.getName());
        StringBuilder sb = new StringBuilder();

        sb.append("import com.microsoft.playwright.*;\n");
        sb.append("import com.microsoft.playwright.options.*;\n");
        sb.append("import org.junit.jupiter.api.*;\n\n");

        sb.append("/**\n");
        sb.append(" * Auto-generated Playwright test script\n");
        sb.append(" * Scenario : ").append(scenario.getName()).append("\n");
        sb.append(" * Target   : ").append(scenario.getTargetUrl()).append("\n");
        sb.append(" * Steps    : ").append(scenario.getSteps().size()).append("\n");
        sb.append(" * Generated: ").append(java.time.LocalDateTime.now()).append("\n");
        sb.append(" */\n");
        sb.append("public class ").append(className).append("Test {\n\n");

        sb.append("    static Playwright playwright;\n");
        sb.append("    static Browser browser;\n");
        sb.append("    BrowserContext context;\n");
        sb.append("    Page page;\n\n");

        sb.append("    @BeforeAll\n");
        sb.append("    static void launchBrowser() {\n");
        sb.append("        playwright = Playwright.create();\n");
        sb.append("        browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(false));\n");
        sb.append("    }\n\n");

        sb.append("    @AfterAll\n");
        sb.append("    static void closeBrowser() {\n");
        sb.append("        playwright.close();\n");
        sb.append("    }\n\n");

        sb.append("    @BeforeEach\n");
        sb.append("    void createContextAndPage() {\n");
        sb.append("        context = browser.newContext();\n");
        sb.append("        page = context.newPage();\n");
        sb.append("    }\n\n");

        sb.append("    @AfterEach\n");
        sb.append("    void closeContext() {\n");
        sb.append("        context.close();\n");
        sb.append("    }\n\n");

        sb.append("    @Test\n");
        sb.append("    void ").append(toMethodName(scenario.getName())).append("() {\n");

        // Navigate to the target URL
        sb.append("        // Step 0: Navigate to target URL\n");
        sb.append("        page.navigate(\"").append(escape(scenario.getTargetUrl())).append("\");\n\n");

        // Generate each step
        for (TestStepEntity step : scenario.getSteps()) {
            appendStep(sb, step);
        }

        sb.append("    }\n");
        sb.append("}\n");

        return sb.toString();
    }

    // ── Step code generation ──────────────────────────────────────────────────

    private void appendStep(StringBuilder sb, TestStepEntity step) {
        String selector  = escape(step.getPrimarySelector());
        String action    = step.getActionType() != null ? step.getActionType().toLowerCase() : "unknown";
        String value     = step.getInputValue() != null ? escape(step.getInputValue()) : "";
        String desc      = step.getAiDescription() != null ? step.getAiDescription() : action + " on " + step.getPrimarySelector();

        sb.append("        // Step ").append(step.getStepOrder()).append(": ").append(desc).append("\n");

        switch (action) {
            case "click":
                sb.append("        page.locator(\"").append(selector).append("\").click();\n");
                break;

            case "type":
            case "input":
                sb.append("        page.locator(\"").append(selector).append("\").fill(\"").append(value).append("\");\n");
                break;

            case "change":
                // Could be a select dropdown or text field
                if (value.isEmpty()) {
                    sb.append("        page.locator(\"").append(selector).append("\").click();\n");
                } else {
                    sb.append("        page.locator(\"").append(selector).append("\").fill(\"").append(value).append("\");\n");
                }
                break;

            case "select":
                sb.append("        page.locator(\"").append(selector).append("\").selectOption(\"").append(value).append("\");\n");
                break;

            case "navigate":
                sb.append("        page.navigate(\"").append(value.isEmpty() ? selector : value).append("\");\n");
                break;

            case "check":
                sb.append("        page.locator(\"").append(selector).append("\").check();\n");
                break;

            case "uncheck":
                sb.append("        page.locator(\"").append(selector).append("\").uncheck();\n");
                break;

            case "hover":
                sb.append("        page.locator(\"").append(selector).append("\").hover();\n");
                break;

            case "dblclick":
                sb.append("        page.locator(\"").append(selector).append("\").dblclick();\n");
                break;

            default:
                sb.append("        // [UNSUPPORTED ACTION: ").append(action).append("] selector: ").append(selector).append("\n");
                break;
        }

        sb.append("\n");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Convert "My Test Name" → "MyTestName" */
    private String toClassName(String name) {
        if (name == null || name.isBlank()) return "Generated";
        StringBuilder result = new StringBuilder();
        for (String word : name.trim().split("[\\s_\\-]+")) {
            if (!word.isEmpty()) {
                result.append(Character.toUpperCase(word.charAt(0)));
                result.append(word.substring(1).toLowerCase());
            }
        }
        return result.toString().replaceAll("[^A-Za-z0-9]", "");
    }

    /** Convert "My Test Name" → "myTestName" */
    private String toMethodName(String name) {
        String cls = toClassName(name);
        if (cls.isEmpty()) return "runTest";
        return Character.toLowerCase(cls.charAt(0)) + cls.substring(1);
    }

    /** Escape double-quotes and backslashes for Java string literals */
    private String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
