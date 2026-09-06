package com.miniautomation.backend.datadriven;

import java.util.List;
import java.util.Map;

/**
 * Configuration for a data-driven execution run.
 * Passed as JSON alongside the uploaded dataset file.
 */
public class DataDrivenConfig {

    /** Step order of the first step in the data-driven loop (inclusive). */
    private int startStepOrder;

    /** Step order of the last step in the data-driven loop (inclusive). */
    private int endStepOrder;

    /**
     * Optional manual field mappings: key = stepId (as String), value = dataset column name.
     * Takes highest priority over automatic mapping.
     */
    private Map<String, String> manualMappings;

    /**
     * Pre-validated resolved mappings when the client sends them directly.
     * Used when the user has already validated mappings in the UI.
     */
    private List<ResolvedMapping> resolvedMappings;

    public static class ResolvedMapping {
        // Boxed (not primitive long) deliberately: this DTO is deserialized from
        // client-supplied JSON in DataDrivenService.validateClientMappings, which
        // explicitly checks `if (stepId == null)` to reject a payload that omits
        // it. A primitive long can never be null, so that check was previously
        // dead code and a missing stepId silently became 0 instead of failing
        // loudly with a clear error message.
        private Long stepId;
        private int stepOrder;
        private String fieldLabel;
        private String datasetColumn;

        public Long getStepId()        { return stepId; }
        public void setStepId(Long id) { this.stepId = id; }
        public int getStepOrder()      { return stepOrder; }
        public void setStepOrder(int o){ this.stepOrder = o; }
        public String getFieldLabel()  { return fieldLabel; }
        public void setFieldLabel(String l) { this.fieldLabel = l; }
        public String getDatasetColumn() { return datasetColumn; }
        public void setDatasetColumn(String c) { this.datasetColumn = c; }
    }

    public int getStartStepOrder()  { return startStepOrder; }
    public void setStartStepOrder(int s) { this.startStepOrder = s; }

    public int getEndStepOrder()    { return endStepOrder; }
    public void setEndStepOrder(int e) { this.endStepOrder = e; }

    public Map<String, String> getManualMappings() { return manualMappings; }
    public void setManualMappings(Map<String, String> m) { this.manualMappings = m; }

    public List<ResolvedMapping> getResolvedMappings() { return resolvedMappings; }
    public void setResolvedMappings(List<ResolvedMapping> r) { this.resolvedMappings = r; }
}
