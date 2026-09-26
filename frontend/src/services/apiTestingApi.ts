import axios from 'axios';
import type {
  ApiCollection, ApiEnvironment, ApiEnvironmentVariable, ApiFolder, ApiRequestEntity,
  ApiRequestSpec, ApiAuthConfig, ApiRun, ExecuteRequestPayload, StartRunConfig,
} from '../types';

const BASE = '/api/api-testing';

export const apiTestingApi = {
  // ── Environments ───────────────────────────────────────────────────────
  getEnvironments: async (): Promise<ApiEnvironment[]> => (await axios.get(`${BASE}/environments`)).data,
  getEnvironment: async (id: number): Promise<ApiEnvironment> => (await axios.get(`${BASE}/environments/${id}`)).data,
  createEnvironment: async (name: string, variables: ApiEnvironmentVariable[]): Promise<ApiEnvironment> =>
    (await axios.post(`${BASE}/environments`, { name, variables })).data,
  updateEnvironment: async (id: number, name: string, variables: ApiEnvironmentVariable[]): Promise<ApiEnvironment> =>
    (await axios.put(`${BASE}/environments/${id}`, { name, variables })).data,
  deleteEnvironment: async (id: number): Promise<void> => { await axios.delete(`${BASE}/environments/${id}`); },

  // ── Collections ────────────────────────────────────────────────────────
  getCollections: async (): Promise<ApiCollection[]> => (await axios.get(`${BASE}/collections`)).data,
  getCollection: async (id: number): Promise<ApiCollection> => (await axios.get(`${BASE}/collections/${id}`)).data,
  createCollection: async (name: string, description?: string): Promise<ApiCollection> =>
    (await axios.post(`${BASE}/collections`, { name, description })).data,
  updateCollection: async (id: number, name: string, description: string | undefined,
                            auth: ApiAuthConfig, variables: ApiEnvironmentVariable[]): Promise<ApiCollection> =>
    (await axios.put(`${BASE}/collections/${id}`, { name, description, auth, variables })).data,
  duplicateCollection: async (id: number): Promise<ApiCollection> => (await axios.post(`${BASE}/collections/${id}/duplicate`)).data,
  deleteCollection: async (id: number): Promise<void> => { await axios.delete(`${BASE}/collections/${id}`); },
  getCollectionAuth: async (id: number): Promise<ApiAuthConfig> => (await axios.get(`${BASE}/collections/${id}/auth`)).data,

  // ── Folders ────────────────────────────────────────────────────────────
  getFolders: async (collectionId: number): Promise<ApiFolder[]> => (await axios.get(`${BASE}/collections/${collectionId}/folders`)).data,
  createFolder: async (collectionId: number, name: string): Promise<ApiFolder> =>
    (await axios.post(`${BASE}/collections/${collectionId}/folders`, { name })).data,
  renameFolder: async (folderId: number, name: string): Promise<ApiFolder> =>
    (await axios.put(`${BASE}/folders/${folderId}`, { name })).data,
  deleteFolder: async (folderId: number): Promise<void> => { await axios.delete(`${BASE}/folders/${folderId}`); },

  // ── Requests ───────────────────────────────────────────────────────────
  getRequestsForCollection: async (collectionId: number): Promise<ApiRequestEntity[]> =>
    (await axios.get(`${BASE}/collections/${collectionId}/requests`)).data,
  getRequestSpec: async (requestId: number): Promise<ApiRequestSpec> => (await axios.get(`${BASE}/requests/${requestId}/spec`)).data,
  createRequest: async (collectionId: number, folderId: number | null, spec: ApiRequestSpec): Promise<ApiRequestEntity> =>
    (await axios.post(`${BASE}/collections/${collectionId}/requests`, { folderId, spec })).data,
  updateRequest: async (requestId: number, spec: ApiRequestSpec): Promise<ApiRequestEntity> =>
    (await axios.put(`${BASE}/requests/${requestId}`, spec)).data,
  duplicateRequest: async (requestId: number): Promise<ApiRequestEntity> => (await axios.post(`${BASE}/requests/${requestId}/duplicate`)).data,
  moveRequest: async (requestId: number, folderId: number | null): Promise<ApiRequestEntity> =>
    (await axios.put(`${BASE}/requests/${requestId}/move`, { folderId })).data,
  deleteRequest: async (requestId: number): Promise<void> => { await axios.delete(`${BASE}/requests/${requestId}`); },

  // ── Import ─────────────────────────────────────────────────────────────
  importPostman: async (file: File): Promise<ApiCollection> => {
    const form = new FormData();
    form.append('file', file);
    return (await axios.post(`${BASE}/import/postman`, form)).data;
  },
  importOpenApi: async (file: File): Promise<ApiCollection> => {
    const form = new FormData();
    form.append('file', file);
    return (await axios.post(`${BASE}/import/openapi`, form)).data;
  },

  // ── Execution ──────────────────────────────────────────────────────────

  /** Ad-hoc single "Send" — always runs the CURRENT (possibly unsaved) request state. Synchronous. */
  execute: async (payload: ExecuteRequestPayload): Promise<ApiRun> => (await axios.post(`${BASE}/execute`, payload)).data,

  /** Starts a Collection Runner pass. Returns immediately with status=RUNNING — poll getRun(). */
  startRun: async (collectionId: number, config: StartRunConfig, datasetFile?: File | null): Promise<ApiRun> => {
    const form = new FormData();
    form.append('config', JSON.stringify(config));
    if (datasetFile) form.append('file', datasetFile);
    return (await axios.post(`${BASE}/collections/${collectionId}/run`, form)).data;
  },

  getRun: async (runId: number): Promise<ApiRun> => (await axios.get(`${BASE}/runs/${runId}`)).data,
  getAllRuns: async (): Promise<ApiRun[]> => (await axios.get(`${BASE}/runs`)).data,
  getRunsForCollection: async (collectionId: number): Promise<ApiRun[]> => (await axios.get(`${BASE}/collections/${collectionId}/runs`)).data,
};
