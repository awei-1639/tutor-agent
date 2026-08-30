import { useEffect, useRef, useState } from 'react';
import { api, streamChat, type ConversationSummary, type MemoryRef } from '../lib/api';
import { renderMarkdown } from '../lib/markdown';

interface Citation { sid: string; node_id: string; type: string; title: string; text: string; graph_path?: string; source_url?: string; source_status?: string; evidence_hash?: string; }
interface DisplayCitation extends Citation { key: string; }
interface Msg { id?: number; role: 'user' | 'assistant'; content: string; tokens?: string; citations?: Citation[]; memories?: MemoryRef[]; citationStatus?: string; citationIssues?: string[]; clarify?: string; clarifyOptions?: Array<{ id: string; label: string }>; trace_id?: string; locked?: boolean; feedback?: 'helpful' | 'not_helpful'; truncated?: boolean; }
type Conv = ConversationSummary;

function safeSourceUrl(value?: string): string | null {
  try {
    const url = new URL(value ?? '');
    return url.protocol === 'https:' ? url.toString() : null;
  } catch {
    return null;
  }
}

function sourceStatusLabel(status?: string): string {
  return ({
    managed: '平台固化 · 哈希一致',
    verified: '来源快照已验证',
    unverified: '外部来源 · 未验证',
    integrity_mismatch: '证据完整性异常',
    invalid: '来源链接无效',
    missing: '未收录来源',
  } as Record<string, string>)[status ?? 'missing'] ?? '来源状态未知';
}

// hover 浮卡: 展示已持久化的材料身份和原文片段。
function CiteHover({ cite, x, y }: { cite: Citation; x: number; y: number }) {
  const sourceUrl = safeSourceUrl(cite.source_url);
  return (
    <div className="fixed z-50 max-w-md bg-ink-900 text-ink-50 text-xs px-3.5 py-3 rounded-lg shadow-lift pointer-events-auto"
         style={{ left: x + 12, top: y + 12 }}>
      <div className="font-semibold mb-1.5 flex items-center gap-2">
        <span className="text-accent-400">{cite.sid}</span>
        <span>{cite.title}</span>
      </div>
      <div className="text-ink-300 text-[10px] mb-1.5">{cite.node_id}</div>
      <div className={`text-[10px] mb-1.5 ${cite.source_status === 'integrity_mismatch' ? 'text-rose-300' : 'text-ink-400'}`}>
        {sourceStatusLabel(cite.source_status)}
      </div>
      <div className="text-ink-200 whitespace-pre-wrap leading-relaxed mb-2">
        {cite.text?.slice(0, 200)}…
      </div>
      {sourceUrl ? <a href={sourceUrl} target="_blank" rel="noreferrer" className="text-accent-300 hover:text-accent-200">打开原始材料 ↗</a>
        : <div className="text-[10px] text-ink-400">{cite.source_status === 'managed' ? '平台内部固化材料' : '未收录原始链接'}</div>}
      {cite.graph_path && <div className="border-t border-white/10 pt-2 text-[10px] text-ink-300">关联路径 · {cite.graph_path}</div>}
    </div>
  );
}

// 右侧溯源面板 (Qwen 风格: 列表 + 详情, 选中高亮)
function ReferencePanel({ citations, pinnedKey, onPin, onClose }: {
  citations: DisplayCitation[];
  pinnedKey: string | null;
  onPin: (key: string) => void;
  onClose: () => void;
}) {
  const pinned = citations.find(c => c.key === pinnedKey) ?? citations[0];
  const sourceUrl = safeSourceUrl(pinned?.source_url);
  return (
    <aside className="w-80 border-l border-ink-100 bg-ink-50/50 flex flex-col">
      <div className="px-4 py-3 border-b border-ink-100 flex items-center justify-between bg-white">
        <div className="text-sm font-semibold text-ink-900">参考材料</div>
        <button onClick={onClose} className="text-xs text-ink-500 hover:text-ink-900">关闭</button>
      </div>
      <div className="flex-1 overflow-y-auto p-3 space-y-2">
        {citations.length === 0 && <div className="text-xs text-ink-500 py-4 text-center">本次回答未引用材料</div>}
        {citations.map(c => (
          <button key={c.key} onClick={() => onPin(c.key)}
                  className={`w-full text-left p-2.5 rounded-md border transition ${
                    pinnedKey === c.key ? 'bg-white border-accent-500 shadow-soft' : 'bg-white border-ink-100 hover:border-ink-300'
                  }`}>
            <div className="flex items-center gap-1.5 mb-1">
              <span className="text-[10px] font-medium text-accent-600">{c.sid}</span>
              <span className="text-xs font-medium text-ink-900 truncate">{c.title}</span>
            </div>
            <div className="text-[10px] text-ink-500 truncate">{c.node_id}</div>
          </button>
        ))}
      </div>
      {pinned && (
        <div className="border-t border-ink-100 bg-white p-4 max-h-72 overflow-y-auto">
          <div className="text-xs text-ink-500 mb-1">详情</div>
          <div className="text-sm font-semibold text-ink-900 mb-1">{pinned.title}</div>
          <div className="text-xs text-ink-500 mb-3">{pinned.node_id} · {pinned.type}</div>
          <div className={`text-[11px] mb-3 ${pinned.source_status === 'integrity_mismatch' ? 'text-rose-600' : 'text-ink-500'}`}>
            {sourceStatusLabel(pinned.source_status)}
          </div>
          <div className="text-xs text-ink-700 whitespace-pre-wrap leading-relaxed">{pinned.text}</div>
          {sourceUrl ? <a href={sourceUrl} target="_blank" rel="noreferrer" className="inline-flex mt-3 text-xs text-accent-600 hover:text-accent-700">打开原始材料 ↗</a>
            : <div className="mt-3 text-[11px] text-ink-500">{pinned.source_status === 'managed' ? '平台内部固化材料，无外部跳转' : '该材料尚未收录原始链接'}</div>}
          {pinned.graph_path && <div className="mt-3 pt-3 border-t border-ink-100 text-[11px] text-ink-500 leading-relaxed">关联路径 · {pinned.graph_path}</div>}
        </div>
      )}
    </aside>
  );
}

function parseStoredCitations(raw?: string): Citation[] {
  if (!raw) return [];
  try {
    const value: unknown = JSON.parse(raw);
    if (!Array.isArray(value)) return [];
    return value.flatMap(item => {
      if (!item || typeof item !== 'object') return [];
      const c = item as Record<string, unknown>;
      if (typeof c.sid !== 'string' || typeof c.node_id !== 'string' || typeof c.title !== 'string') return [];
      return [{
        sid: c.sid, node_id: c.node_id, title: c.title,
        type: typeof c.type === 'string' ? c.type : 'unknown',
        text: typeof c.text === 'string' ? c.text : '',
        graph_path: typeof c.graph_path === 'string' ? c.graph_path : '',
        source_url: typeof c.source_url === 'string' ? c.source_url : '',
        source_status: typeof c.source_status === 'string' ? c.source_status : 'missing',
        evidence_hash: typeof c.evidence_hash === 'string' ? c.evidence_hash : '',
      }];
    });
  } catch {
    return [];
  }
}

function parseCitationIssues(raw?: string): string[] {
  if (!raw) return [];
  try {
    const value: unknown = JSON.parse(raw);
    return Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string') : [];
  } catch { return []; }
}

// 对话分组 (Qwen 风格: 今天 / 昨天 / 过去 7 天 / 过去 30 天)
function groupConvs(convs: Conv[]): { label: string; items: Conv[] }[] {
  const now = new Date();
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
  const yesterday = today - 86400000;
  const week = today - 7 * 86400000;
  const month = today - 30 * 86400000;
  const groups = { 今天: [] as Conv[], 昨天: [] as Conv[], '过去 7 天': [] as Conv[], '过去 30 天': [] as Conv[] };
  for (const c of convs) {
    const t = c.last_active_at ? new Date(c.last_active_at).getTime() : 0;
    if (t >= today) groups.今天.push(c);
    else if (t >= yesterday) groups.昨天.push(c);
    else if (t >= week) groups['过去 7 天'].push(c);
    else if (t >= month) groups['过去 30 天'].push(c);
  }
  return (['今天', '昨天', '过去 7 天', '过去 30 天'] as const)
    .filter(k => groups[k].length > 0)
    .map(k => ({ label: k, items: groups[k] }));
}

export default function ChatPage() {
  const [convId, setConvId] = useState<number | null>(null);
  const [messages, setMessages] = useState<Msg[]>([]);
  const [input, setInput] = useState('');
  const [streaming, setStreaming] = useState(false);
  const [stage, setStage] = useState<string | null>(null);
  const [convs, setConvs] = useState<Conv[]>([]);
  const [hoverCite, setHoverCite] = useState<{ c: Citation; x: number; y: number } | null>(null);
  const [pinnedKey, setPinnedKey] = useState<string | null>(null);
  const [feedbackTarget, setFeedbackTarget] = useState<number | null>(null);
  const [panelOpen, setPanelOpen] = useState(false);
  const [sidebarOpen, setSidebarOpen] = useState(true);
  // 今日剩余额度百分比 (meta 事件携带)；<=20% 时展示提示条，用完时提示恢复时间。
  const [quotaRemaining, setQuotaRemaining] = useState<number | null>(null);
  // 主动记忆：新会话开场透出上次未完成事项；进入具体会话后不再打扰。
  const [openItems, setOpenItems] = useState<string[]>([]);
  const [openItemsDismissed, setOpenItemsDismissed] = useState(false);
  const scrollRef = useRef<HTMLDivElement>(null);
  const abortRef = useRef<AbortController | null>(null);
  const turnIdRef = useRef<string | null>(null);
  const activeStreamId = useRef<string | null>(null);

  useEffect(() => { scrollRef.current?.scrollTo({ top: 1e9, behavior: 'smooth' }); }, [messages, stage]);

  useEffect(() => {
    if (convId != null) { setOpenItems([]); return; }
    let cancelled = false;
    api.getOpenItems().then(items => { if (!cancelled) setOpenItems(items); }).catch(() => {});
    return () => { cancelled = true; };
  }, [convId]);

  useEffect(() => { api.listConversations().then(setConvs).catch(() => {}); }, [convId]);

  async function loadConv(id: number) {
    const msgs = await api.getMessages(id);
    setMessages(msgs.map(m => ({
      id: m.id, role: m.role as 'user' | 'assistant', content: m.content,
      citations: parseStoredCitations(m.citations), citationStatus: m.citationStatus,
      citationIssues: parseCitationIssues(m.citationIssues), trace_id: m.traceId,
      feedback: m.feedback === 'helpful' || m.feedback === 'not_helpful' ? m.feedback : undefined,
    })));
    setConvId(id);
    setPanelOpen(false); setPinnedKey(null);
  }

  function newChat() {
    setConvId(null);
    setMessages([]);
    setStage(null);
    setPanelOpen(false); setPinnedKey(null);
  }

  function send(override?: string) {
    const text = (override ?? input).trim();
    if (!text || streaming) return;
    setInput('');
    const userMsg: Msg = { role: 'user', content: text };
    const assistantPlaceholder: Msg & { citations?: Citation[]; tokens?: string; clarify?: string } = { role: 'assistant', content: '', tokens: '' };
    let memoriesBuffer: MemoryRef[] = [];
    setMessages(m => [...m, userMsg, assistantPlaceholder]);
    setStreaming(true);
    setStage('routing');
    setPanelOpen(false); setPinnedKey(null);

    const myStreamId = Math.random().toString(36).slice(2);
    const requestId = typeof crypto !== 'undefined' && crypto.randomUUID
      ? crypto.randomUUID() : `${Date.now()}-${myStreamId}`;
    activeStreamId.current = myStreamId;

    const ctrl = streamChat(
      { conversationId: convId, message: text, requestId },
      {
        isActive: () => activeStreamId.current === myStreamId,
        onMeta: e => {
          assistantPlaceholder.trace_id = e.trace_id;
          turnIdRef.current = e.turn_id ?? null;
          setConvId(e.conversation_id);
          if (typeof e.quota_remaining_percent === 'number') setQuotaRemaining(e.quota_remaining_percent);
        },
        onStage: e => setStage(e.expert && e.status ? `${e.phase}:${e.expert}:${e.status}` : e.phase),
        onToken: t => {
          if (activeStreamId.current !== myStreamId) return;
          assistantPlaceholder.tokens = (assistantPlaceholder.tokens ?? '') + t;
          setMessages(m => {
            const copy = [...m];
            const last = copy[copy.length - 1];
            if (last && last.role === 'assistant' && !last.locked) {
              last.content = assistantPlaceholder.tokens ?? '';
            }
            return copy;
          });
        },
        onCitation: c => {
          if (activeStreamId.current !== myStreamId) return;
          // 累积到 placeholder.citations (避免 setMessages 闭包 m 竞态)
          assistantPlaceholder.citations = [...(assistantPlaceholder.citations ?? []), c];
          setMessages(m => {
            const copy = [...m];
            const last = copy[copy.length - 1];
            if (last && last.role === 'assistant' && !last.locked) {
              last.citations = assistantPlaceholder.citations;
            }
            return copy;
          });
        },
        onMemories: e => {
          if (activeStreamId.current !== myStreamId) return;
          memoriesBuffer = [...memoriesBuffer, ...(e.items ?? [])];
          setMessages(m => {
            const copy = [...m];
            const last = copy[copy.length - 1];
            if (last && last.role === 'assistant' && !last.locked) {
              last.memories = memoriesBuffer;
            }
            return copy;
          });
        },
        onClarify: event => {
          if (activeStreamId.current !== myStreamId) return;
          assistantPlaceholder.clarify = event.question;
          assistantPlaceholder.clarifyOptions = event.options ?? [];
          setMessages(m => {
            const copy = [...m];
            const last = copy[copy.length - 1];
            if (last && last.role === 'assistant' && !last.locked) {
              last.clarify = event.question;
              last.clarifyOptions = event.options ?? [];
            }
            return copy;
          });
        },
        onDone: e => {
          if (activeStreamId.current !== myStreamId) return;
          turnIdRef.current = null;
          assistantPlaceholder.citationStatus = e.citation_status;
          assistantPlaceholder.citationIssues = e.citation_issues ?? [];
          setMessages(m => {
            const copy = [...m];
            const last = copy[copy.length - 1];
            if (last && last.role === 'assistant') {
              last.locked = true;
              last.id = e.message_id;
              last.trace_id = e.trace_id ?? assistantPlaceholder.trace_id;
              last.citationStatus = assistantPlaceholder.citationStatus;
              last.citationIssues = assistantPlaceholder.citationIssues;
              last.truncated = e.truncated ?? false;
            }
            return copy;
          });
          setStreaming(false); setStage(null);
          activeStreamId.current = null;
          // 引用数量 > 0 自动展开右侧面板
          setMessages(m => {
            let lastIndex = -1;
            for (let i = m.length - 1; i >= 0; i -= 1) {
              if (m[i].role === 'assistant') { lastIndex = i; break; }
            }
            const last = lastIndex >= 0 ? m[lastIndex] : undefined;
            if (last?.citations && last.citations.length > 0) {
              setPanelOpen(true);
              setPinnedKey(`${lastIndex}:${last.citations[0].sid}`);
            }
            return m;
          });
          api.listConversations().then(setConvs).catch(() => {});
        },
        onError: msg => {
          if (activeStreamId.current !== myStreamId) return;
          turnIdRef.current = null;
          setStreaming(false); setStage(null);
          setMessages(m => [...m, { role: 'assistant', content: '⚠️ ' + msg }]);
        },
      }
    );
    abortRef.current = ctrl;
  }

  function stop() {
    const turnId = turnIdRef.current;
    if (turnId) api.cancelChatTurn(turnId).catch(() => {});
    abortRef.current?.abort();
    activeStreamId.current = null;
    turnIdRef.current = null;
    setStreaming(false); setStage(null);
  }

  async function submitFeedback(messageIndex: number, rating: 'helpful' | 'not_helpful', reason?: string) {
    const message = messages[messageIndex];
    if (!message?.id || message.role !== 'assistant') return;
    const previous = message.feedback;
    setMessages(items => items.map((item, i) => i === messageIndex ? { ...item, feedback: rating } : item));
    try {
      await api.submitMessageFeedback(message.id, rating, reason);
      setFeedbackTarget(null);
    } catch {
      setMessages(items => items.map((item, i) => i === messageIndex ? { ...item, feedback: previous } : item));
    }
  }

  // 使用“消息序号:S#”作为 UI 主键，避免多轮回答都有 S1 时相互覆盖。
  const allCitations: DisplayCitation[] = (() => {
    const result: DisplayCitation[] = [];
    messages.forEach((msg, messageIndex) => {
      if (msg.role === 'assistant') msg.citations?.forEach(c => result.push({ ...c, key: `${messageIndex}:${c.sid}` }));
    });
    return result;
  })();

  function onMouseMove(e: React.MouseEvent) {
    const t = e.target as HTMLElement;
    const ref = t.closest('.cite-ref') as HTMLElement | null;
    if (ref) {
      const key = ref.dataset.citeKey;
      const c = allCitations.find(x => x.key === key);
      if (c) setHoverCite({ c, x: e.clientX, y: e.clientY });
    } else {
      setHoverCite(null);
    }
  }

  return (
    <div className="h-full flex bg-transparent">
      {/* 左侧会话列表 (Qwen 风格: 分组 + 时间) */}
      <aside className={`${sidebarOpen ? 'w-72' : 'w-0'} shrink-0 border-r editorial-rule bg-[#f8f7f3] flex flex-col transition-all overflow-hidden`}>
        <div className="px-4 py-5 border-b editorial-rule shrink-0">
          <button onClick={newChat} className="w-full px-3.5 py-2.5 bg-[#3155d9] hover:bg-[#2747c2] text-white rounded-sm text-sm font-semibold flex items-center justify-center gap-2 transition">
            <span className="text-lg leading-none">+</span><span>开启新对话</span>
          </button>
        </div>
        <div className="flex-1 overflow-y-auto px-2 py-2 space-y-3">
          {groupConvs(convs).map(g => (
            <div key={g.label}>
              <div className="px-2 py-1.5 text-[10px] font-semibold text-ink-500 uppercase tracking-[.12em]">{g.label}</div>
              <div className="space-y-0.5">
                {g.items.map(c => (
                  <button key={c.id} onClick={() => loadConv(c.id)}
                          className={`w-full text-left px-3 py-2.5 rounded-xl text-sm transition ${
                            convId === c.id ? 'bg-accent-50 text-accent-700 font-medium shadow-soft' : 'text-ink-700 hover:bg-white/85'
                          }`}>
                    <div className="truncate text-xs">{c.title || '(无标题)'}</div>
                  </button>
                ))}
              </div>
            </div>
          ))}
        </div>
      </aside>

      {/* 主区: 顶部固定 header + 消息流 + 底部输入 */}
      <div className="flex-1 flex flex-col min-w-0"
           onMouseMove={onMouseMove}
           onMouseLeave={() => setHoverCite(null)}
           onClick={e => {
             const t = e.target as HTMLElement;
             const ref = t.closest('.cite-ref') as HTMLElement | null;
             if (ref) {
               const key = ref.dataset.citeKey;
               if (key) { setPinnedKey(key); setPanelOpen(true); }
             }
           }}>
        {/* 顶部固定: 标题 + 上下滚动提示 */}
        <header className="shrink-0 px-7 py-5 border-b editorial-rule bg-[#f8f7f3] flex items-center justify-between">
          <div className="flex items-center gap-3">
            <button onClick={() => setSidebarOpen(o => !o)} className="text-ink-500 hover:text-accent-600 hover:bg-white p-2 rounded-lg transition" title={sidebarOpen ? '折叠历史' : '展开历史'}>
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"><line x1="3" y1="6" x2="21" y2="6"/><line x1="3" y1="12" x2="21" y2="12"/><line x1="3" y1="18" x2="21" y2="18"/></svg>
            </button>
            <div>
              <div className="editorial-kicker mb-1">当前工作区</div><div className="text-base font-semibold tracking-[-.02em] text-ink-900">
                {convId ? `对话 #${convId}` : '新对话'}
              </div>
              <div className="text-xs text-ink-500 mt-1">你的专属成长教练 · 每条建议均可溯源</div>
            </div>
          </div>
          <div className="text-xs text-ink-500">
            {streaming && stage && <span className="inline-flex items-center gap-1.5 bg-accent-50 text-accent-700 px-2.5 py-1 rounded-full"><span className="w-1.5 h-1.5 rounded-full bg-accent-500 animate-pulse" />{stageLabel(stage)}</span>}
          </div>
        </header>

        <div ref={scrollRef} className="flex-1 overflow-y-auto px-7 py-7">
          <div className="max-w-3xl mx-auto space-y-4">
            {quotaRemaining !== null && quotaRemaining <= 20 && (
              <div className={`rounded-xl border px-4 py-2 text-sm ${quotaRemaining === 0 ? 'border-rose-200 bg-rose-50 text-rose-700' : 'border-amber-200 bg-amber-50 text-amber-700'}`}>
                {quotaRemaining === 0
                  ? '你今天的 AI 额度已用完，明天 0 点自动恢复。'
                  : `今日 AI 额度剩余 ${quotaRemaining}%，请合理安排提问。`}
              </div>
            )}
            {convId == null && !openItemsDismissed && openItems.length > 0 && (
              <div className="mx-auto max-w-3xl mb-4 rounded-2xl border border-accent-100 bg-accent-50/60 px-4 py-3.5" role="status">
                <div className="flex items-center justify-between gap-3">
                  <div className="text-xs font-medium text-accent-700">🧠 上次还有这些没完成</div>
                  <button onClick={() => setOpenItemsDismissed(true)} className="text-xs text-ink-400 hover:text-ink-600" aria-label="关闭提醒">✕</button>
                </div>
                <div className="mt-2 flex flex-wrap gap-2">
                  {openItems.map(item => (
                    <button key={item} onClick={() => setInput(`我们继续上次的话题：${item}`)}
                      className="rounded-full bg-white border border-accent-200 px-3 py-1.5 text-xs text-ink-700 hover:border-accent-400 hover:text-accent-700 transition">
                      {item} ↗
                    </button>
                  ))}
                </div>
              </div>
            )}
            {messages.length === 0 && (
            <div className="text-center mt-24 space-y-6 text-ink-500">
                <div className="mx-auto h-16 w-16 rounded-2xl bg-[#211950] text-white text-2xl font-semibold shadow-[0_16px_34px_rgba(45,32,113,.25)] flex items-center justify-center">T</div>
                <div><div className="text-3xl font-semibold tracking-[-.035em] text-ink-900">今天，想向前走哪一步？</div><div className="text-sm mt-3">从一个问题开始，让学习和求职变得更清晰。</div></div>
                <div className="grid grid-cols-1 sm:grid-cols-3 gap-2.5 text-left max-w-2xl mx-auto pt-2">
                  {['帮我制定本周学习计划', '我适合什么技术岗位？', '推荐一个可做的实战项目'].map(prompt => (
                    <button key={prompt} onClick={() => setInput(prompt)} className="glass-panel rounded-xl p-3.5 text-xs text-ink-700 hover:text-accent-700 hover:-translate-y-0.5 transition text-left">{prompt}<span className="block mt-2 text-accent-500 text-sm">↗</span></button>
                  ))}
                </div>
              </div>
            )}
            {messages.map((m, i) => (
              <div key={i} className={`flex ${m.role === 'user' ? 'justify-end' : 'justify-start'}`}>
                <div className={`max-w-[85%] rounded-2xl px-4 py-3 ${
                  m.role === 'user' ? 'bg-accent-600 text-white shadow-soft' : 'bg-white border border-ink-100 text-ink-900 shadow-soft'
                }`}>
                  {m.role === 'user' ? (
                    <div className="whitespace-pre-wrap break-words">{m.content}</div>
                  ) : (
                    <div className="prose-chat" dangerouslySetInnerHTML={{ __html: renderMarkdown(m.content, String(i)) }} />
                  )}
                  {m.clarify && (
                    <div className="mt-2 px-3 py-2 bg-accent-50 text-accent-700 text-sm rounded">
                      ❓ 追问: {m.clarify}
                      {m.clarifyOptions?.length ? (
                        <div className="mt-2 flex flex-wrap gap-2">
                          {m.clarifyOptions.map(option => (
                            <button key={option.id} onClick={() => setInput(option.label)}
                              className="px-2.5 py-1.5 rounded border border-accent-200 bg-white text-accent-700 hover:bg-accent-100 transition">
                              {option.label}
                            </button>
                          ))}
                        </div>
                      ) : null}
                    </div>
                  )}
                  {m.role === 'assistant' && m.truncated && !streaming && (
                    <div className="mt-2">
                      <button onClick={() => send('继续')}
                        className="px-3 py-1.5 rounded-lg border border-accent-200 bg-accent-50 text-accent-700 text-xs hover:bg-accent-100 transition">
                        回答较长已被截断，点击继续生成 →
                      </button>
                    </div>
                  )}
                  {m.role === 'assistant' && m.memories && m.memories.length > 0 && <MemoryChips memories={m.memories} />}
                  {m.role === 'assistant' && m.citationStatus && m.citationStatus !== 'not_applicable' && (
                    <div className={`mt-2 text-[11px] ${m.citationStatus === 'verified' ? 'text-emerald-600' : m.citationStatus === 'pending' ? 'text-amber-600' : 'text-rose-600'}`}>
                      引用状态：{{ pending: '校验中', verified: '已验证', unsupported: '存在未充分支持的陈述', invalid_reference: '包含无效引用编号', unavailable: '校验服务暂不可用' }[m.citationStatus] ?? m.citationStatus}
                      {m.citationIssues?.length ? `（${m.citationIssues.join('、')}）` : ''}
                    </div>
                  )}
                  {m.role === 'assistant' && m.id && (
                    <div className="mt-3 pt-2.5 border-t border-ink-100 flex items-center gap-2 text-xs text-ink-500">
                      <span>这条回答有帮助吗？</span>
                      <button onClick={() => submitFeedback(i, 'helpful')}
                        className={`px-2 py-1 rounded transition ${m.feedback === 'helpful' ? 'bg-emerald-50 text-emerald-700' : 'hover:bg-ink-50 hover:text-ink-700'}`}>有帮助</button>
                      <button onClick={() => setFeedbackTarget(feedbackTarget === i ? null : i)}
                        className={`px-2 py-1 rounded transition ${m.feedback === 'not_helpful' ? 'bg-rose-50 text-rose-700' : 'hover:bg-ink-50 hover:text-ink-700'}`}>不准确</button>
                    </div>
                  )}
                  {m.role === 'assistant' && m.id && feedbackTarget === i && (
                    <div className="mt-2 flex flex-wrap gap-1.5 text-xs">
                      {[['citation_irrelevant', '引用不相关'], ['factual_error', '内容不准确'], ['too_generic', '太笼统']].map(([reason, label]) => (
                        <button key={reason} onClick={() => submitFeedback(i, 'not_helpful', reason)}
                          className="px-2.5 py-1.5 rounded-lg bg-rose-50 text-rose-700 hover:bg-rose-100 transition">{label}</button>
                      ))}
                    </div>
                  )}
                </div>
              </div>
            ))}
          </div>
        </div>

        <div className="border-t editorial-rule bg-[#f8f7f3] px-7 py-6">
          <div className="max-w-3xl mx-auto flex gap-3 items-end">
            <textarea
              value={input}
              onChange={e => setInput(e.target.value)}
              onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); } }}
              placeholder="输入你的问题，Enter 发送 / Shift+Enter 换行"
              rows={1}
              className="flex-1 resize-none px-4 py-3.5 border border-ink-200 bg-white shadow-none rounded-sm focus:outline-none focus:ring-2 focus:ring-[#3155d9]/20 focus:border-[#3155d9] max-h-32 transition"
            />
            {streaming ? (
              <button onClick={stop} className="px-5 py-3.5 bg-ink-200 hover:bg-ink-300 text-ink-700 rounded-xl font-medium transition">停止</button>
            ) : (
              <button onClick={() => send()} disabled={!input.trim()} className="px-5 py-3.5 bg-[#3155d9] hover:bg-[#2747c2] disabled:bg-ink-200 text-white rounded-sm font-medium transition">发送</button>
            )}
          </div>
        </div>

        {hoverCite && <CiteHover cite={hoverCite.c} x={hoverCite.x} y={hoverCite.y} />}
      </div>

      {/* 右侧参考材料面板 (Qwen 风格: 列表 + 详情) */}
      {panelOpen && <ReferencePanel citations={allCitations} pinnedKey={pinnedKey} onPin={setPinnedKey} onClose={() => setPanelOpen(false)} />}
    </div>
  );
}

function MemoryChips({ memories }: { memories: MemoryRef[] }) {
  const [open, setOpen] = useState(false);
  return (
    <div className="mt-2">
      <button onClick={() => setOpen(o => !o)}
        className="text-[11px] text-ink-500 hover:text-accent-600 inline-flex items-center gap-1 transition">
        <span aria-hidden>🧠</span>本次回答参考了 {memories.length} 条跨会话记忆{open ? '' : ' ▾'}
      </button>
      {open && (
        <div className="mt-1.5 space-y-1" role="list" aria-label="参考记忆">
          {memories.map(r => (
            <div key={`${r.kind}-${r.id}`} role="listitem"
              className="text-[11px] text-ink-600 bg-[#f8f7f3] border border-ink-100 rounded px-2 py-1 flex items-start gap-1.5">
              <span className={`shrink-0 px-1.5 py-0.5 rounded-full text-[10px] ${r.kind === 'fact' ? 'bg-accent-50 text-accent-700' : 'bg-ink-100 text-ink-600'}`}>
                {r.kind === 'fact' ? '长期事实' : '过往经历'}
              </span>
              <span className="break-words">{r.text}</span>
            </div>
          ))}
          <div className="text-[10px] text-ink-400">可在「跨会话记忆」页面删除对应记录。</div>
        </div>
      )}
    </div>
  );
}

function stageLabel(s: string): string {
  const expertDone = /^expert_done:(.+?):(success|timeout|failed|cancelled|rejected)$/.exec(s);
  if (expertDone) {
    const label = ({ planner: '规划专家', resume: '简历专家', interview: '面试专家' } as Record<string, string>)[expertDone[1]] ?? expertDone[1];
    const status = ({ success: '完成', timeout: '超时', failed: '失败', cancelled: '已取消', rejected: '未执行' } as Record<string, string>)[expertDone[2]];
    return `${label}${status}`;
  }
  const m: Record<string, string> = {
    'routing': '意图识别…',
    'retrieving': '检索证据…',
    'expert:planner': '规划专家思考…',
    'expert:resume': '简历专家思考…',
    'expert:career': '求职专家思考…',
    'aggregating': '整合答案…',
  };
  return m[s] ?? s;
}
