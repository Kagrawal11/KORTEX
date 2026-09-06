import axios from 'axios';
import type { EmailReportRequest, EmailReportResponse } from '../types';

const REPORTS_API_BASE_URL = 'http://localhost:8080/api/reports';

/**
 * Shared by all three report pages (UI Automation, Data Driven,
 * Accessibility) — one backend endpoint handles all three report kinds, so
 * one small client here does too, rather than bolting this onto any single
 * capability's existing api service file.
 */
export const reportEmailApi = {
  emailReport: async (request: EmailReportRequest): Promise<EmailReportResponse> => {
    const response = await axios.post(`${REPORTS_API_BASE_URL}/email`, request);
    return response.data;
  },
};
