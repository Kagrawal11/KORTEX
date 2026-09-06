export interface TestStep {
  id?: number;
  stepOrder: number;
  actionType: string;
  primarySelector: string;
  elementId?: string;
  name?: string;
  type?: string;
  role?: string;
  labelText?: string;
  text?: string;
  placeholder?: string;
  inputValue?: string;
  testId?: string;
  ariaLabel?: string;
  frameSelector?: string;
  createdAt?: string;
}

export interface TestScenario {
  id: number;
  name: string;
  targetUrl: string;
  createdAt: string;
  steps: TestStep[];
}

export interface TestRunStep {
  id: number;
  stepOrder: number;
  actionType: string;
  primarySelector: string;
  inputValue?: string;
  status: 'PASSED' | 'FAILED' | 'HEALED_BY_AI' | 'SKIPPED';
  errorMessage?: string;
  durationMs: number;
}

export interface TestRun {
  id: number;
  scenario: {
    id: number;
    name: string;
    targetUrl: string;
  };
  status: 'RUNNING' | 'PASSED' | 'FAILED';
  startedAt: string;
  completedAt?: string;
  totalDurationMs: number;
  totalSteps: number;
  passedSteps: number;
  failedSteps: number;
  healedByAiSteps: number;
  errorMessage?: string;
  stepResults: TestRunStep[];
}

export interface CreateTestRequest {
  name: string;
  targetUrl: string;
}

// ── Data-Driven Types ────────────────────────────────────────────────────────

export interface DatasetPreview {
  filename: string;
  rowCount: number;
  columnCount: number;
  headers: string[];
  previewRows: Record<string, string>[];
}

export interface MappingApiDto {
  stepId: number;
  stepOrder: number;
  fieldLabel: string;
  datasetColumn: string;
}

export interface MappingValidationResult {
  valid: boolean;
  resolvedMappings: MappingApiDto[];
  unresolvedFields: string[];
}

export interface DataDrivenConfig {
  startStepOrder: number;
  endStepOrder: number;
  manualMappings?: Record<string, string>;
  resolvedMappings?: MappingApiDto[];
}

export interface DataDrivenRowResult {
  id: number;
  rowNumber: number;
  status: 'SUCCESS' | 'FAILED' | 'CRITICAL_FAILURE';
  durationMs: number;
  failedAtStep: number;
  errorMessage?: string;
  rowDataJson: string;
  stepResultsJson: string;
}

export interface DataDrivenRun {
  id: number;
  scenario: {
    id: number;
    name: string;
    targetUrl: string;
  };
  status: 'RUNNING' | 'PASSED' | 'FAILED';
  startStepOrder: number;
  endStepOrder: number;
  datasetFilename: string;
  totalRows: number;
  passedRows: number;
  failedRows: number;
  totalSteps: number;
  passedSteps: number;
  failedSteps: number;
  healedByAiSteps: number;
  totalDurationMs: number;
  startedAt: string;
  completedAt?: string;
  errorMessage?: string;
  rowResults: DataDrivenRowResult[];
}

// ── Dashboard / global aggregation types ──────────────────────────────────

export interface ActivityItem {
  kind: 'TEST_CREATED' | 'STANDARD_RUN' | 'DATA_DRIVEN_RUN';
  testId: number | null;
  testName: string;
  status: string | null;
  timestamp: string;
  description: string;
}

export interface DashboardSummary {
  totalTests: number;
  recordedTests: number;
  draftTests: number;
  totalRuns: number;
  passedRuns: number;
  failedRuns: number;
  runningRuns: number;
  successRatePercent: number;
  recentActivity: ActivityItem[];
}

export interface StandardRunSummary {
  id: number;
  testId: number | null;
  testName: string;
  status: 'RUNNING' | 'PASSED' | 'FAILED';
  startedAt: string;
  completedAt?: string;
  totalDurationMs: number;
  totalSteps: number;
  passedSteps: number;
  failedSteps: number;
}

export interface DataDrivenRunSummary {
  id: number;
  testId: number | null;
  testName: string;
  status: 'RUNNING' | 'PASSED' | 'FAILED';
  datasetFilename: string;
  startedAt: string;
  completedAt?: string;
  totalDurationMs: number;
  totalRows: number;
  passedRows: number;
  failedRows: number;
}

export interface AccessibilityRunSummary {
  id: number;
  scanId: number | null;
  scanName: string;
  targetUrl: string;
  status: 'RUNNING' | 'COMPLETED' | 'FAILED';
  startedAt: string;
  completedAt?: string;
  durationMs: number;
  totalViolations: number;
  criticalCount: number;
  seriousCount: number;
  needsReviewCount: number;
  passedCount: number;
}

export interface ApiRunSummary {
  id: number;
  collectionId: number | null;
  runName: string;
  status: string;
  startedAt: string;
  completedAt?: string;
  totalDurationMs: number;
  totalRequests: number;
  passedRequests: number;
  failedRequests: number;
}

export interface DashboardRuns {
  standardRuns: StandardRunSummary[];
  dataDrivenRuns: DataDrivenRunSummary[];
  accessibilityRuns: AccessibilityRunSummary[];
  apiRuns: ApiRunSummary[];
}

// ── Accessibility Testing types ───────────────────────────────────────────

export type AccessibilityScanScope = 'FULL_PAGE' | 'SELECTOR';
export type AccessibilityStandard = 'WCAG_A' | 'WCAG_AA' | 'BEST_PRACTICES';
export type AccessibilityRunStatus = 'RUNNING' | 'COMPLETED' | 'FAILED';
export type AccessibilityImpact = 'critical' | 'serious' | 'moderate' | 'minor' | null;

export interface AccessibilityScan {
  id: number;
  name: string;
  description?: string;
  targetUrl: string;
  scanScope: AccessibilityScanScope;
  selector?: string;
  standardsJson: string;
  createdAt: string;
}

export interface AccessibilityRuleNode {
  html: string;
  target: string;
  failureSummary: string;
}

export interface AccessibilityRuleFinding {
  ruleId: string;
  description: string;
  help: string;
  helpUrl: string;
  impact: AccessibilityImpact;
  tags: string[];
  nodes: AccessibilityRuleNode[];
}

export interface AccessibilityPassSummary {
  ruleId: string;
  description: string;
  impact: AccessibilityImpact;
  tags: string[];
  nodeCount: number;
}

export interface AccessibilityScanRun {
  id: number;
  scan: {
    id: number;
    name: string;
    targetUrl: string;
    scanScope: AccessibilityScanScope;
    selector?: string;
    standardsJson: string;
  };
  status: AccessibilityRunStatus;
  targetUrl: string;
  startedAt: string;
  completedAt?: string;
  durationMs: number;
  errorMessage?: string;
  totalViolations: number;
  criticalCount: number;
  seriousCount: number;
  moderateCount: number;
  minorCount: number;
  needsReviewCount: number;
  passedCount: number;
  violationsJson: string;
  incompleteJson: string;
  passesSummaryJson: string;
  manualChecksJson?: string;
}

// ── Manual / guided testing checklist ───────────────────────────────────────

export type ManualCheckStatus = 'NOT_CHECKED' | 'PASS' | 'FAIL' | 'NOT_APPLICABLE';

export interface ManualCheckEntry {
  status: ManualCheckStatus;
  notes: string;
}

export type ManualChecksMap = Record<string, ManualCheckEntry>;

export interface AccessibilityCreateScanRequest {
  name: string;
  description?: string;
  targetUrl: string;
  scanScope: AccessibilityScanScope;
  selector?: string;
  standards: AccessibilityStandard[];
}

export interface AccessibilityLatestScanSummary {
  runId: number;
  scanId: number;
  scanName: string;
  targetUrl: string;
  status: AccessibilityRunStatus;
  startedAt: string;
}

export interface AccessibilityDashboardSummary {
  totalScans: number;
  totalRuns: number;
  latestScan: AccessibilityLatestScanSummary | null;
  totalViolations: number;
  criticalCount: number;
  seriousCount: number;
  moderateCount: number;
  minorCount: number;
  needsReviewCount: number;
  passedCount: number;
}

export interface AccessibilityScanTrendPoint {
  runId: number;
  startedAt: string;
  status: AccessibilityRunStatus;
  durationMs: number;
  totalViolations: number;
  criticalCount: number;
  seriousCount: number;
  moderateCount: number;
  minorCount: number;
  needsReviewCount: number;
  passedCount: number;
  criticalDelta: number | null;
  seriousDelta: number | null;
  totalViolationsDelta: number | null;
  regression: boolean;
  improvement: boolean;
}

export interface AccessibilityScanTrend {
  scanId: number;
  scanName: string;
  targetUrl: string;
  points: AccessibilityScanTrendPoint[];
}

// ── Email Execution Report ────────────────────────────────────────────────

export type EmailReportType = 'STANDARD' | 'DATA_DRIVEN' | 'ACCESSIBILITY' | 'API_TESTING';

export interface EmailReportRequest {
  reportType: EmailReportType;
  runId: number;
  recipients: string[];
}

export interface EmailReportResponse {
  message: string;
  recipientCount: number;
}

// ── API Testing types ────────────────────────────────────────────────────

export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE' | 'HEAD' | 'OPTIONS';
export type ApiBodyType = 'NONE' | 'JSON' | 'RAW' | 'FORM_URLENCODED' | 'MULTIPART';
export type ApiAuthType = 'INHERIT' | 'NONE' | 'BEARER' | 'BASIC' | 'API_KEY' | 'CUSTOM_HEADER';
export type ApiRunStatus = 'RUNNING' | 'PASSED' | 'FAILED';
export type ApiRequestResultStatus = 'PASSED' | 'FAILED' | 'NETWORK_ERROR' | 'TIMEOUT' | 'SKIPPED';
export type ApiRunScope = 'COLLECTION' | 'FOLDER' | 'REQUEST';

export interface KeyValueItem {
  key: string;
  value: string;
  enabled: boolean;
  description?: string;
}

export interface ApiAuthConfig {
  type: ApiAuthType;
  token?: string;
  username?: string;
  password?: string;
  apiKeyName?: string;
  apiKeyValue?: string;
  apiKeyAddTo?: 'HEADER' | 'QUERY';
  customHeaderName?: string;
  customHeaderValue?: string;
}

export type AssertionType =
  | 'STATUS_CODE_EQUALS' | 'STATUS_CODE_NOT_EQUALS' | 'STATUS_CODE_ONE_OF'
  | 'RESPONSE_TIME_LESS_THAN' | 'RESPONSE_TIME_GREATER_THAN'
  | 'HEADER_EXISTS' | 'HEADER_EQUALS' | 'HEADER_CONTAINS'
  | 'BODY_CONTAINS' | 'BODY_EQUALS'
  | 'JSON_PATH_EXISTS' | 'JSON_PATH_EQUALS' | 'JSON_PATH_NOT_EQUALS' | 'JSON_PATH_CONTAINS';

export interface AssertionDefinition {
  type: AssertionType;
  target?: string;
  expected?: string;
}

export interface AssertionResult {
  description: string;
  passed: boolean;
  expected?: string;
  actual?: string;
}

export type ExtractionSource = 'JSON_PATH' | 'HEADER' | 'STATUS_CODE';
export type ExtractionSaveTo = 'RUNTIME' | 'ENVIRONMENT';

export interface ExtractionRule {
  source: ExtractionSource;
  path: string;
  variableName: string;
  saveTo: ExtractionSaveTo;
}

export interface PreRequestVariable {
  name: string;
  value: string;
}

export interface ApiRequestSpec {
  name: string;
  method: HttpMethod;
  url: string;
  params: KeyValueItem[];
  headers: KeyValueItem[];
  bodyType: ApiBodyType;
  bodyContent: string;
  formFields: KeyValueItem[];
  authType: ApiAuthType;
  auth: ApiAuthConfig;
  assertions: AssertionDefinition[];
  preRequestVars: PreRequestVariable[];
  extractions: ExtractionRule[];
}

export function emptyRequestSpec(name = 'New Request'): ApiRequestSpec {
  return {
    name,
    method: 'GET',
    url: '',
    params: [],
    headers: [],
    bodyType: 'NONE',
    bodyContent: '',
    formFields: [],
    authType: 'INHERIT',
    auth: { type: 'NONE' },
    assertions: [],
    preRequestVars: [],
    extractions: [],
  };
}

export interface ApiEnvironmentVariable {
  key: string;
  value: string;
  secret: boolean;
}

export interface ApiEnvironment {
  id: number;
  name: string;
  variablesJson: string;
  createdAt: string;
}

export interface ApiCollection {
  id: number;
  name: string;
  description?: string;
  authConfigJson: string;
  variablesJson: string;
  createdAt: string;
}

export interface ApiFolder {
  id: number;
  collection: { id: number; name: string };
  name: string;
  sortOrder: number;
  createdAt: string;
}

export interface ApiRequestEntity {
  id: number;
  collection: { id: number; name: string };
  folder?: { id: number; name: string } | null;
  name: string;
  method: HttpMethod;
  url: string;
  bodyType: ApiBodyType;
  authType: ApiAuthType;
  sortOrder: number;
  createdAt: string;
  updatedAt: string;
}

export interface ApiRequestRunResult {
  id: number;
  requestOrder: number;
  iterationIndex: number;
  requestId: number | null;
  requestName: string;
  method: string;
  resolvedUrl: string;
  status: ApiRequestResultStatus;
  httpStatus: number;
  httpStatusText: string;
  durationMs: number;
  responseSizeBytes: number;
  responseTruncated: boolean;
  responseHeadersJson: string;
  responseBody: string;
  assertionResultsJson: string;
  errorMessage?: string;
}

export interface ApiRun {
  id: number;
  collection: ApiCollection | null;
  environment: ApiEnvironment | null;
  runName: string;
  scope: ApiRunScope;
  folderId: number | null;
  requestId: number | null;
  status: ApiRunStatus;
  iterationCount: number;
  stopOnFailure: boolean;
  delayMs: number;
  datasetFilename: string | null;
  totalRequests: number;
  passedRequests: number;
  failedRequests: number;
  totalDurationMs: number;
  errorMessage?: string;
  startedAt: string;
  completedAt?: string;
  requestResults: ApiRequestRunResult[];
}

export interface ExecuteRequestPayload {
  requestId?: number;
  requestName?: string;
  collectionId?: number;
  environmentId?: number;
  request: ApiRequestSpec;
}

export interface StartRunConfig {
  environmentId?: number;
  scope: ApiRunScope;
  folderId?: number;
  requestId?: number;
  iterationCount: number;
  stopOnFailure: boolean;
  delayMs: number;
}
