/**
 * REST + SSE 客户端: dev 用 /api 代理到 8180, 生产直连。
 * token 存 localStorage, 每次请求自动带 Authorization。
 */
const BASE = '/api';

export function getToken(): string | null {
  return localStorage.getItem('tutor_token');
}

export function setToken(token: string, userId: number, name: string) {
  localStorage.setItem('tutor_token', token);
  localStorage.setItem('tutor_user_id', String(userId));
  localStorage.setItem('tutor_user_name', name);
}

export function clearToken() {
  localStorage.removeItem('tutor_token');
  localStorage.removeItem('tutor_user_id');
  localStorage.removeItem('tutor_user_name');
}

export function getUserId(): number {
  const v = localStorage.getItem('tutor_user_id');
  return v ? Number(v) : 1;
}

async function request<T>(path: string, opts: RequestInit = {}): Promise<T> {
  const headers: Record<string, string> = { 'Content-Type': 'application/json', ...(opts.headers as any) };
  const token = getToken();
  if (token) headers['Authorization'] = `Bearer ${token}`;
  const res = await fetch(BASE + path, { ...opts, headers });
  if (!res.ok) {
    const text = await res.text().catch(() => '');
    throw new Error(`${path} HTTP ${res.status}: ${text.slice(0, 200)}`);
  }
  return res.json();
}

export const api = {
  // Auth
  login: (email: string, password: string) =>
    request<{ user_id: number; token: string; name: string }>('/auth/login', { method: 'POST', body: JSON.stringify({ email, password }) }),
  register: (email: string, password: string, name?: string) =>
    request<{ user_id: number; token: string; name: string }>('/auth/register', { method: 'POST', body: JSON.stringify({ email, password, name }) }),
  devLogin: (name: string) =>
    request<{ user_id: number; token: string; name: string }>('/auth/dev-login', { method: 'POST', body: JSON.stringify({ name }) }),
  // Conversations
  listConversations: () => request<any[]>('/conversations'),
  getMessages: (id: number) => request<{ role: string; content: string; citations?: string }[]>(`/conversations/${id}/messages`),
  // Profile
  getProfile: () => request<{ data: any; updated_at: string }>('/profile'),
  confirmProfile: (field: string, accept: boolean) =>
    request('/profile/confirm', { method: 'POST', body: JSON.stringify({ field, accept }) }),
  // Resume
  uploadResume: async (file: File) => {
    const form = new FormData();
    form.append('file', file);
    const res = await fetch(BASE + '/resumes', {
      method: 'POST',
      body: form,
      headers: { Authorization: `Bearer ${getToken() ?? ''}` },
    });
    if (!res.ok) throw new Error('upload failed');
    return res.json();
  },
  // Notifications
  listNotifications: () => request<any[]>('/notifications'),
  markRead: (id: number) => request(`/notifications/read`, { method: 'POST', body: JSON.stringify({ ids: [id] }) }),
  // Plans
  generatePlan: (body: { goal: string; currentSkills?: string; checkinHistory?: string }) =>
    request('/plans', { method: 'POST', body: JSON.stringify(body) }),
  todayTasks: () => request<any[]>('/plans/today'),
  checkin: (taskId: number, status: string, feedback?: string) =>
    request('/plans/checkin', { method: 'POST', body: JSON.stringify({ taskId, status, feedback }) }),
  shouldReplan: () => request<{ should_replan: boolean }>('/plans/should-replan'),
  // Interview
  openInterview: (sessionId: string, targetRole?: string) =>
    request('/interview/open', { method: 'POST', body: JSON.stringify({ sessionId, targetRole }) }),
  answerInterview: (sessionId: string, answer: string) =>
    request('/interview/answer', { method: 'POST', body: JSON.stringify({ sessionId, answer }) }),
  getReport: (sessionId: string) => request(`/interview/report/${sessionId}`),
};

/**
 * SSE 流式 chat: 回调按事件类型分发
 * events: meta/citation/stage/token/done/error/clarify
 * isActive: 可选回调, 每读一行检查; 返回 false 立即中断 (应对重复流竞争)
 */
export function streamChat(
  body: { conversationId?: number | null; message: string },
  handlers: {
    onMeta?: (e: { conversation_id: number; trace_id: string }) => void;
    onStage?: (e: { phase: string }) => void;
    onCitation?: (e: { sid: string; node_id: string; type: string; title: string; text: string }) => void;
    onToken?: (text: string) => void;
    onClarify?: (question: string) => void;
    onDone?: (e: { message_id: number }) => void;
    onError?: (msg: string) => void;
    isActive?: () => boolean;
  }
) {
  const ctrl = new AbortController();
  const token = getToken();
  fetch(BASE + '/chat', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    },
    body: JSON.stringify(body),
    signal: ctrl.signal,
  }).then(async (res) => {
    if (!res.ok || !res.body) {
      handlers.onError?.(`HTTP ${res.status}`);
      return;
    }
    const reader = res.body.getReader();
    const dec = new TextDecoder();
    let buf = '';
    while (true) {
      // 读之前先检查是否仍活跃 (StrictMode 双流竞争 / 新 send 中断旧流)
      if (handlers.isActive && !handlers.isActive()) {
        try { await reader.cancel(); } catch {}
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
          else if (typeof evt.text === 'string') handlers.onToken?.(evt.text);
          else if ('question' in evt) handlers.onClarify?.(evt);
          else if ('message_id' in evt) handlers.onDone?.(evt);
          else if ('message' in evt) handlers.onError?.(evt.message);
        } catch {
          // 忽略非 JSON 行
        }
      }
    }
  }).catch(err => handlers.onError?.(err.message ?? '网络异常'));
  return ctrl;
}