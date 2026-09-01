import axios from 'axios';
import type { CreateTestRequest, TestRun, TestScenario } from '../types';

const API_BASE_URL = 'http://localhost:8080/api/ui-automation';

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
  }
};
