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
  stepResults: TestRunStep[];
}

export interface CreateTestRequest {
  name: string;
  targetUrl: string;
}
