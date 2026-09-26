import axios from 'axios';
import type {
  CreateTestRequest, TestRun, TestScenario,
  DatasetPreview, MappingValidationResult, DataDrivenRun,
  DashboardSummary, DashboardRuns
} from '../types';

export const API_BASE_URL = '/api/ui-automation';

/**
 * noVNC client for the live browser session, served by the backend container
 * (websockify -> x11vnc -> Xvfb :99) and reverse-proxied at /vnc/.
 *
 * This replaced the old polled-screenshot preview. That preview was read-only,
 * so nobody could record on a deployed instance, and it re-sent a full JPEG
 * every 1.5s whether the page had changed or not. VNC is delta-based: it sends
 * nothing while the screen is static, and it carries real input back.
 *
 * quality/compression are the bandwidth levers — lower quality, higher
 * compression if egress is constrained.
 */
export const RECORDING_VNC_URL =
  '/vnc/vnc.html?autoconnect=1&resize=scale&reconnect=1&quality=3&compression=9';

// ── Normal Record & Play API ─────────────────────────────────────────────────

export const uiAutomationApi = {
  // Scenarios
  getTests: async (): Promise<TestScenario[]> => {
    const response = await axios.get(`${API_BASE_URL}/tests`);
    return response.data;
  },

  getTest: async (id: number): Promise<TestScenario> => {
    const response = await axios.get(`${API_BASE_URL}/tests/${id}`);
    return response.data;
  },

  createTest: async (request: CreateTestRequest): Promise<TestScenario> => {
    const response = await axios.post(`${API_BASE_URL}/tests`, request);
    return response.data;
  },

  // Recording
  startRecording: async (id: number): Promise<void> => {
    await axios.post(`${API_BASE_URL}/tests/${id}/record/start`);
  },

  stopRecording: async (id: number): Promise<TestScenario> => {
    const response = await axios.post(`${API_BASE_URL}/tests/${id}/record/stop`);
    return response.data;
  },

  // Execution
  runTest: async (id: number): Promise<TestRun> => {
    const response = await axios.post(`${API_BASE_URL}/tests/${id}/run`);
    return response.data;
  },

  getTestRuns: async (id: number): Promise<TestRun[]> => {
    const response = await axios.get(`${API_BASE_URL}/tests/${id}/runs`);
    return response.data;
  },

  getTestRun: async (runId: number): Promise<TestRun> => {
    const response = await axios.get(`${API_BASE_URL}/runs/${runId}`);
    return response.data;
  },

  /** Real current-URL of the live browser session — used alongside the screenshot preview. */
  getRecordingStatus: async (): Promise<{ active: boolean; currentUrl: string }> => {
    const response = await axios.get(`${API_BASE_URL}/recording/status`);
    return response.data;
  }
};

// ── Data-Driven API ──────────────────────────────────────────────────────────

export const dataDrivenApi = {
  /**
   * Upload CSV/XLSX and return metadata preview (row count, column count, headers).
   * Does NOT start execution.
   */
  previewDataset: async (testId: number, file: File): Promise<DatasetPreview> => {
    const form = new FormData();
    form.append('file', file);
    const response = await axios.post(
      `${API_BASE_URL}/tests/${testId}/data-driven/preview`,
      form,
      { headers: { 'Content-Type': 'multipart/form-data' } }
    );
    return response.data;
  },

  /**
   * Validate field mappings between dataset columns and recorded input steps.
   */
  validateMapping: async (
    testId: number,
    startStepOrder: number,
    endStepOrder: number,
    datasetHeaders: string[],
    manualMappings?: Record<string, string>,
    sampleRows?: Record<string, string>[]
  ): Promise<MappingValidationResult> => {
    const response = await axios.post(
      `${API_BASE_URL}/tests/${testId}/data-driven/validate-mapping`,
      { startStepOrder, endStepOrder, datasetHeaders, manualMappings, sampleRows }
    );
    return response.data;
  },

  /**
   * Start a data-driven run. Returns immediately with status=RUNNING.
   */
  startRun: async (testId: number, file: File, config: object): Promise<DataDrivenRun> => {
    const form = new FormData();
    form.append('file', file);
    form.append('config', JSON.stringify(config));
    const response = await axios.post(
      `${API_BASE_URL}/tests/${testId}/data-driven/run`,
      form,
      { headers: { 'Content-Type': 'multipart/form-data' } }
    );
    return response.data;
  },

  /**
   * List all data-driven runs for a scenario.
   */
  getRunsForScenario: async (testId: number): Promise<DataDrivenRun[]> => {
    const response = await axios.get(`${API_BASE_URL}/tests/${testId}/data-driven/runs`);
    return response.data;
  },

  /**
   * Poll a specific data-driven run for live status and row-level results.
   */
  getRun: async (runId: number): Promise<DataDrivenRun> => {
    const response = await axios.get(`${API_BASE_URL}/data-driven/runs/${runId}`);
    return response.data;
  }
};

// ── Dashboard / global aggregation API ───────────────────────────────────────

export const dashboardApi = {
  /**
   * Cross-test counts, success rate, and a recent-activity feed — backs the
   * Dashboard page. Read-only, no side effects.
   */
  getSummary: async (): Promise<DashboardSummary> => {
    const response = await axios.get(`${API_BASE_URL}/dashboard/summary`);
    return response.data;
  },

  /**
   * Every standard + data-driven run across every test — backs the global
   * Execution History page.
   */
  getAllRuns: async (): Promise<DashboardRuns> => {
    const response = await axios.get(`${API_BASE_URL}/dashboard/runs`);
    return response.data;
  }
};
