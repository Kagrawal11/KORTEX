import axios from 'axios';
import type {
  AccessibilityScan, AccessibilityScanRun, AccessibilityCreateScanRequest,
  AccessibilityDashboardSummary, AccessibilityScanTrend, ManualChecksMap
} from '../types';

const ACCESSIBILITY_API_BASE_URL = '/api/accessibility';

export const accessibilityApi = {
  /** Creates a scan config and immediately starts its first run (status=RUNNING). */
  createScan: async (request: AccessibilityCreateScanRequest): Promise<AccessibilityScanRun> => {
    const response = await axios.post(`${ACCESSIBILITY_API_BASE_URL}/scans`, request);
    return response.data;
  },

  /** Re-runs an existing scan using its saved configuration. */
  rerunScan: async (scanId: number): Promise<AccessibilityScanRun> => {
    const response = await axios.post(`${ACCESSIBILITY_API_BASE_URL}/scans/${scanId}/rerun`);
    return response.data;
  },

  getAllScans: async (): Promise<AccessibilityScan[]> => {
    const response = await axios.get(`${ACCESSIBILITY_API_BASE_URL}/scans`);
    return response.data;
  },

  getScan: async (scanId: number): Promise<AccessibilityScan> => {
    const response = await axios.get(`${ACCESSIBILITY_API_BASE_URL}/scans/${scanId}`);
    return response.data;
  },

  /** Every scan run across every scan, newest first — backs Scan History. */
  getAllRuns: async (): Promise<AccessibilityScanRun[]> => {
    const response = await axios.get(`${ACCESSIBILITY_API_BASE_URL}/runs`);
    return response.data;
  },

  /** Poll while status=RUNNING; once COMPLETED/FAILED it carries the full report. */
  getRun: async (runId: number): Promise<AccessibilityScanRun> => {
    const response = await axios.get(`${ACCESSIBILITY_API_BASE_URL}/runs/${runId}`);
    return response.data;
  },

  getDashboardSummary: async (): Promise<AccessibilityDashboardSummary> => {
    const response = await axios.get(`${ACCESSIBILITY_API_BASE_URL}/dashboard/summary`);
    return response.data;
  },

  /** Run-to-run history for one scan, oldest first, with regression/improvement deltas. */
  getScanTrend: async (scanId: number): Promise<AccessibilityScanTrend> => {
    const response = await axios.get(`${ACCESSIBILITY_API_BASE_URL}/scans/${scanId}/trend`);
    return response.data;
  },

  /** Saves the manual/guided testing checklist answers for one run. */
  saveManualChecks: async (runId: number, checks: ManualChecksMap): Promise<AccessibilityScanRun> => {
    const response = await axios.put(`${ACCESSIBILITY_API_BASE_URL}/runs/${runId}/manual-checks`, { checks });
    return response.data;
  },
};
