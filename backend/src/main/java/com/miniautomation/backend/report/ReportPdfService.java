package com.miniautomation.backend.report;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniautomation.backend.accessibility.PassSummaryDto;
import com.miniautomation.backend.accessibility.RuleFindingDto;
import com.miniautomation.backend.accessibility.RuleNodeDto;
import com.miniautomation.backend.apitesting.dto.AssertionResult;
import com.miniautomation.backend.entity.AccessibilityScanRunEntity;
import com.miniautomation.backend.entity.ApiRequestRunResultEntity;
import com.miniautomation.backend.entity.ApiRunEntity;
import com.miniautomation.backend.entity.DataDrivenRowResultEntity;
import com.miniautomation.backend.entity.DataDrivenRunEntity;
import com.miniautomation.backend.entity.TestRunEntity;
import com.miniautomation.backend.entity.TestRunStepEntity;
import com.miniautomation.backend.entity.TestScenarioEntity;
import com.miniautomation.backend.entity.TestStepEntity;
import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder.FontStyle;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Renders a real execution/scan result into a PDF, entirely server-side, from
 * the SAME entity data the report pages already display — no separate data
 * path, no fabricated content. Builds a small HTML string (escaping every
 * dynamic value — a violation's node.html is literal HTML captured from the
 * SCANNED page and must never be interpreted as markup here) and hands it to
 * openhtmltopdf for layout/pagination, rather than hand-laying-out text with
 * raw PDFBox — table/page-break handling is exactly the part not worth
 * re-implementing by hand.
 *
 * Layout: a full-bleed light header band (logo/wordmark/tagline left,
 * report-type title right, subtle diagonal accent), a test-info bar with a
 * prominent colored status chip, four-up summary cards, a step/row results
 * table, a two-panel "Test Information" / "Result" footer section, and a
 * full-bleed dark closing band — modelled on a supplied reference design.
 * Real per-page numbering ("Page X of Y") comes from a separate, always-
 * reliable {@code @page} running footer (CSS counters only work in page
 * margin boxes), rendered on every page regardless of how many pages the
 * report spans; the rich dark closing band is normal in-flow content and so
 * only appears once, at the end.
 *
 * Step names shown in the main "Step-by-Step Results" table are
 * human-readable ("Enter Username", "Click Sign In") rather than raw
 * selectors — built from the ORIGINAL recorded {@link TestStepEntity}
 * metadata (label/placeholder/accessible name/visible text), looked up by
 * stepOrder from the run's own scenario (see {@link #stepMetadataByOrder}).
 * Raw selector/value detail is never discarded — it moves to an optional
 * "Technical Reference" appendix at the end of the report for engineers who
 * need it.
 */
@Service
public class ReportPdfService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm:ss");
    private static final DateTimeFormatter DATE_ONLY_FMT = DateTimeFormatter.ofPattern("d MMM yyyy");
    private static final DateTimeFormatter TIME_ONLY_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final String FONT_FAMILY = "Roboto";
    private static final String FONT_RESOURCE = "fonts/Roboto-Regular.ttf";
    private static final String LOGO_RESOURCE = "branding/kortex_logo_small.png";

    /** Dark navy used for the closing footer band — matches the logo tile's own background. */
    private static final String BRAND_INK = "#01091C";

    private static final String TAGLINE = "Test Smarter. Deliver Confidently.";
    private static final String REPORT_TAGLINE = "Automate  |  Validate  |  Accelerate";
    private static final String CLOSING_QUOTE = "Automating today for a more reliable tomorrow.";

    private static final String CSS = """
            @page {
                size: A4;
                margin: 22px 36px 40px 36px;
                @bottom-center {
                    content: "KORTEX  \\2022  Page " counter(page) " of " counter(pages);
                    font-family: 'Roboto', sans-serif;
                    font-size: 7.5px;
                    letter-spacing: 0.05em;
                    color: #94a3b8;
                }
            }
            * { box-sizing: border-box; }
            body { font-family: 'Roboto', sans-serif; font-size: 10px; color: #1e293b; margin:0; line-height:1.5; background:#ffffff; }

            /* ── Header band (full-bleed, diagonal accent) ───────────────────── */
            .header-band { margin: -22px -36px 0 -36px; padding: 22px 36px 18px;
                background: linear-gradient(135deg, #ffffff 0%%, #ffffff 60%%, #eef2ff 60%%, #eef2ff 100%%);
                border-bottom: 2px solid #e2e8f0; }
            .header-row { display:table; width:100%%; }
            .header-left { display:table-cell; vertical-align:top; }
            .header-right { display:table-cell; vertical-align:top; text-align:right; }
            .brand-row { display:table; }
            .brand-logo-cell { display:table-cell; width:40px; vertical-align:middle; }
            .brand-logo { width:34px; height:34px; display:block; border-radius:7px; }
            .brand-word-cell { display:table-cell; vertical-align:middle; padding-left:9px; }
            .brand-word { font-size:22px; font-weight:700; color:#1e293b; letter-spacing:-0.01em; }
            .brand-tagline { font-size:8.5px; color:#94a3b8; margin-top:4px; }
            .header-eyebrow { font-size:9px; font-weight:700; letter-spacing:0.14em; text-transform:uppercase; color:#64748b; }
            .header-title { font-size:19px; font-weight:700; color:#1e293b; margin-top:3px; }
            .header-tagline { font-size:7.5px; color:#94a3b8; letter-spacing:0.08em; margin-top:6px; text-transform:uppercase; }

            /* ── Test info bar ────────────────────────────────────────────────── */
            .info-bar { display:table; width:100%%; padding: 18px 0 16px; border-bottom:1px solid #e2e8f0; }
            .info-left { display:table-cell; vertical-align:middle; }
            .info-test-name { font-size:16px; font-weight:700; color:#1e293b; }
            .info-url { font-size:9px; color:#2563eb; margin-top:5px; }
            .info-meta-row { margin-top:9px; font-size:8.5px; color:#64748b; }
            .info-meta-row .sep { margin: 0 9px; color:#cbd5e1; }
            .info-right { display:table-cell; vertical-align:middle; text-align:right; width:200px; }
            .status-chip { display:inline-block; text-align:left; border-radius:8px; padding:10px 16px; min-width:170px; }
            .status-chip-title { font-size:15px; font-weight:700; }
            .status-chip-dot { display:inline-block; width:10px; height:10px; border-radius:50%%; margin-right:7px; vertical-align:middle; }
            .status-chip-sub { font-size:8px; color:#64748b; margin-top:3px; }
            .status-chip.success { background:#ecfdf5; border:1px solid #a7f3d0; }
            .status-chip.success .status-chip-title { color:#059669; }
            .status-chip.error { background:#fef2f2; border:1px solid #fecaca; }
            .status-chip.error .status-chip-title { color:#dc2626; }
            .status-chip.warning { background:#fffbeb; border:1px solid #fde68a; }
            .status-chip.warning .status-chip-title { color:#b45309; }
            .status-chip.neutral { background:#f8fafc; border:1px solid #e2e8f0; }
            .status-chip.neutral .status-chip-title { color:#475569; }

            .section-title { font-size:12px; font-weight:700; color:#1e293b; margin:20px 0 10px; }
            .meta { color:#64748b; font-size: 9.5px; }

            /* ── Summary cards ─────────────────────────────────────────────────── */
            .summary-grid { display:table; width:100%%; table-layout:fixed; border-spacing:8px 0; margin-bottom:4px; }
            .summary-cell { display:table-cell; border:1px solid #e2e8f0; border-radius:8px; padding:12px; background:#ffffff; vertical-align:top; }
            .summary-icon { width:22px; height:22px; border-radius:50%%; margin-bottom:9px; }
            .summary-label { font-size:7.5px; text-transform:uppercase; letter-spacing:0.05em; color:#94a3b8; font-weight:700; }
            .summary-value { font-size:14.5px; font-weight:700; color:#1e293b; margin-top:4px; }
            .summary-sub { font-size:7.8px; color:#94a3b8; margin-top:2px; }

            /* ── Status badges (table pills) ──────────────────────────────────── */
            .badge { display:inline-block; padding:2.5px 9px 2.5px 8px; border-radius: 9px; font-size: 8px; font-weight:700; text-transform:uppercase; letter-spacing:0.03em; }
            .badge-dot { display:inline-block; width:6px; height:6px; border-radius:50%%; margin-right:5px; vertical-align:middle; }
            .badge-success{background:#d1fae5;color:#065f46;} .badge-success .badge-dot{background:#10b981;}
            .badge-error{background:#fee2e2;color:#991b1b;} .badge-error .badge-dot{background:#ef4444;}
            .badge-warning{background:#fef3c7;color:#92400e;} .badge-warning .badge-dot{background:#f59e0b;}
            .badge-moderate{background:#fef9c3;color:#854d0e;} .badge-moderate .badge-dot{background:#eab308;}
            .badge-review{background:#ede9fe;color:#5b21b6;} .badge-review .badge-dot{background:#8b5cf6;}
            .badge-neutral{background:#f1f5f9;color:#475569;} .badge-neutral .badge-dot{background:#94a3b8;}

            /* ── Tables (shared) ──────────────────────────────────────────────── */
            table { width:100%%; border-collapse: collapse; margin-bottom: 10px; }
            th { background:#f8fafc; text-align:left; padding:7px 8px; font-size:7.8px; text-transform:uppercase; letter-spacing:0.03em; color:#64748b; border-bottom:1px solid #e2e8f0;}
            td { padding:7px 8px; border-bottom:1px solid #f1f5f9; font-size:9px; vertical-align:middle; }
            .row-failed td { background:#fef2f2; }
            .error-box { background:#fef2f2;border:1px solid #fecaca;color:#991b1b;padding:9px 11px;border-radius:6px;margin:12px 0;font-size:9.5px; }
            .zebra tbody tr:nth-child(even) td { background:#f8fafc; }
            .zebra .row-failed td { background:#fef2f2; }

            /* ── Step / row results table ──────────────────────────────────────── */
            .step-num { font-family:monospace; color:#94a3b8; font-weight:700; width:24px; }
            .action-chip { display:inline-block; background:#eef2ff; color:#4338ca; font-size:7.5px; font-weight:700; padding:3px 8px; border-radius:4px; text-transform:uppercase; letter-spacing:0.02em; white-space:nowrap; }
            .step-name { font-weight:600; color:#1e293b; }
            .step-value { color:#64748b; font-family:monospace; font-size:8.5px; }
            .step-duration { color:#64748b; font-family:monospace; font-size:8.5px; white-space:nowrap; }
            .step-error-cell { color:#dc2626; font-size:8.5px; font-weight:600; }
            .step-row-failed .step-name { color:#991b1b; }
            .step-error-row td { background:#fef2f2; border-bottom:1px solid #f1f5f9; padding:0 8px 8px 32px; font-size:8.5px; color:#991b1b; }

            /* ── Data-Driven row cards ─────────────────────────────────────────── */
            .row-block { background:#ffffff; border:1px solid #e2e8f0; border-left-width:4px; border-radius:8px; margin-bottom:11px; }
            .row-header { background:#f8fafc; padding:8px 12px; border-bottom:1px solid #e2e8f0; border-radius:0 7px 0 0; }
            .row-body { padding:10px 12px; }
            .kv-label { font-size:8px; font-weight:700; text-transform:uppercase; letter-spacing:0.04em; color:#64748b; margin-bottom:4px; }
            .chip { display:inline-block; background:#f1f5f9; border:1px solid #e2e8f0; border-radius:9px; padding:2px 8px; font-size:8.5px; margin:0 4px 4px 0; }

            /* ── Accessibility findings ────────────────────────────────────────── */
            .finding { background:#ffffff; border:1px solid #e2e8f0; border-radius:8px; padding:10px 12px; margin-bottom:8px; }
            .finding-header { margin-bottom:2px; }
            .mono { font-family: monospace; }

            /* ── Technical appendix ────────────────────────────────────────────── */
            .tech-appendix { margin-top: 20px; }
            .tech-note { color:#94a3b8; font-size:8.5px; margin: -4px 0 8px; }
            .tech-table td, .tech-table th { font-size:8px; }

            /* ── Bottom two-panel (Test Information + Result) ─────────────────── */
            .bottom-grid { display:table; width:100%%; border-spacing:10px 0; margin-top:8px; }
            .bottom-cell { display:table-cell; vertical-align:top; width:50%%; }
            .info-panel { border:1px solid #e2e8f0; border-radius:8px; padding:14px 16px; }
            .panel-title { font-size:11px; font-weight:700; color:#1e293b; margin-bottom:11px; }
            .kv-row { display:table; width:100%%; padding:5px 0; border-bottom:1px solid #f8fafc; }
            .kv-key { display:table-cell; width:42%%; font-size:8.5px; color:#94a3b8; }
            .kv-val { display:table-cell; font-size:9px; color:#1e293b; font-weight:600; }
            .result-panel { border-radius:8px; padding:16px; }
            .result-panel.success { background:#ecfdf5; border:1px solid #a7f3d0; }
            .result-panel.error { background:#fef2f2; border:1px solid #fecaca; }
            .result-panel.warning { background:#fffbeb; border:1px solid #fde68a; }
            .result-panel.neutral { background:#f8fafc; border:1px solid #e2e8f0; }
            .result-headline { font-size:14px; font-weight:700; margin:9px 0 7px; }
            .result-headline.success-text { color:#059669; }
            .result-headline.error-text { color:#dc2626; }
            .result-headline.warning-text { color:#b45309; }
            .result-headline.neutral-text { color:#475569; }
            .result-body { font-size:9px; color:#475569; }
            .result-quote { font-size:8.5px; font-style:italic; color:#64748b; margin-top:12px; padding-top:11px; border-top:1px solid rgba(15,23,42,0.08); }
            .result-quote .attrib { display:block; margin-top:4px; font-style:normal; font-weight:700; color:#94a3b8; text-align:right; }

            /* ── Closing band (full-bleed, in-flow — appears once) ─────────────── */
            .footer-band { margin: 26px -36px -40px -36px; padding:16px 36px; background: %s; }
            .footer-row { display:table; width:100%%; }
            .footer-left { display:table-cell; vertical-align:middle; }
            .footer-brand-row { display:table; }
            .footer-logo-cell { display:table-cell; width:30px; vertical-align:middle; }
            .footer-logo { width:24px; height:24px; display:block; border-radius:5px; }
            .footer-word-cell { display:table-cell; vertical-align:middle; padding-left:8px; }
            .footer-word { font-size:13px; font-weight:700; color:#ffffff; }
            .footer-tagline { font-size:7.5px; color:#94a3b8; margin-top:2px; }
            .footer-right { display:table-cell; vertical-align:middle; text-align:right; font-size:8px; color:#94a3b8; }
            .footer-right div { margin-top:3px; }
            """.formatted(BRAND_INK);

    private final ObjectMapper objectMapper;

    public ReportPdfService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ── Public entry points ──────────────────────────────────────────────

    public byte[] generateStandardRunPdf(TestRunEntity run) {
        return renderHtmlToPdf(buildStandardRunHtml(run));
    }

    public byte[] generateDataDrivenRunPdf(DataDrivenRunEntity run) {
        return renderHtmlToPdf(buildDataDrivenRunHtml(run));
    }

    public byte[] generateAccessibilityRunPdf(AccessibilityScanRunEntity run) {
        return renderHtmlToPdf(buildAccessibilityRunHtml(run));
    }

    public byte[] generateApiRunPdf(ApiRunEntity run) {
        return renderHtmlToPdf(buildApiRunHtml(run));
    }

    // ── Rendering ─────────────────────────────────────────────────────────
    //
    // Font bytes and the logo data URI are read from the classpath and
    // (for the logo) base64-encoded exactly ONCE per JVM lifetime, cached in
    // these fields, then reused on every report — not re-read/re-encoded per
    // request. Confirmed to matter: the bundled logo was originally a
    // 1254x1254, ~936KB PNG (the same file used for the app's favicon,
    // sized for that purpose); decoding an image that large from a fresh
    // base64 string on every single "Email Report" click was real, avoidable
    // work happening synchronously in the request path. It's now bundled at
    // 160x160 (~25KB) specifically for PDF embedding — still crisp at the
    // ~30-50px it's ever displayed at in the report, a fraction of the
    // decode cost. Both fields are lazily initialised (not a static
    // initializer) so a missing/unreadable resource degrades gracefully on
    // first use instead of failing class loading.
    private static volatile byte[] cachedFontBytes;
    private static volatile String cachedLogoDataUri;

    private static byte[] fontBytes() {
        byte[] bytes = cachedFontBytes;
        if (bytes == null) {
            try {
                bytes = new ClassPathResource(FONT_RESOURCE).getInputStream().readAllBytes();
            } catch (Exception e) {
                throw new ReportEmailDeliveryException("Failed to load the report font: " + e.getMessage(), e);
            }
            cachedFontBytes = bytes;
        }
        return bytes;
    }

    /**
     * Base64 data URI for the bundled Kortex logo. Never throws: a missing
     * logo degrades to a text-only header rather than blocking the user's
     * actual execution report.
     */
    private static String logoDataUri() {
        String uri = cachedLogoDataUri;
        if (uri == null) {
            try {
                byte[] bytes = new ClassPathResource(LOGO_RESOURCE).getInputStream().readAllBytes();
                uri = "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes);
            } catch (Exception e) {
                uri = "";
            }
            cachedLogoDataUri = uri;
        }
        return uri.isEmpty() ? null : uri;
    }

    private byte[] renderHtmlToPdf(String html) {
        try {
            byte[] fontBytes = fontBytes();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            PdfRendererBuilder builder = new PdfRendererBuilder();
            // Registered once (not once per weight): there is no genuine bold
            // TTF bundled, so a second registration at weight 700 using the
            // SAME bytes previously made PDFBox subset/embed the identical
            // font data twice on every render for no visual gain — CSS
            // font-weight:700 still renders (synthetically bolded) against
            // this single registration.
            builder.useFont(() -> new ByteArrayInputStream(fontBytes), FONT_FAMILY, 400, FontStyle.NORMAL, true);
            builder.withHtmlContent(html, null);
            builder.toStream(out);
            builder.run();
            return out.toByteArray();
        } catch (Exception e) {
            throw new ReportEmailDeliveryException("Failed to generate the PDF report: " + e.getMessage(), e);
        }
    }

    // ── UI Automation ─────────────────────────────────────────────────────

    private String buildStandardRunHtml(TestRunEntity run) {
        String name = run.getScenario() != null ? run.getScenario().getName() : "UI Automation Test";
        String url = run.getScenario() != null ? run.getScenario().getTargetUrl() : "";
        Map<Integer, TestStepEntity> metaByOrder = stepMetadataByOrder(run.getScenario());
        boolean passed = "PASSED".equals(run.getStatus());

        StringBuilder body = new StringBuilder();
        body.append(infoBar(name, url, "Run #" + run.getId(), fmt(run.getStartedAt()), "UI Automation",
                statusChip(run.getStatus(), passed ? "All steps completed successfully" : "See details below")));

        if (notBlank(run.getErrorMessage())) {
            body.append(errorBox("Execution Error", run.getErrorMessage()));
        }

        body.append(sectionTitle("Execution Summary"));
        body.append(summaryGrid(
                summaryCard("Status", nonEmpty(run.getStatus()), passed ? "All steps completed" : "Execution finished", statusAccentColor(run.getStatus())),
                summaryCard("Started", fmtDateOnly(run.getStartedAt()), fmtTimeOnly(run.getStartedAt()), "#2563eb"),
                summaryCard("Duration", durationSeconds(run.getTotalDurationMs()), "Total execution time", "#7c3aed"),
                summaryCard("Steps Passed", run.getPassedSteps() + " / " + run.getTotalSteps(), successRateLabel(run.getPassedSteps(), run.getTotalSteps()), "#8b5cf6")
        ));

        body.append(sectionTitle("Step-by-Step Results"));
        List<TestRunStepEntity> steps = run.getStepResults();
        if (steps == null || steps.isEmpty()) {
            body.append("<p class=\"meta\">No steps were recorded during this execution.</p>");
        } else {
            body.append(stepsTableHeader());
            for (TestRunStepEntity step : steps) {
                TestStepEntity meta = metaByOrder.get(step.getStepOrder());
                boolean failed = "FAILED".equals(step.getStatus());
                String stepName = describeStep(meta, step.getActionType());
                body.append(stepRow(step.getStepOrder(), step.getActionType(), stepName, step.getInputValue(),
                        step.getStatus(), step.getDurationMs(), failed));
                if (failed && notBlank(step.getErrorMessage())) {
                    body.append(stepErrorRow(step.getErrorMessage()));
                }
            }
            body.append("</tbody></table>");
            body.append(technicalAppendixForStandard(steps));
        }

        body.append(bottomPanels(
                testInfoPanel(
                        kv("Test Name", esc(name)),
                        kv("Target URL", linkHtml(url)),
                        kv("Run ID", esc("#" + run.getId())),
                        kv("Test Type", "UI Automation"),
                        kv("Executed On", esc(fmt(run.getStartedAt()))),
                        kv("Total Steps", String.valueOf(run.getTotalSteps())),
                        kv("Passed Steps", String.valueOf(run.getPassedSteps())),
                        kv("Failed Steps", String.valueOf(run.getFailedSteps()))
                ),
                resultPanel(run.getStatus(),
                        passed ? "Test Passed Successfully!" : "Test Failed",
                        passed
                                ? "All " + run.getTotalSteps() + " steps executed successfully. The application behaved as expected."
                                : run.getFailedSteps() + " of " + run.getTotalSteps() + " step(s) failed. See the step details above for the exact failure.")
        ));

        return htmlShell("UI AUTOMATION", "EXECUTION REPORT",
                "UI Automation Execution Report", "Run #" + run.getId() + "  |  " + fmt(run.getStartedAt()),
                body.toString());
    }

    private String technicalAppendixForStandard(List<TestRunStepEntity> steps) {
        boolean anyDetail = steps.stream().anyMatch(s -> notBlank(s.getPrimarySelector()) || notBlank(s.getInputValue()));
        if (!anyDetail) return "";
        StringBuilder sb = new StringBuilder("<div class=\"tech-appendix\">");
        sb.append(sectionTitle("Technical Reference"));
        sb.append("<div class=\"tech-note\">Selector and value detail for QA/engineering debugging — not needed for a general read of this report.</div>");
        sb.append("<table class=\"tech-table zebra\"><thead><tr><th>#</th><th>Action</th><th>Selector Used</th><th>Value</th></tr></thead><tbody>");
        for (TestRunStepEntity step : steps) {
            sb.append("<tr><td>").append(step.getStepOrder()).append("</td>")
                    .append("<td>").append(esc(step.getActionType())).append("</td>")
                    .append("<td class=\"mono\">").append(esc(step.getPrimarySelector())).append("</td>")
                    .append("<td class=\"mono\">").append(esc(step.getInputValue())).append("</td>")
                    .append("</tr>");
        }
        sb.append("</tbody></table></div>");
        return sb.toString();
    }

    // ── Data Driven ───────────────────────────────────────────────────────

    private String buildDataDrivenRunHtml(DataDrivenRunEntity run) {
        String name = run.getScenario() != null ? run.getScenario().getName() : "Data Driven Test";
        String url = run.getScenario() != null ? run.getScenario().getTargetUrl() : "";
        int passRate = run.getTotalRows() > 0 ? Math.round(run.getPassedRows() * 100f / run.getTotalRows()) : 0;
        Map<Integer, TestStepEntity> metaByOrder = stepMetadataByOrder(run.getScenario());
        boolean passed = "PASSED".equals(run.getStatus());

        StringBuilder body = new StringBuilder();
        body.append(infoBar(name, url, "Run #" + run.getId(), fmt(run.getStartedAt()), "Data Driven",
                statusChip(run.getStatus(), passed ? "All rows completed successfully" : "See details below")));

        if (notBlank(run.getErrorMessage())) {
            body.append(errorBox("Execution Error", run.getErrorMessage()));
        }

        body.append(sectionTitle("Execution Summary"));
        body.append(summaryGrid(
                summaryCard("Status", nonEmpty(run.getStatus()), passed ? "All rows completed" : "Execution finished", statusAccentColor(run.getStatus())),
                summaryCard("Started", fmtDateOnly(run.getStartedAt()), fmtTimeOnly(run.getStartedAt()), "#2563eb"),
                summaryCard("Duration", durationSeconds(run.getTotalDurationMs()), "Total execution time", "#7c3aed"),
                summaryCard("Data Source", nonEmpty(run.getDatasetFilename()), "Dataset file", "#0891b2"),
                summaryCard("Rows Passed", run.getPassedRows() + " / " + run.getTotalRows(), passRate + "% success rate", "#8b5cf6")
        ));

        body.append(sectionTitle("Row-by-Row Results"));
        List<DataDrivenRowResultEntity> rows = run.getRowResults();
        if (rows == null || rows.isEmpty()) {
            body.append("<p class=\"meta\">No rows were executed.</p>");
        } else {
            for (DataDrivenRowResultEntity row : rows) {
                body.append("<div class=\"row-block\" style=\"border-left-color:")
                        .append(statusAccentColor(row.getStatus())).append(";\">");
                body.append("<div class=\"row-header\"><strong>Row ").append(row.getRowNumber()).append("</strong> &#160; ")
                        .append(badge(row.getStatus())).append(" &#160; <span class=\"meta\">").append(esc(stepMillis(row.getDurationMs()))).append("</span></div>");
                body.append("<div class=\"row-body\">");

                Map<String, Object> rowData = parseJsonMap(row.getRowDataJson());
                if (!rowData.isEmpty()) {
                    body.append("<div class=\"kv-label\">Input Data</div><div>");
                    for (Map.Entry<String, Object> e : rowData.entrySet()) {
                        body.append("<span class=\"chip\">").append(esc(e.getKey())).append(": <b>").append(esc(e.getValue())).append("</b></span>");
                    }
                    body.append("</div>");
                }

                List<Map<String, Object>> stepResults = parseJsonList(row.getStepResultsJson());
                if (!stepResults.isEmpty()) {
                    body.append("<div class=\"kv-label\" style=\"margin-top:8px;\">Step Results</div>");
                    body.append(stepsTableHeader());
                    for (Map<String, Object> sr : stepResults) {
                        String st = String.valueOf(sr.getOrDefault("status", ""));
                        boolean failed = "FAILED".equals(st);
                        Integer stepOrder = asInt(sr.get("stepOrder"));
                        TestStepEntity meta = stepOrder != null ? metaByOrder.get(stepOrder) : null;
                        String actType = String.valueOf(sr.get("actionType"));
                        String stepName = describeStep(meta, actType);
                        long durMs = asLong(sr.get("durationMs"));
                        body.append(stepRow(stepOrder != null ? stepOrder : 0, actType, stepName, null, st, durMs, failed));
                        Object errMsg = sr.get("errorMessage");
                        if (failed && errMsg != null && notBlank(String.valueOf(errMsg))) {
                            body.append(stepErrorRow(String.valueOf(errMsg)));
                        }
                    }
                    body.append("</tbody></table>");
                }

                if (notBlank(row.getErrorMessage())) {
                    String title = "Row Error" + (row.getFailedAtStep() > 0 ? " at step " + row.getFailedAtStep() : "");
                    body.append(errorBox(title, row.getErrorMessage()));
                }

                body.append("</div></div>");
            }
        }

        body.append(bottomPanels(
                testInfoPanel(
                        kv("Test Name", esc(name)),
                        kv("Target URL", linkHtml(url)),
                        kv("Run ID", esc("#" + run.getId())),
                        kv("Test Type", "Data Driven"),
                        kv("Executed On", esc(fmt(run.getStartedAt()))),
                        kv("Dataset File", esc(nonEmpty(run.getDatasetFilename()))),
                        kv("Total Rows", String.valueOf(run.getTotalRows())),
                        kv("Passed Rows", run.getPassedRows() + " / " + run.getTotalRows())
                ),
                resultPanel(run.getStatus(),
                        passed ? "Test Passed Successfully!" : "Test Failed",
                        passed
                                ? "All " + run.getTotalRows() + " data rows executed successfully. The application behaved as expected across every row."
                                : run.getFailedRows() + " of " + run.getTotalRows() + " row(s) failed. See the row details above for exact failures.")
        ));

        return htmlShell("DATA DRIVEN", "EXECUTION REPORT",
                "Data Driven Execution Report", "Run #" + run.getId() + "  |  " + fmt(run.getStartedAt()),
                body.toString());
    }

    // ── Accessibility ─────────────────────────────────────────────────────

    private String buildAccessibilityRunHtml(AccessibilityScanRunEntity run) {
        String name = run.getScan() != null ? run.getScan().getName() : "Accessibility Scan";
        String url = nonEmpty(run.getTargetUrl());
        boolean completed = "COMPLETED".equals(run.getStatus());

        StringBuilder body = new StringBuilder();
        body.append(infoBar(name, url, "Run #" + run.getId(), fmt(run.getStartedAt()), "Accessibility",
                statusChip(run.getStatus(), completed ? "Scan completed successfully" : "See details below")));

        body.append(sectionTitle("Execution Summary"));
        body.append(summaryGrid(
                summaryCard("Status", nonEmpty(run.getStatus()), completed ? "Scan completed" : "Scan finished", statusAccentColor(run.getStatus())),
                summaryCard("Started", fmtDateOnly(run.getStartedAt()), fmtTimeOnly(run.getStartedAt()), "#2563eb"),
                summaryCard("Duration", durationSeconds(run.getDurationMs()), "Total scan time", "#7c3aed"),
                summaryCard("Violations", String.valueOf(run.getTotalViolations()),
                        run.getTotalViolations() == 0 ? "No issues found" : "Issues detected",
                        run.getTotalViolations() == 0 ? "#10b981" : "#ef4444")
        ));

        body.append(summaryGrid(
                summaryCard("Critical", String.valueOf(run.getCriticalCount()), "", "#ef4444"),
                summaryCard("Serious", String.valueOf(run.getSeriousCount()), "", "#f59e0b"),
                summaryCard("Moderate", String.valueOf(run.getModerateCount()), "", "#eab308"),
                summaryCard("Minor", String.valueOf(run.getMinorCount()), "", "#64748b"),
                summaryCard("Needs Review", String.valueOf(run.getNeedsReviewCount()), "", "#8b5cf6"),
                summaryCard("Passed", String.valueOf(run.getPassedCount()), "", "#10b981")
        ));

        if (notBlank(run.getErrorMessage())) {
            body.append(errorBox("Scan Error", run.getErrorMessage()));
        }

        if (completed) {
            body.append("<p class=\"meta\">Automatically detected accessibility issues — automated tools cannot find every problem; manual review is still required for full coverage.</p>");

            List<RuleFindingDto> violations = parseFindings(run.getViolationsJson());
            List<RuleFindingDto> incomplete = parseFindings(run.getIncompleteJson());
            List<PassSummaryDto> passes = parsePasses(run.getPassesSummaryJson());

            body.append(wcagConformanceSection(violations, incomplete, passes));

            body.append(sectionTitle("Violations (" + violations.size() + ")"));
            if (violations.isEmpty()) {
                body.append("<p class=\"meta\">No violations found.</p>");
            } else {
                for (RuleFindingDto v : violations) body.append(findingBlock(v));
            }

            body.append(sectionTitle("Needs Review (" + incomplete.size() + ")"));
            if (incomplete.isEmpty()) {
                body.append("<p class=\"meta\">Nothing flagged for manual review.</p>");
            } else {
                for (RuleFindingDto v : incomplete) body.append(findingBlock(v));
            }

            body.append(sectionTitle("Passed Checks (" + passes.size() + ")"));
            if (!passes.isEmpty()) {
                body.append("<table class=\"zebra\"><thead><tr><th>Rule</th><th>Description</th><th>Elements Checked</th></tr></thead><tbody>");
                for (PassSummaryDto p : passes) {
                    body.append("<tr><td class=\"mono\">").append(esc(p.getRuleId())).append("</td><td>")
                            .append(esc(p.getDescription())).append("</td><td>").append(p.getNodeCount()).append("</td></tr>");
                }
                body.append("</tbody></table>");
            }
        }

        body.append(bottomPanels(
                testInfoPanel(
                        kv("Scan Name", esc(name)),
                        kv("Target URL", linkHtml(url)),
                        kv("Run ID", esc("#" + run.getId())),
                        kv("Test Type", "Accessibility"),
                        kv("Executed On", esc(fmt(run.getStartedAt()))),
                        kv("Total Violations", String.valueOf(run.getTotalViolations())),
                        kv("Critical", String.valueOf(run.getCriticalCount())),
                        kv("Passed Checks", String.valueOf(run.getPassedCount()))
                ),
                resultPanel(run.getStatus(),
                        completed
                                ? (run.getTotalViolations() == 0 ? "No Accessibility Issues Found!" : run.getTotalViolations() + " Issue(s) Found")
                                : "Scan Failed",
                        completed
                                ? (run.getTotalViolations() == 0
                                        ? "The scan completed and found no automatically-detectable accessibility violations."
                                        : "The scan completed and found " + run.getTotalViolations() + " violation(s) requiring attention — see the details above.")
                                : "The scan did not complete successfully. See the error above for detail.")
        ));

        return htmlShell("ACCESSIBILITY", "SCAN REPORT",
                "Accessibility Scan Report", "Run #" + run.getId() + "  |  " + fmt(run.getStartedAt()),
                body.toString());
    }

    // ── API Testing ──────────────────────────────────────────────────────

    private String buildApiRunHtml(ApiRunEntity run) {
        String name = notBlank(run.getRunName()) ? run.getRunName() : "API Test Run";
        String collectionName = run.getCollection() != null ? run.getCollection().getName() : "—";
        String environmentName = run.getEnvironment() != null ? run.getEnvironment().getName() : "No environment";
        boolean completed = "PASSED".equals(run.getStatus()) || "FAILED".equals(run.getStatus());

        StringBuilder body = new StringBuilder();
        body.append(infoBar(name, collectionName + "  •  Environment: " + environmentName,
                "Run #" + run.getId(), fmt(run.getStartedAt()), "API Testing",
                statusChip(run.getStatus(), completed ? scoreLine(run) : "See details below")));

        body.append(sectionTitle("Execution Summary"));
        body.append(summaryGrid(
                summaryCard("Status", nonEmpty(run.getStatus()), completed ? "Run completed" : "Run finished", statusAccentColor(run.getStatus())),
                summaryCard("Started", fmtDateOnly(run.getStartedAt()), fmtTimeOnly(run.getStartedAt()), "#2563eb"),
                summaryCard("Duration", durationSeconds(run.getTotalDurationMs()), "Total run time", "#7c3aed"),
                summaryCard("Requests", String.valueOf(run.getTotalRequests()),
                        run.getFailedRequests() == 0 ? "All passed" : run.getFailedRequests() + " failed",
                        run.getFailedRequests() == 0 ? "#10b981" : "#ef4444")
        ));
        body.append(summaryGrid(
                summaryCard("Passed", String.valueOf(run.getPassedRequests()), "", "#10b981"),
                summaryCard("Failed", String.valueOf(run.getFailedRequests()), "", "#ef4444"),
                summaryCard("Iterations", String.valueOf(run.getIterationCount()), run.getDatasetFilename() != null ? "Data-driven" : "", "#8b5cf6"),
                summaryCard("Scope", nonEmpty(run.getScope()), "", "#64748b")
        ));

        if (notBlank(run.getErrorMessage())) {
            body.append(errorBox("Run Error", run.getErrorMessage()));
        }

        List<ApiRequestRunResultEntity> results = run.getRequestResults();
        body.append(sectionTitle("Request Results (" + results.size() + ")"));
        if (results.isEmpty()) {
            body.append("<p class=\"meta\">No requests were executed.</p>");
        } else {
            int index = 0;
            for (ApiRequestRunResultEntity r : results) {
                index++;
                body.append(apiRequestBlock(index, r, run.getIterationCount() > 1 || notBlank(run.getDatasetFilename())));
            }
        }

        body.append(bottomPanels(
                testInfoPanel(
                        kv("Run Name", esc(name)),
                        kv("Collection", esc(collectionName)),
                        kv("Environment", esc(environmentName)),
                        kv("Run ID", esc("#" + run.getId())),
                        kv("Test Type", "API Testing"),
                        kv("Executed On", esc(fmt(run.getStartedAt()))),
                        kv("Total Requests", String.valueOf(run.getTotalRequests())),
                        kv("Passed", String.valueOf(run.getPassedRequests()))
                ),
                resultPanel(run.getStatus(),
                        "PASSED".equals(run.getStatus()) ? "All Requests Passed!" : "FAILED".equals(run.getStatus()) ? run.getFailedRequests() + " Request(s) Failed" : "Run " + nonEmpty(run.getStatus()),
                        "PASSED".equals(run.getStatus())
                                ? "Every request in this run completed successfully and every configured assertion passed."
                                : "FAILED".equals(run.getStatus())
                                        ? run.getFailedRequests() + " of " + run.getTotalRequests() + " request(s) failed — see the details above."
                                        : "The run did not complete. See the error above for detail.")
        ));

        return htmlShell("API TESTING", "EXECUTION REPORT",
                "API Test Report", "Run #" + run.getId() + "  |  " + fmt(run.getStartedAt()),
                body.toString());
    }

    private String scoreLine(ApiRunEntity run) {
        return run.getPassedRequests() + " / " + run.getTotalRequests() + " request(s) passed";
    }

    private String apiRequestBlock(int index, ApiRequestRunResultEntity r, boolean showIteration) {
        StringBuilder sb = new StringBuilder("<div class=\"finding\">");
        String label = String.format("%02d", index) + "  " + nonEmpty(r.getMethod()) + "  " + nonEmpty(r.getRequestName());
        sb.append("<div class=\"finding-header\">")
                .append(apiStatusBadge(r.getStatus()))
                .append(" <strong>").append(esc(label)).append("</strong>")
                .append(" <span class=\"meta\">(").append(r.getDurationMs()).append("ms")
                .append(r.getHttpStatus() > 0 ? " — HTTP " + r.getHttpStatus() + " " + nonEmpty(r.getHttpStatusText()) : "")
                .append(showIteration ? " — iteration " + (r.getIterationIndex() + 1) : "")
                .append(")</span></div>");

        // No word-break here: openhtmltopdf does not support that CSS property (silently
        // ignored, confirmed via a build-time "unrecognized CSS property" warning) — a
        // long URL simply wraps at its existing "/" and "?" characters instead, which
        // covers the realistic case (URLs are rarely one unbroken run of characters).
        sb.append("<div class=\"meta\">").append(esc(nonEmpty(r.getResolvedUrl()))).append("</div>");

        if (notBlank(r.getErrorMessage()) && r.getHttpStatus() == 0) {
            sb.append("<div class=\"meta\" style=\"color:#ef4444;\">").append(esc(r.getErrorMessage())).append("</div>");
        }

        List<AssertionResult> assertions = parseAssertionResults(r.getAssertionResultsJson());
        if (!assertions.isEmpty()) {
            sb.append("<div style=\"margin-top:8px;\">");
            for (AssertionResult a : assertions) {
                String dotColor = a.isPassed() ? "#10b981" : "#ef4444";
                sb.append("<div class=\"meta\" style=\"margin:2px 0;\"><span class=\"badge-dot\" style=\"background:")
                        .append(dotColor).append(";\"></span>").append(esc(nonEmpty(a.getDescription())));
                if (!a.isPassed() && notBlank(a.getExpected())) {
                    sb.append(" — expected \"").append(esc(a.getExpected())).append("\", received \"")
                            .append(esc(nonEmpty(a.getActual()))).append("\"");
                }
                sb.append("</div>");
            }
            sb.append("</div>");
        }

        sb.append("</div>");
        return sb.toString();
    }

    private String apiStatusBadge(String status) {
        String cls = switch (nonEmpty(status)) {
            case "PASSED" -> "badge-success";
            case "FAILED", "NETWORK_ERROR", "TIMEOUT" -> "badge-error";
            case "SKIPPED" -> "badge-neutral";
            default -> "badge-neutral";
        };
        return "<span class=\"badge " + cls + "\"><span class=\"badge-dot\"></span>" + esc(nonEmpty(status).replace('_', ' ')) + "</span>";
    }

    private List<AssertionResult> parseAssertionResults(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, new TypeReference<List<AssertionResult>>() { });
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private String findingBlock(RuleFindingDto f) {
        StringBuilder sb = new StringBuilder("<div class=\"finding\">");
        String impactLabel = notBlank(f.getImpact()) ? f.getImpact() : "review";
        sb.append("<div class=\"finding-header\">")
                .append(impactBadge(impactLabel))
                .append(" <strong>").append(esc(notBlank(f.getDescription()) ? f.getDescription() : f.getRuleId())).append("</strong>")
                .append(" <span class=\"meta\">(").append(esc(f.getRuleId())).append(")</span>")
                .append("</div>");
        if (notBlank(f.getHelp())) {
            sb.append("<div class=\"meta\" style=\"margin:4px 0;\">").append(esc(f.getHelp())).append("</div>");
        }
        if (f.getTags() != null && !f.getTags().isEmpty()) {
            String wcag = f.getTags().stream().filter(t -> t != null && t.startsWith("wcag")).collect(Collectors.joining(", "));
            if (notBlank(wcag)) sb.append("<div class=\"meta\">WCAG: ").append(esc(wcag)).append("</div>");
        }
        if (notBlank(f.getHelpUrl())) {
            sb.append("<div class=\"meta\">How to fix: <span style=\"color:#2563eb;\">").append(esc(f.getHelpUrl())).append("</span></div>");
        }
        if (f.getNodes() != null && !f.getNodes().isEmpty()) {
            sb.append("<table class=\"zebra\" style=\"margin-top:6px;\"><thead><tr><th>Target</th><th>HTML</th><th>Failure Summary</th></tr></thead><tbody>");
            for (RuleNodeDto node : f.getNodes()) {
                sb.append("<tr><td class=\"mono\">").append(esc(node.getTarget())).append("</td>")
                        .append("<td class=\"mono\">").append(esc(node.getHtml())).append("</td>")
                        .append("<td>").append(esc(node.getFailureSummary())).append("</td></tr>");
            }
            sb.append("</tbody></table>");
        }
        sb.append("</div>");
        return sb.toString();
    }

    /**
     * Axe-core "wcagXXX" tag → WCAG 2.0/2.1 Success Criterion number + name,
     * ordered by SC number for a stable, readable report table. Deliberately
     * a static lookup rather than parsed from the tag string at runtime —
     * some SC numbers have two-digit sub-parts (e.g. 1.4.10, 2.5.4) which
     * makes "wcag1410" ambiguous to split back into "1.4.10" vs "1.41.0"
     * without a reference table.
     *
     * Covers WCAG 2.0 + 2.1 Level A and AA criteria, matching this app's own
     * WCAG_A/WCAG_AA scan options (see AccessibilityScanService.resolveAxeTags) —
     * AAA criteria are out of scope for the same reason they're not offered
     * as a scan option.
     */
    private static final Map<String, String> WCAG_SC_REFERENCE = buildWcagScReference();

    private static Map<String, String> buildWcagScReference() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("wcag111", "1.1.1 Non-text Content (A)");
        m.put("wcag121", "1.2.1 Audio-only and Video-only, Prerecorded (A)");
        m.put("wcag122", "1.2.2 Captions, Prerecorded (A)");
        m.put("wcag123", "1.2.3 Audio Description or Media Alternative, Prerecorded (A)");
        m.put("wcag124", "1.2.4 Captions, Live (AA)");
        m.put("wcag125", "1.2.5 Audio Description, Prerecorded (AA)");
        m.put("wcag131", "1.3.1 Info and Relationships (A)");
        m.put("wcag132", "1.3.2 Meaningful Sequence (A)");
        m.put("wcag133", "1.3.3 Sensory Characteristics (A)");
        m.put("wcag134", "1.3.4 Orientation (AA)");
        m.put("wcag135", "1.3.5 Identify Input Purpose (AA)");
        m.put("wcag141", "1.4.1 Use of Color (A)");
        m.put("wcag142", "1.4.2 Audio Control (A)");
        m.put("wcag143", "1.4.3 Contrast, Minimum (AA)");
        m.put("wcag144", "1.4.4 Resize Text (AA)");
        m.put("wcag145", "1.4.5 Images of Text (AA)");
        m.put("wcag1410", "1.4.10 Reflow (AA)");
        m.put("wcag1411", "1.4.11 Non-text Contrast (AA)");
        m.put("wcag1412", "1.4.12 Text Spacing (AA)");
        m.put("wcag1413", "1.4.13 Content on Hover or Focus (AA)");
        m.put("wcag211", "2.1.1 Keyboard (A)");
        m.put("wcag212", "2.1.2 No Keyboard Trap (A)");
        m.put("wcag214", "2.1.4 Character Key Shortcuts (A)");
        m.put("wcag221", "2.2.1 Timing Adjustable (A)");
        m.put("wcag222", "2.2.2 Pause, Stop, Hide (A)");
        m.put("wcag231", "2.3.1 Three Flashes or Below Threshold (A)");
        m.put("wcag241", "2.4.1 Bypass Blocks (A)");
        m.put("wcag242", "2.4.2 Page Titled (A)");
        m.put("wcag243", "2.4.3 Focus Order (A)");
        m.put("wcag244", "2.4.4 Link Purpose, In Context (A)");
        m.put("wcag245", "2.4.5 Multiple Ways (AA)");
        m.put("wcag246", "2.4.6 Headings and Labels (AA)");
        m.put("wcag247", "2.4.7 Focus Visible (AA)");
        m.put("wcag251", "2.5.1 Pointer Gestures (A)");
        m.put("wcag252", "2.5.2 Pointer Cancellation (A)");
        m.put("wcag253", "2.5.3 Label in Name (A)");
        m.put("wcag254", "2.5.4 Motion Actuation (A)");
        m.put("wcag311", "3.1.1 Language of Page (A)");
        m.put("wcag312", "3.1.2 Language of Parts (AA)");
        m.put("wcag321", "3.2.1 On Focus (A)");
        m.put("wcag322", "3.2.2 On Input (A)");
        m.put("wcag323", "3.2.3 Consistent Navigation (AA)");
        m.put("wcag324", "3.2.4 Consistent Identification (AA)");
        m.put("wcag331", "3.3.1 Error Identification (A)");
        m.put("wcag332", "3.3.2 Labels or Instructions (A)");
        m.put("wcag333", "3.3.3 Error Suggestion (AA)");
        m.put("wcag334", "3.3.4 Error Prevention, Legal/Financial/Data (AA)");
        m.put("wcag411", "4.1.1 Parsing (A)");
        m.put("wcag412", "4.1.2 Name, Role, Value (A)");
        m.put("wcag413", "4.1.3 Status Messages (AA)");
        return Collections.unmodifiableMap(m);
    }

    /**
     * A VPAT/ACR-style "WCAG Conformance Summary" — one row per Success
     * Criterion this app can scan for, rolled up from the same rule-level
     * violations/incomplete/passes this report already lists in detail.
     * Framed honestly in the report body as this tool's own summary, not a
     * claim of official ITI VPAT template compliance.
     *
     * Precedence when a Success Criterion is touched by more than one axe
     * rule with different outcomes: a real violation always wins over a
     * manual-review flag, which always wins over a clean pass — the summary
     * must never hide a known failure behind an unrelated passing rule for
     * the same criterion.
     */
    private String wcagConformanceSection(List<RuleFindingDto> violations, List<RuleFindingDto> incomplete,
                                           List<PassSummaryDto> passes) {
        Set<String> violationTags = collectWcagTags(violations.stream().map(RuleFindingDto::getTags));
        Set<String> incompleteTags = collectWcagTags(incomplete.stream().map(RuleFindingDto::getTags));
        Set<String> passTags = collectWcagTags(passes.stream().map(PassSummaryDto::getTags));

        StringBuilder sb = new StringBuilder();
        sb.append(sectionTitle("WCAG Conformance Summary"));
        sb.append("<p class=\"meta\">This tool's own per-criterion rollup of the automated results below — not an official VPAT/ACR filing. "
                + "\"Not Evaluated\" means no automated rule for that criterion applied to this page (e.g. no audio/video present); "
                + "many criteria always require manual testing regardless of automated result.</p>");
        sb.append("<table class=\"zebra\"><thead><tr><th>Success Criterion</th><th>Conformance</th></tr></thead><tbody>");
        for (Map.Entry<String, String> entry : WCAG_SC_REFERENCE.entrySet()) {
            String tag = entry.getKey();
            String label = entry.getValue();
            String status;
            String cls;
            if (violationTags.contains(tag)) {
                status = "Does Not Support";
                cls = "badge-error";
            } else if (incompleteTags.contains(tag)) {
                status = "Needs Review";
                cls = "badge-review";
            } else if (passTags.contains(tag)) {
                status = "Supports";
                cls = "badge-success";
            } else {
                status = "Not Evaluated";
                cls = "badge-neutral";
            }
            sb.append("<tr><td>").append(esc(label)).append("</td><td><span class=\"badge ")
                    .append(cls).append("\"><span class=\"badge-dot\"></span>").append(status).append("</span></td></tr>");
        }
        sb.append("</tbody></table>");
        return sb.toString();
    }

    private Set<String> collectWcagTags(java.util.stream.Stream<List<String>> tagLists) {
        Set<String> result = new LinkedHashSet<>();
        tagLists.forEach(tags -> {
            if (tags != null) {
                for (String t : tags) {
                    if (t != null && WCAG_SC_REFERENCE.containsKey(t)) result.add(t);
                }
            }
        });
        return result;
    }

    private String impactBadge(String impact) {
        String cls = switch (impact.toLowerCase()) {
            case "critical" -> "badge-error";
            case "serious" -> "badge-warning";
            case "moderate" -> "badge-moderate";
            case "minor" -> "badge-neutral";
            default -> "badge-review";
        };
        return "<span class=\"badge " + cls + "\">" + esc(impact) + "</span>";
    }

    // ── Clean, human-readable step naming ─────────────────────────────────
    //
    // The main results table must never show raw HTML/CSS/DOM selector code
    // (e.g. `input[type="text"][placeholder="Password"]`) — it reads a
    // step's ORIGINAL recorded metadata (label/placeholder/accessible name/
    // visible text/role) and turns it into a short verb phrase a
    // non-developer QA user can actually read. Raw selector/value detail is
    // never deleted, only relocated to an optional technical appendix (see
    // technicalAppendixForStandard).

    /**
     * Maps stepOrder → the ORIGINAL recorded {@link TestStepEntity} for a
     * scenario. {@link TestRunStepEntity} (the per-run RESULT row) does not
     * itself carry label/placeholder/role metadata — only the recorded
     * scenario's steps do, and {@code TestScenarioEntity.steps} is EAGER
     * fetched, so this is a safe, query-free, purely-additive lookup already
     * available on the same entity graph the report already reads.
     */
    private Map<Integer, TestStepEntity> stepMetadataByOrder(TestScenarioEntity scenario) {
        if (scenario == null || scenario.getSteps() == null) return Collections.emptyMap();
        Map<Integer, TestStepEntity> map = new HashMap<>();
        for (TestStepEntity step : scenario.getSteps()) {
            if (step != null) map.put(step.getStepOrder(), step);
        }
        return map;
    }

    /**
     * Best available human-readable name for the element a step acted on,
     * in priority order: associated label, aria-label, placeholder, visible
     * text (all already meant to be read by a person) — then a prettified
     * form of the field `name` or element id (code identifiers like
     * "txtUserId" or "user_name" read as "User Id" / "User Name") — then
     * null, letting the caller fall back to a generic verb-only phrase
     * rather than ever printing a raw selector.
     */
    private static String elementName(TestStepEntity meta) {
        if (meta == null) return null;
        String[] candidates = { meta.getLabelText(), meta.getAriaLabel(), meta.getPlaceholder(), meta.getText() };
        for (String c : candidates) {
            if (notBlank(c)) return c.trim();
        }
        String pretty = prettifyIdentifier(meta.getName());
        if (pretty != null) return pretty;
        return prettifyIdentifier(meta.getElementId());
    }

    /** Turns a code-style identifier ("txtUserId", "user_name") into readable text ("Txt User Id", "User Name"). */
    private static String prettifyIdentifier(String raw) {
        if (raw == null || raw.isBlank()) return null;
        String s = raw.trim();
        s = s.replaceAll("([a-z0-9])([A-Z])", "$1 $2");
        s = s.replaceAll("[_\\-]+", " ");
        s = s.replaceAll("\\s+", " ").trim();
        if (s.isEmpty()) return null;
        StringBuilder out = new StringBuilder();
        for (String w : s.split(" ")) {
            if (w.isEmpty()) continue;
            if (out.length() > 0) out.append(' ');
            if (w.length() <= 3 && w.equals(w.toUpperCase())) {
                out.append(w); // keep short acronyms (ID, URL, OTP) as-is
            } else {
                out.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1).toLowerCase());
            }
        }
        return out.length() > 0 ? out.toString() : null;
    }

    /**
     * Builds the human-readable step title shown in the main table, e.g.
     * "Enter Username", "Click Sign In", "Select Country". {@code meta} may
     * be null (a scenario edited/deleted since this run, or a step order
     * that no longer matches) — every branch degrades to a clean generic
     * phrase rather than ever surfacing a raw selector.
     */
    private static String describeStep(TestStepEntity meta, String actionType) {
        String action = actionType == null ? "" : actionType.trim().toLowerCase();
        String field = elementName(meta);
        boolean isSelect = meta != null && "select".equalsIgnoreCase(meta.getTag());
        boolean isToggle = meta != null && ("checkbox".equalsIgnoreCase(meta.getType()) || "radio".equalsIgnoreCase(meta.getType()));

        switch (action) {
            case "type": case "input": case "change":
                if (isSelect) return field != null ? "Select " + field : "Select an option";
                if (isToggle) return field != null ? "Check " + field : "Check the option";
                return field != null ? "Enter " + field : "Enter a value";
            case "click":
                if (isToggle) return field != null ? "Check " + field : "Check the option";
                return field != null ? "Click " + field : "Click";
            case "keydown": {
                String key = meta != null ? meta.getKey() : null;
                if (key != null && key.equalsIgnoreCase("Enter")) {
                    return field != null ? "Submit " + field : "Press Enter";
                }
                return notBlank(key) ? "Press " + key : "Press a key";
            }
            case "scroll":
                return field != null ? "Scroll to " + field : "Scroll the page";
            case "select":
                return field != null ? "Select " + field : "Select an option";
            case "check":
                return field != null ? "Check " + field : "Check the option";
            case "uncheck":
                return field != null ? "Uncheck " + field : "Uncheck the option";
            case "navigate":
                return field != null ? "Open " + field : "Open the page";
            case "upload":
                return field != null ? "Upload " + field : "Upload a file";
            case "assert": case "verify":
                return field != null ? "Verify " + field : "Verify condition";
            default:
                if (field != null) return capitalize(action.isEmpty() ? "Perform" : action) + " " + field;
                return notBlank(actionType) ? capitalize(actionType) : "Step";
        }
    }

    /** Short verb-only label for the ACTION column, distinct from describeStep()'s full human phrase. */
    private static String actionLabel(String actionType) {
        if (actionType == null) return "Action";
        String a = actionType.trim().toLowerCase();
        return switch (a) {
            case "type", "input", "change", "fill" -> "Type";
            case "click" -> "Click";
            case "keydown" -> "Key Press";
            case "scroll" -> "Scroll";
            case "select" -> "Select";
            case "check" -> "Check";
            case "uncheck" -> "Uncheck";
            case "navigate" -> "Navigate";
            case "upload" -> "Upload";
            case "assert", "verify" -> "Verify";
            default -> capitalize(actionType);
        };
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static Integer asInt(Object value) {
        if (value instanceof Number n) return n.intValue();
        if (value instanceof String s) {
            try { return Integer.valueOf(s); } catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    private static long asLong(Object value) {
        if (value instanceof Number n) return n.longValue();
        if (value instanceof String s) {
            try { return Long.parseLong(s); } catch (NumberFormatException ignored) { return 0L; }
        }
        return 0L;
    }

    // ── Shared HTML building blocks ───────────────────────────────────────

    private String htmlShell(String eyebrow, String title, String footerReportLabel, String footerRunLine, String bodyHtml) {
        String logo = logoDataUri();

        StringBuilder header = new StringBuilder("<div class=\"header-band\"><div class=\"header-row\">");
        header.append("<div class=\"header-left\"><div class=\"brand-row\">");
        if (logo != null) {
            header.append("<div class=\"brand-logo-cell\"><img class=\"brand-logo\" src=\"").append(logo).append("\"/></div>");
        }
        header.append("<div class=\"brand-word-cell\"><span class=\"brand-word\">Kortex</span></div>")
                .append("</div><div class=\"brand-tagline\">").append(esc(TAGLINE)).append("</div></div>");
        header.append("<div class=\"header-right\">")
                .append("<div class=\"header-eyebrow\">").append(esc(eyebrow)).append("</div>")
                .append("<div class=\"header-title\">").append(esc(title)).append("</div>")
                .append("<div class=\"header-tagline\">").append(esc(REPORT_TAGLINE)).append("</div>")
                .append("</div>");
        header.append("</div></div>");

        StringBuilder footer = new StringBuilder("<div class=\"footer-band\"><div class=\"footer-row\">");
        footer.append("<div class=\"footer-left\"><div class=\"footer-brand-row\">");
        if (logo != null) {
            footer.append("<div class=\"footer-logo-cell\"><img class=\"footer-logo\" src=\"").append(logo).append("\"/></div>");
        }
        footer.append("<div class=\"footer-word-cell\"><span class=\"footer-word\">Kortex</span></div>")
                .append("</div><div class=\"footer-tagline\">").append(esc(TAGLINE)).append("</div></div>");
        footer.append("<div class=\"footer-right\"><div>").append(esc(footerReportLabel)).append("</div>")
                .append("<div>").append(esc(footerRunLine)).append("</div></div>");
        footer.append("</div></div>");

        return "<html><head><meta charset=\"utf-8\"/><style>" + CSS + "</style></head><body>"
                + header
                + bodyHtml
                + footer
                + "</body></html>";
    }

    private String infoBar(String testName, String url, String runIdLabel, String executedOn, String testType, String statusChipHtml) {
        StringBuilder sb = new StringBuilder("<div class=\"info-bar\"><div class=\"info-left\">");
        sb.append("<div class=\"info-test-name\">").append(esc(testName)).append("</div>");
        if (notBlank(url)) {
            sb.append("<div class=\"info-url\">").append(esc(url)).append("</div>");
        }
        sb.append("<div class=\"info-meta-row\">").append(esc(runIdLabel))
                .append("<span class=\"sep\">|</span>").append(esc(executedOn))
                .append("<span class=\"sep\">|</span>").append(esc(testType))
                .append("</div>");
        sb.append("</div><div class=\"info-right\">").append(statusChipHtml).append("</div></div>");
        return sb.toString();
    }

    private String statusChip(String status, String subtitle) {
        String cls = statusChipClass(status);
        // A colored circle (the same safe, already-proven CSS technique as
        // .badge-dot) rather than a Unicode check/cross glyph — an earlier
        // attempt using "✓"/"✕" rendered as a literal "#" in the actual PDF
        // output (the glyph isn't in the embedded Roboto subset), so this
        // sticks to a shape CSS itself draws, not a font-dependent symbol.
        return "<div class=\"status-chip " + cls + "\"><div class=\"status-chip-title\">"
                + "<span class=\"status-chip-dot\" style=\"background:" + statusAccentColor(status) + ";\"></span>"
                + esc(nonEmpty(status)) + "</div><div class=\"status-chip-sub\">" + esc(subtitle) + "</div></div>";
    }

    private static String statusChipClass(String status) {
        if (status == null) return "neutral";
        return switch (status) {
            case "PASSED", "SUCCESS", "COMPLETED" -> "success";
            case "FAILED", "CRITICAL_FAILURE" -> "error";
            case "RUNNING", "HEALED_BY_AI" -> "warning";
            default -> "neutral";
        };
    }

    private String sectionTitle(String text) {
        return "<div class=\"section-title\">" + esc(text) + "</div>";
    }

    private String summaryCard(String label, String value, String subtitle, String iconColor) {
        StringBuilder sb = new StringBuilder("<div class=\"summary-cell\">")
                .append("<div class=\"summary-icon\" style=\"background:").append(iconColor).append(";\"></div>")
                .append("<div class=\"summary-label\">").append(esc(label)).append("</div>")
                .append("<div class=\"summary-value\">").append(esc(value)).append("</div>");
        if (notBlank(subtitle)) {
            sb.append("<div class=\"summary-sub\">").append(esc(subtitle)).append("</div>");
        }
        return sb.append("</div>").toString();
    }

    private String summaryGrid(String... cardsHtml) {
        StringBuilder sb = new StringBuilder("<div class=\"summary-grid\">");
        for (String c : cardsHtml) sb.append(c);
        return sb.append("</div>").toString();
    }

    private static String statusAccentColor(String status) {
        if (status == null) return "#cbd5e1";
        return switch (status) {
            case "PASSED", "SUCCESS", "COMPLETED" -> "#10b981";
            case "FAILED", "CRITICAL_FAILURE" -> "#ef4444";
            case "RUNNING", "HEALED_BY_AI" -> "#f59e0b";
            default -> "#cbd5e1";
        };
    }

    private String stepsTableHeader() {
        return "<table><thead><tr>"
                + "<th class=\"step-num\">#</th><th>Action</th><th>Step Name</th><th>Value</th><th>Status</th><th>Duration</th><th>Error</th>"
                + "</tr></thead><tbody>";
    }

    private String stepRow(int stepOrder, String actionType, String stepName, String value, String status, long durationMs, boolean failed) {
        return "<tr" + (failed ? " class=\"step-row-failed\"" : "") + ">"
                + "<td class=\"step-num\">" + String.format("%02d", stepOrder) + "</td>"
                + "<td><span class=\"action-chip\">" + esc(actionLabel(actionType)) + "</span></td>"
                + "<td class=\"step-name\">" + esc(stepName) + "</td>"
                + "<td class=\"step-value\">" + (notBlank(value) ? esc(value) : "—") + "</td>"
                + "<td>" + badge(status) + "</td>"
                + "<td class=\"step-duration\">" + esc(stepMillis(durationMs)) + "</td>"
                + "<td class=\"step-error-cell\">" + (failed ? "See below" : "—") + "</td>"
                + "</tr>";
    }

    private String stepErrorRow(String errorMessage) {
        return "<tr class=\"step-error-row\"><td colspan=\"7\">" + esc(errorMessage) + "</td></tr>";
    }

    private String bottomPanels(String leftPanelHtml, String rightPanelHtml) {
        return "<div class=\"bottom-grid\"><div class=\"bottom-cell\">" + leftPanelHtml + "</div>"
                + "<div class=\"bottom-cell\">" + rightPanelHtml + "</div></div>";
    }

    private String testInfoPanel(String... kvRowsHtml) {
        StringBuilder sb = new StringBuilder("<div class=\"info-panel\"><div class=\"panel-title\">Test Information</div>");
        for (String r : kvRowsHtml) sb.append(r);
        return sb.append("</div>").toString();
    }

    private String kv(String key, String valueHtml) {
        return "<div class=\"kv-row\"><div class=\"kv-key\">" + esc(key) + "</div><div class=\"kv-val\">" + valueHtml + "</div></div>";
    }

    private String linkHtml(String url) {
        if (!notBlank(url)) return "—";
        return "<span style=\"color:#2563eb;\">" + esc(url) + "</span>";
    }

    private String resultPanel(String status, String headline, String description) {
        String cls = statusChipClass(status);
        String headlineCls = cls + "-text";
        return "<div class=\"result-panel " + cls + "\"><div class=\"panel-title\">Result</div>"
                + "<div class=\"result-headline " + headlineCls + "\">" + esc(headline) + "</div>"
                + "<div class=\"result-body\">" + esc(description) + "</div>"
                + "<div class=\"result-quote\">“" + esc(CLOSING_QUOTE) + "”<span class=\"attrib\">— KORTEX</span></div>"
                + "</div>";
    }

    private String errorBox(String title, String message) {
        return "<div class=\"error-box\"><strong>" + esc(title) + ":</strong> " + esc(message) + "</div>";
    }

    private String badge(String status) {
        if (status == null) return "<span class=\"badge badge-neutral\"><span class=\"badge-dot\"></span>—</span>";
        String cls = switch (status) {
            case "PASSED", "SUCCESS", "COMPLETED" -> "badge-success";
            case "FAILED", "CRITICAL_FAILURE" -> "badge-error";
            case "RUNNING", "HEALED_BY_AI" -> "badge-warning";
            default -> "badge-neutral";
        };
        return "<span class=\"badge " + cls + "\"><span class=\"badge-dot\"></span>" + esc(status) + "</span>";
    }

    // ── Small helpers ─────────────────────────────────────────────────────

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String nonEmpty(String s) {
        return s == null ? "" : s;
    }

    private static String fmt(LocalDateTime dt) {
        return dt == null ? "—" : dt.format(DATE_FMT);
    }

    private static String fmtDateOnly(LocalDateTime dt) {
        return dt == null ? "—" : dt.format(DATE_ONLY_FMT);
    }

    private static String fmtTimeOnly(LocalDateTime dt) {
        return dt == null ? "—" : dt.format(TIME_ONLY_FMT);
    }

    private static String durationSeconds(long ms) {
        return String.format("%.2fs", ms / 1000.0);
    }

    private static String stepMillis(long ms) {
        return ms + "ms";
    }

    private static String successRateLabel(int passed, int total) {
        if (total <= 0) return "No steps recorded";
        int pct = Math.round(passed * 100f / total);
        return pct + "% success rate";
    }

    /** Escapes any dynamic value before it's concatenated into the HTML template. */
    private static String esc(Object value) {
        if (value == null) return "";
        String s = String.valueOf(value);
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private Map<String, Object> parseJsonMap(String json) {
        if (json == null || json.isBlank()) return Collections.emptyMap();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    private List<Map<String, Object>> parseJsonList(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() { });
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private List<RuleFindingDto> parseFindings(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, new TypeReference<List<RuleFindingDto>>() { });
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private List<PassSummaryDto> parsePasses(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, new TypeReference<List<PassSummaryDto>>() { });
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
