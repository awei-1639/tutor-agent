/**
 * REST + SSE 客户端: dev 用 /api 代理到 8180, 生产直连。
 * 认证使用服务端签发的 HttpOnly cookie；localStorage 只保留 UI 所需的非敏感用户信息。
 */
const BASE = '/api';

export function getToken(): string | null {
  return null;
}

export function setSessionHint(userId: number, name: string, role = 'USER') {
  // Authentication is held exclusively in HttpOnly cookies.
  saveSessionHint(userId, name, role);
}

function saveSessionHint(userId: number, name: string, role = 'USER') {
  localStorage.setItem('tutor_user_id', String(userId));
  localStorage.setItem('tutor_user_name', name);
  localStorage.setItem('tutor_user_role', role);
}

export function clearToken() {
  localStorage.removeItem('tutor_token');
  localStorage.removeItem('tutor_user_id');
  localStorage.removeItem('tutor_user_name');
  localStorage.removeItem('tutor_user_role');
}

export function getUserId(): number {
  const v = localStorage.getItem('tutor_user_id');
  return v ? Number(v) : 0;
}

export function hasSessionHint(): boolean {
  return localStorage.getItem('tutor_user_id') !== null;
}

export function getUserRole(): string {
  return localStorage.getItem('tutor_user_role') ?? 'USER';
}

function csrfToken(): string | null {
  const item = document.cookie.split('; ').find(value => value.startsWith('tutor_csrf='));
  return item ? decodeURIComponent(item.substring('tutor_csrf='.length)) : null;
}

export class ApiError extends Error {
  constructor(
    public readonly path: string,
    public readonly status: number,
    public readonly detail?: string,
  ) {
    super(`${path} HTTP ${status}${detail ? `: ${detail}` : ''}`);
    this.name = 'ApiError';
  }
}

function backendDetail(text: string): string | undefined {
  if (!text) return undefined;
  try {
    const body = JSON.parse(text) as { message?: string; error?: string; detail?: string };
    return body.message ?? body.detail ?? (body.error && body.error !== 'Unauthorized' && body.error !== 'Bad Request' ? body.error : undefined);
  } catch {
    return undefined;
  }
}

/** 将底层接口错误转换为用户提示；ApiError 仍保留原始状态供开发排查。 */
export function toUserMessage(error: unknown, fallback = '操作失败，请稍后重试。'): string {
  if (error instanceof ApiError) {
    if (error.status === 400) {
      if (error.path.includes('/admin/documents')) return error.detail ?? '仅支持 PDF、DOCX、TXT、Markdown，且文件不能超过大小限制。';
      if (error.path.includes('/resumes')) return error.detail ?? '仅支持 PDF、DOCX、TXT、Markdown，且简历文本不能过短。';
      if (error.path.includes('/auth/register')) return '注册信息不完整或格式不正确，请检查后重试。';
      return '提交的信息不符合要求，请检查后重试。';
    }
    if (error.status === 401) {
      if (error.path.includes('/auth/login')) return '邮箱或密码不正确，请重试。';
      if (error.path.includes('/auth/register')) return '注册请求未通过验证，请刷新页面后重试。';
      if (error.path.includes('/auth/dev-login')) return '开发登录暂未启用，请使用邮箱注册或登录。';
      return '登录状态已失效，请重新登录。';
    }
    if (error.status === 403 && error.path.startsWith('/admin')) return '当前账号没有管理员权限。';
    if (error.status === 403) return '页面验证已过期，请刷新页面后重试。';
    if (error.status === 404) return '请求的内容不存在或已下线。';
    if (error.status === 409) {
      if (error.path.includes('/admin/interview-evals/annotations/replay')) return error.detail ?? fallback;
      return '该邮箱已注册，请直接登录。';
    }
    if (error.status === 413) return '文件超过大小限制，请选择 5MB 以内的文件。';
    if (error.status === 429) return '当前请求较多，请稍后再试。';
    if (error.status === 502 && error.path.includes('/admin/documents')) return 'OSS 暂时无法写入，请检查 Bucket、地域和 RAM 权限。';
    if (error.status >= 500) return '服务暂时不可用，请稍后重试。';
    return fallback;
  }
  if (error instanceof TypeError && /fetch|network/i.test(error.message)) return '网络连接失败，请检查网络后重试。';
  return fallback;
}

// 同一时刻只允许一个刷新请求，避免画像页多个并发 GET 在会话过期时争抢刷新令牌。
let refreshPromise: Promise<boolean> | null = null;

function refreshSession(): Promise<boolean> {
  if (!refreshPromise) {
    refreshPromise = fetch(BASE + '/auth/refresh', {
      method: 'POST', credentials: 'same-origin', headers: { 'X-CSRF-Token': csrfToken() ?? '' }
    }).then(async res => {
      if (!res.ok) return false;
      const body = await res.json().catch(() => null) as { user_id?: number; name?: string; role?: string } | null;
      if (body?.user_id) saveSessionHint(body.user_id, body.name ?? '', body.role ?? 'USER');
      return true;
    }).catch(() => false).finally(() => { refreshPromise = null; });
  }
  return refreshPromise;
}

async function request<T>(path: string, opts: RequestInit = {}): Promise<T> {
  const headers = new Headers(opts.headers);
  if (!headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
  const csrf = csrfToken();
  if (csrf) headers.set('X-CSRF-Token', csrf);
  let res = await fetch(BASE + path, { ...opts, headers, credentials: 'same-origin' });
  if (res.status === 401 && !path.startsWith('/auth/')) {
      if (await refreshSession()) {
      headers.set('X-CSRF-Token', csrfToken() ?? '');
      res = await fetch(BASE + path, { ...opts, headers, credentials: 'same-origin' });
    }
  }
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    const error = new ApiError(path, res.status, backendDetail(text));
    console.error('[api]', error.message);
    throw error;
  }
  if (res.status === 204) return undefined as T;
  return res.json() as Promise<T>;
}

export const api = {
  // Auth
  login: (email: string, password: string) =>
    request<{ user_id: number; name: string; role: string }>('/auth/login', { method: 'POST', body: JSON.stringify({ email, password }) }),
  register: (email: string, password: string, name?: string) =>
    request<{ user_id: number; name: string; role: string }>('/auth/register', { method: 'POST', body: JSON.stringify({ email, password, name }) }),
  devLogin: (name: string) =>
    request<{ user_id: number; name: string; role: string }>('/auth/dev-login', { method: 'POST', body: JSON.stringify({ name }) }),
  logout: () => request<void>('/auth/logout', { method: 'POST' }),
  // Conversations
  listConversations: () => request<ConversationSummary[]>('/conversations'),
  getMessages: (id: number) => request<{ id: number; role: string; content: string; citations?: string; citationStatus?: string; citationIssues?: string; traceId?: string; feedback?: string }[]>(`/conversations/${id}/messages`),
  submitMessageFeedback: (messageId: number, rating: 'helpful' | 'not_helpful', reason?: string) =>
    request<{ id: number; messageId: number; rating: string; traceId?: string }>('/feedback', {
      method: 'POST', body: JSON.stringify({ messageId, rating, reason }),
    }),
  // Profile
  getProfile: () => request<ProfileData>('/profile'),
  getProfileEvents: (limit = 12) => request<ProfileEvent[]>(`/profile/events?limit=${limit}`),
  getCareerGaps: () => request<CareerGapCard[]>('/career/gaps'),
  addGapTasks: (jobId: number, skillIds: string[]) => request<PlanTask[]>('/career/gaps/tasks', {
    method: 'POST', body: JSON.stringify({ jobId, skillIds }),
  }),
  confirmProfile: (field: string, accept: boolean) =>
    request('/profile/confirm', { method: 'POST', body: JSON.stringify({ field, accept }) }),
  // Resume
  uploadResume: async (file: File) => {
    const form = new FormData();
    form.append('file', file);
    const res = await fetch(BASE + '/resumes', {
      method: 'POST',
      body: form,
      credentials: 'same-origin',
      headers: { 'X-CSRF-Token': csrfToken() ?? '' },
    });
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      const error = new ApiError('/resumes', res.status, backendDetail(text));
      console.error('[api]', error.message);
      throw error;
    }
    return res.json() as Promise<ResumeUploadResponse>;
  },
  // Notifications
  listNotifications: () => request<Notification[]>('/notifications'),
  markRead: (id: number) => request(`/notifications/read`, { method: 'POST', body: JSON.stringify({ ids: [id] }) }),
  // Plans
  generatePlan: (body: { goal: string; currentSkills?: string; checkinHistory?: string }) =>
    request<PlanGenerationJob>('/plans', { method: 'POST', body: JSON.stringify(body) }),
  getPlanGenerationJob: (id: number) => request<PlanGenerationJob>(`/plans/jobs/${id}`),
  todayTasks: () => request<PlanTask[]>('/plans/today'),
  checkin: (taskId: number, status: string, feedback?: string) =>
    request('/plans/checkin', { method: 'POST', body: JSON.stringify({ taskId, status, feedback }) }),
  shouldReplan: () => request<{ should_replan: boolean }>('/plans/should-replan'),
  // Interview
  openInterview: (config: InterviewConfig) =>
    request<InterviewMessage>('/interview/open', { method: 'POST', body: JSON.stringify(config) }),
  answerInterview: (sessionId: string, answer: string, requestId: string) =>
    request<InterviewTurnJob>(`/interview/${encodeURIComponent(sessionId)}/answer`, {
      method: 'POST', body: JSON.stringify({ answer, requestId }),
    }),
  retryInterviewTurn: (sessionId: string, turnId: string) =>
    request<InterviewTurnJob>(`/interview/${encodeURIComponent(sessionId)}/turns/${encodeURIComponent(turnId)}/retry`, { method: 'POST' }),
  getInterviewTurn: (sessionId: string, turnId: string) =>
    request<InterviewTurnJob>(`/interview/${encodeURIComponent(sessionId)}/turns/${encodeURIComponent(turnId)}`),
  cancelInterview: (sessionId: string) => request<InterviewMessage>(`/interview/${encodeURIComponent(sessionId)}/cancel`, { method: 'POST' }),
  getInterviewSession: (sessionId: string) => request<InterviewSessionView>(`/interview/${encodeURIComponent(sessionId)}`),
  retestInterview: (sessionId: string) => request<InterviewMessage>(`/interview/${encodeURIComponent(sessionId)}/retest`, { method: 'POST' }),
  getInterviewHistory: (limit = 10) => request<InterviewHistoryItem[]>(`/interview/history?limit=${limit}`),
  getReport: (sessionId: string) => request<InterviewReport>(`/interview/${encodeURIComponent(sessionId)}/report`),
  getInterviewCompletion: (sessionId: string) => request<InterviewCompletionStatus>(`/interview/${encodeURIComponent(sessionId)}/completion`),
  submitInterviewFeedback: (sessionId: string, rating: 'accurate' | 'inaccurate', reason = '') =>
    request<void>(`/interview/${encodeURIComponent(sessionId)}/feedback`, { method: 'POST', body: JSON.stringify({ rating, reason }) }),
  // RAG evaluation (development/internal workspace)
  listEvalRuns: () => request<EvalRunSummary[]>('/internal/evals'),
  getEvalRun: (id: number) => request<EvalRunDetail>(`/internal/evals/${id}`),
  startEval: (body: { topK?: number; limit?: number; modes?: string[] } = {}) =>
    request<{ id: number; status: string; datasetVersion: string; topK: number; totalCases: number; modes: string[] }>('/internal/evals', {
      method: 'POST', body: JSON.stringify(body),
    }),
  // Admin console
  adminOverview: () => request<AdminOverview>('/admin/overview'),
  adminUsers: (params: { search?: string; status?: string; page?: number; size?: number } = {}) => {
    const query = new URLSearchParams();
    if (params.search) query.set('search', params.search);
    if (params.status) query.set('status', params.status);
    if (params.page !== undefined) query.set('page', String(params.page));
    if (params.size !== undefined) query.set('size', String(params.size));
    return request<AdminUserPage>(`/admin/users?${query.toString()}`);
  },
  adminUserAction: (id: number, action: 'disable' | 'restore' | 'soft-delete') =>
    request<void>(`/admin/users/${id}/${action}`, { method: 'POST' }),
  adminAudit: (limit = 50) => request<AdminAudit[]>(`/admin/audit?limit=${limit}`),
  adminDocuments: (limit = 50) => request<AdminDocument[]>(`/admin/documents?limit=${limit}`),
  adminUploadDocument: async (file: File, title?: string) => {
    const form = new FormData();
    form.append('file', file);
    if (title?.trim()) form.append('title', title.trim());
    const res = await fetch(BASE + '/admin/documents', {
      method: 'POST', body: form, credentials: 'same-origin', headers: { 'X-CSRF-Token': csrfToken() ?? '' },
    });
    if (!res.ok) {
      const text = await res.text().catch(() => '');
      throw new ApiError('/admin/documents', res.status, backendDetail(text));
    }
    return res.json() as Promise<{ id: string; status: string; deduplicated: boolean }>;
  },
  adminDocumentAction: (id: string, action: 'retry' | 'soft-delete') =>
    request<void>(`/admin/documents/${id}/${action}`, { method: 'POST' }),
  adminInterviewAnnotationQueue: (limit = 20, minReviewers = 2, blind = true, maxPerSession = 1) =>
    request<InterviewAnnotationQueueItem[]>(`/admin/interview-evals/annotations/queue?limit=${limit}&minReviewers=${minReviewers}&blind=${blind}&maxPerSession=${maxPerSession}`),
  adminUpsertInterviewAnnotation: (questionId: number, humanScore: number, rationale: string) =>
    request<{ questionId: number; humanScore: number }>(`/admin/interview-evals/annotations/${questionId}`, {
      method: 'POST', body: JSON.stringify({ humanScore, rationale }),
    }),
  adminReplayInterviewAnnotations: (datasetVersion = 'human-gold-current', minReviewers = 2) =>
    request<InterviewScoreReplayResult>('/admin/interview-evals/annotations/replay', {
      method: 'POST', body: JSON.stringify({ datasetVersion, minReviewers }),
    }),
};

export interface ConversationSummary {
  id: number;
  last_active_at: string | null;
  title: string | null;
  msg_count: number;
}

export type ProfileValue = string | number | boolean | null | ProfileValue[] | ProfileObject;

export interface ProfileObject {
  value?: ProfileValue;
  confirmed?: boolean;
  field?: string;
  [key: string]: ProfileValue | undefined;
}

export interface ProfileSkill {
  name: string;
  confidence: number;
  source: string;
  last_seen?: string;
}

export interface ProfileData {
  skills?: ProfileSkill[];
  open_items?: string[];
  topics?: string[];
  pending_confirm?: Array<{ field: string; value?: string }>;
  [key: string]: ProfileValue | ProfileSkill[] | string[] | Array<{ field: string; value?: string }> | undefined;
}

export interface Notification {
  id: number;
  type: string;
  payload: unknown;
  read: boolean;
  created_at: string;
}

export interface InterviewMessage {
  sessionId: string;
  status: 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED' | 'FAILED';
  message: string;
}

export interface InterviewConfig {
  targetRole?: string;
  jobDescription?: string;
  interviewType?: 'technical' | 'project' | 'behavioral' | 'system_design';
  difficulty?: 'JUNIOR' | 'MID' | 'SENIOR';
  durationMinutes?: number;
}

export interface InterviewSessionView {
  sessionId: string;
  status: 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED' | 'FAILED';
  targetRole: string;
  topic: string;
  mainQuestionsAsked: number;
  deadlineAt: string;
  transcript: Array<{ speaker: 'ai' | 'me'; content: string }>;
}

export interface InterviewHistoryItem {
  sessionId: string;
  targetRole: string;
  interviewType: string;
  difficulty: string;
  status: 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED' | 'FAILED';
  totalQuestions: number;
  avgScore: number;
  createdAt: string;
  completedAt?: string | null;
  retestOf?: string | null;
  scoreDelta?: number | null;
}

export interface InterviewRetestComparison {
  sourceSessionId: string;
  baselineAvgScore: number;
  scoreDelta: number;
  originalFocusAreas?: string[];
}

export interface InterviewReport {
  totalQuestions: number;
  avgScore?: number;
  scoreConfidence?: number;
  strengths?: string[];
  improvements?: string[];
  resources?: string[];
  retestComparison?: InterviewRetestComparison | null;
}

export interface InterviewCompletionStatus {
  sessionId: string;
  status: 'queued' | 'running' | 'completed' | 'failed';
  attempts: number;
  lastError?: string | null;
  createdAt: string;
  startedAt?: string | null;
  finishedAt?: string | null;
  evidenceStatus?: 'queued' | 'completed' | string;
  learningPlanStatus?: 'queued' | 'completed' | 'failed' | string;
}

export interface InterviewTurnJob {
  id: string;
  sessionId: string;
  requestId: string;
  status: 'PENDING' | 'PROCESSING' | 'COMPLETED' | 'RETRYABLE_FAILED' | 'FAILED';
  attempts: number;
  responseStatus?: 'IN_PROGRESS' | 'COMPLETED' | 'CANCELLED' | string | null;
  responseMessage?: string | null;
  lastError?: string | null;
  createdAt: string;
  finishedAt?: string | null;
}

export interface ResumeStructured {
  skills?: string[];
  education?: Array<Record<string, unknown>>;
  projects?: Array<Record<string, unknown>>;
  experiences?: Array<Record<string, unknown>>;
  summary?: string;
}

export interface ResumeUploadResponse {
  resume_id: number;
  masked_pii_count: number;
  structured: ResumeStructured;
}

export interface ProfileEvent {
  id: number;
  changes: string[];
  trigger: 'conversation' | 'resume' | 'confirm' | string;
  createdAt: string;
  traceId?: string;
}

export interface CareerGapCard {
  jobId: number;
  title: string;
  company?: string;
  city?: string;
  coverage: number;
  matched: string[];
  speedup: string[];
  missing: string[];
}

export interface PlanTask {
  id: number; planId: number; day: string; content: string; kind: string; minutes: number; estimatedMinutes?: number; evidenceHint?: string;
}

export interface PlanGenerationJob {
  id: number;
  status: 'queued' | 'running' | 'completed' | 'failed' | string;
  planId?: number | null;
  error?: string | null;
  createdAt?: string;
  finishedAt?: string | null;
}

export interface EvalRunSummary {
  id: number;
  status: 'running' | 'completed' | 'failed' | string;
  mode: string;
  datasetVersion?: string;
  topK?: number;
  totalCases?: number;
  error?: string;
  startedAt?: string;
  finishedAt?: string;
  createdAt?: string;
}

export interface AdminEvalRun {
  id: number;
  status: string;
  datasetVersion?: string;
  topK?: number;
  totalCases?: number;
  startedAt?: string;
  finishedAt?: string;
  createdAt?: string;
}

export interface AdminOverview {
  operatorId: number;
  users: { total: number; active: number; disabled: number; deleted: number; admins: number };
  recentEvalRuns: AdminEvalRun[];
  interviewQuality: {
    finalizedSessions: number; totalFeedback: number; inaccurateFeedback: number; inaccurateRate: number; avgConfidence: number;
    recentCalibration: Array<{ rating: string; reason: string; createdAt?: string }>;
  };
  checks: { database: string; evaluation: string };
}

export interface AdminUser {
  id: number;
  email?: string;
  name?: string;
  role: string;
  status: 'active' | 'disabled' | 'deleted' | string;
  createdAt?: string;
  disabledAt?: string;
  deletedAt?: string;
}

export interface AdminUserPage {
  items: AdminUser[];
  page: number;
  size: number;
  total: number;
}

export interface AdminAudit {
  id: number;
  action: string;
  adminUserId?: number;
  adminName?: string;
  targetUserId?: number;
  targetName?: string;
  metadata?: string;
  createdAt?: string;
}

export interface AdminDocument {
  id: string;
  title: string;
  filename: string;
  contentType?: string;
  sizeBytes: number;
  status: 'uploaded' | 'processing' | 'indexed' | 'failed' | 'deleted' | string;
  error?: string | null;
  chunkCount: number;
  creatorName?: string;
  createdAt?: string;
  updatedAt?: string;
  deletedAt?: string | null;
}

export interface InterviewAnnotationQueueItem {
  questionId: number;
  prompt: string;
  answer: string;
  modelScore?: number | null;
  modelConfidence?: number | null;
  reviewerCount: number;
  answeredAt?: string;
  feedbackRating?: 'accurate' | 'inaccurate' | string | null;
  feedbackReason?: string | null;
  priority?: number | null;
}

export interface InterviewScoreReplayResult {
  kind: string;
  datasetVersion: string;
  runId?: number;
  metrics: {
    n: number;
    mae: number;
    gradeAgreement: number;
    falseHighRate: number;
    doubleLabelCoverage: number;
    reviewerDisagreementRate: number;
    highConfidenceErrorRate: number;
    releaseEligible: boolean;
    rules: Array<{ code: string; label: string; actual: number; threshold: number; comparator: string; passed: boolean }>;
  };
  note?: string;
}

export interface EvalRunDetail extends EvalRunSummary {
  modelConfig?: Record<string, unknown>;
  metrics?: {
    datasetVersion?: string;
    topK?: number;
    totalCases?: number;
    modes?: Record<string, EvalModeResult>;
  };
}

export interface EvalModeResult {
  mode: string;
  overall: EvalAggregate;
  byType: Record<string, EvalAggregate>;
  qualityGate?: EvalQualityGate;
  badcaseClusters?: EvalBadcaseCluster[];
  cases: EvalCase[];
}

export interface EvalAggregate {
  n: number;
  passed: number;
  hitAtK: number;
  hitAtKCi95?: { lower: number; upper: number };
  recallAtK: number;
  mrr: number;
  p50Ms: number;
  p95Ms: number;
  errors: number;
}

export interface EvalCase {
  id: string;
  query: string;
  type: string;
  gold: string[];
  retrieved: string[];
  hits: string[];
  recall: number;
  rr: number;
  hit: boolean;
  latencyMs: number;
  error?: string | null;
  diagnosis?: EvalDiagnosis;
}

export interface EvalQualityGate {
  status: 'passed' | 'needs_review' | 'blocked' | 'sample_only' | string;
  releaseEligible: boolean;
  fullGoldenSet: boolean;
  evaluatedCases: number;
  datasetCases: number;
  rules: Array<{
    code: string;
    level: 'P0' | 'P1' | string;
    label: string;
    actual?: number | null;
    threshold?: number;
    comparator: 'min' | 'max' | string;
    applicable: boolean;
    passed?: boolean | null;
  }>;
}

export interface EvalBadcaseCluster {
  code: string;
  label: string;
  severity: string;
  owner: string;
  suggestion: string;
  count: number;
  sampleCaseIds: string[];
}

export interface EvalDiagnosis {
  code: string;
  label: string;
  severity: string;
  owner: string;
  suggestion: string;
}

/**
 * SSE 流式 chat: 回调按事件类型分发
 * events: meta/citation/stage/token/done/error/clarify
 * isActive: 可选回调, 每读一行检查; 返回 false 立即中断 (应对重复流竞争)
 * token 事件带 seq；同一连接内按序缓冲，并忽略已处理序号，避免重放或并发推送造成错序、重复拼接。
 */
export function streamChat(
  body: { conversationId?: number | null; message: string },
  handlers: {
    onMeta?: (e: { conversation_id: number; trace_id: string }) => void;
    onStage?: (e: { phase: string; expert?: string; status?: string; detail?: string }) => void;
    onCitation?: (e: { sid: string; node_id: string; type: string; title: string; text: string; graph_path?: string; source_url?: string; source_status?: string; evidence_hash?: string }) => void;
    onToken?: (text: string, seq?: number) => void;
    onClarify?: (question: string) => void;
    onDone?: (e: { message_id: number; trace_id?: string; citation_status?: string; citation_issues?: string[] }) => void;
    onError?: (msg: string) => void;
    isActive?: () => boolean;
  }
) {
  const ctrl = new AbortController();
  fetch(BASE + '/chat', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      'X-CSRF-Token': csrfToken() ?? '',
    },
    credentials: 'same-origin',
    body: JSON.stringify(body),
    signal: ctrl.signal,
  }).then(async (res) => {
    if (!res.ok || !res.body) {
      handlers.onError?.(toUserMessage(new ApiError('/chat', res.status), '对话暂时无法完成，请稍后重试。'));
      return;
    }
    const reader = res.body.getReader();
    const dec = new TextDecoder();
    let buf = '';
    let nextTokenSequence = 0;
    const pendingTokens = new Map<number, string>();
    while (true) {
      // 读之前先检查是否仍活跃 (StrictMode 双流竞争 / 新 send 中断旧流)
      if (handlers.isActive && !handlers.isActive()) {
        try { await reader.cancel(); } catch { /* best-effort cancellation */ }
        return;
      }
      const { value, done } = await reader.read();
      if (done) break;
      buf += dec.decode(value, { stream: true });
      const lines = buf.split('\n');
      buf = lines.pop() ?? '';
      for (const line of lines) {
        const m = /^data:\s*(.+)$/.exec(line.trim());
        if (!m) continue;
        try {
          const evt = JSON.parse(m[1]);
          if ('conversation_id' in evt) handlers.onMeta?.(evt);
          else if ('phase' in evt) handlers.onStage?.(evt);
          else if ('sid' in evt) handlers.onCitation?.(evt);
          else if (typeof evt.text === 'string') {
            if (typeof evt.seq === 'number') {
              if (evt.seq < nextTokenSequence) continue;
              pendingTokens.set(evt.seq, evt.text);
              while (pendingTokens.has(nextTokenSequence)) {
                const text = pendingTokens.get(nextTokenSequence)!;
                pendingTokens.delete(nextTokenSequence);
                handlers.onToken?.(text, nextTokenSequence);
                nextTokenSequence += 1;
              }
            } else handlers.onToken?.(evt.text);
          }
          else if ('question' in evt) handlers.onClarify?.(evt);
          else if ('message_id' in evt) handlers.onDone?.(evt);
          else if ('message' in evt) handlers.onError?.(evt.message);
        } catch {
          // 忽略非 JSON 行
        }
      }
    }
  }).catch(err => handlers.onError?.(toUserMessage(err, '网络连接异常，请稍后重试。')));
  return ctrl;
}
