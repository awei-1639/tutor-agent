import { useEffect, useRef, useState } from 'react';
import { api, streamChat } from '../lib/api';
import { renderMarkdown } from '../lib/markdown';

interface Citation { sid: string; node_id: string; type: string; title: string; text: string; }
interface Msg { role: 'user' | 'assistant'; content: string; tokens?: string; citations?: Citation[]; clarify?: string; trace_id?: string; locked?: boolean; }
interface Conv { id: number; last_active_at: string | null; title: string | null; msg_count: number; }

// hover 浮卡 (Qwen 风格: 黑底卡片 + 标题 + node_id + text 前 200 字 + 跳转)
function CiteHover({ cite, x, y }: { cite: Citation; x: number; y: number }) {
  const handleOpen = () => {
    const url = cite.node_id.startsWith('res:')
      ? `https://www.google.com/search?q=${encodeURIComponent(cite.title)}`
      : `https://www.google.com/search?q=${encodeURIComponent(cite.node_id + ' ' + cite.title)}`;
    window.open(url, '_blank', 'noopener,noreferrer');
  };
  return (
    <div className="fixed z-50 max-w-md bg-ink-900 text-ink-50 text-xs px-3.5 py-3 rounded-lg shadow-lift pointer-events-auto"
         style={{ left: x + 12, top: y + 12 }}>
      <div className="font-semibold mb-1.5 flex items-center gap-2">
        <span className="text-accent-400">{cite.sid}</span>
        <span>{cite.title}</span>
      </div>
      <div className="text-ink-300 text-[10px] mb-1.5">{cite.node_id}</div>
      <div className="text-ink-200 whitespace-pre-wrap leading-relaxed mb-2">
        {cite.text?.slice(0, 200)}…
      </div>
      <button onClick={handleOpen}
              className="text-accent-400 hover:text-accent-300 flex items-center gap-1">
        <span>↗</span><span>查看来源</span>
      </button>
    </div>
  );
}

// 右侧溯源面板 (Qwen 风格: 列表 + 详情, 选中高亮)
function ReferencePanel({ citations, pinnedSid, onPin, onClose }: {
  citations: Citation[];
  pinnedSid: string | null;
  onPin: (sid: string) => void;
  onClose: () => void;
}) {
  const pinned = citations.find(c => c.sid === pinnedSid) ?? citations[0];
  return (
    <aside className="w-80 border-l border-ink-100 bg-ink-50/50 flex flex-col">
      <div className="px-4 py-3 border-b border-ink-100 flex items-center justify-between bg-white">
        <div className="text-sm font-semibold text-ink-900">参考材料</div>
        <button onClick={onClose} className="text-xs text-ink-500 hover:text-ink-900">关闭</button>
      </div>
      <div className="flex-1 overflow-y-auto p-3 space-y-2">
        {citations.length === 0 && <div className="text-xs text-ink-500 py-4 text-center">本次回答未引用材料</div>}
        {citations.map(c => (
          <button key={c.sid} onClick={() => onPin(c.sid)}
                  className={`w-full text-left p-2.5 rounded-md border transition ${
                    pinnedSid === c.sid ? 'bg-white border-accent-500 shadow-soft' : 'bg-white border-ink-100 hover:border-ink-300'
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
          <div className="text-xs text-ink-700 whitespace-pre-wrap leading-relaxed">{pinned.text}</div>
          <button onClick={() => window.open(`https://www.google.com/search?q=${encodeURIComponent(pinned.node_id + ' ' + pinned.title)}`, '_blank')}
                  className="mt-3 text-xs text-accent-600 hover:text-accent-700 flex items-center gap-1">
            <span>↗</span><span>查看来源</span>
          </button>
        </div>
      )}
    </aside>
  );
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
  const [pinnedSid, setPinnedSid] = useState<string | null>(null);
  const [panelOpen, setPanelOpen] = useState(false);
  const scrollRef = useRef<HTMLDivElement>(null);
  const abortRef = useRef<AbortController | null>(null);
  const activeStreamId = useRef<string | null>(null);

  useEffect(() => { scrollRef.current?.scrollTo({ top: 1e9, behavior: 'smooth' }); }, [messages, stage]);

  useEffect(() => { api.listConversations().then(setConvs).catch(() => {}); }, [convId]);

  async function loadConv(id: number) {
    const msgs = await api.getMessages(id);
    setMessages(msgs.map(m => ({ role: m.role as 'user' | 'assistant', content: m.content })));
    setConvId(id);
    setPanelOpen(false); setPinnedSid(null);
  }

  function newChat() {
    setConvId(null);
    setMessages([]);
    setStage(null);
    setPanelOpen(false); setPinnedSid(null);
  }

  function send() {
    const text = input.trim();
    if (!text || streaming) return;
    setInput('');
    const userMsg: Msg = { role: 'user', content: text };
    const assistantPlaceholder: Msg = { role: 'assistant', content: '', tokens: '' };
    setMessages(m => [...m, userMsg, assistantPlaceholder]);
    setStreaming(true);
    setStage('routing');
    setPanelOpen(false); setPinnedSid(null);

    const myStreamId = Math.random().toString(36).slice(2);
    activeStreamId.current = myStreamId;

    const ctrl = streamChat(
      { conversationId: convId, message: text },
      {
        isActive: () => activeStreamId.current === myStreamId,
        onMeta: e => setConvId(e.conversation_id),
        onStage: e => setStage(e.phase),
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
          setMessages(m => {
            const copy = [...m];
            const last = copy[copy.length - 1];
            if (last && last.role === 'assistant' && !last.locked) {
              last.citations = [...(last.citations ?? []), c];
            }
            return copy;
          });
        },
        onClarify: q => {
          if (activeStreamId.current !== myStreamId) return;
          setMessages(m => {
            const copy = [...m];
            const last = copy[copy.length - 1];
            if (last && last.role === 'assistant' && !last.locked) last.clarify = q;
            return copy;
          });
        },
        onDone: () => {
          if (activeStreamId.current !== myStreamId) return;
          setMessages(m => {
            const copy = [...m];
            const last = copy[copy.length - 1];
            if (last && last.role === 'assistant') last.locked = true;
            return copy;
          });
          setStreaming(false); setStage(null);
          activeStreamId.current = null;
          // 引用数量 > 0 自动展开右侧面板
          setMessages(m => {
            const last = [...m].reverse().find(x => x.role === 'assistant');
            if (last && last.citations && last.citations.length > 0) {
              setPanelOpen(true);
              setPinnedSid(last.citations[0].sid);
            }
            return m;
          });
          api.listConversations().then(setConvs).catch(() => {});
        },
        onError: msg => {
          if (activeStreamId.current !== myStreamId) return;
          setStreaming(false); setStage(null);
          setMessages(m => [...m, { role: 'assistant', content: '⚠️ ' + msg }]);
        },
      }
    );
    abortRef.current = ctrl;
  }

  function stop() {
    abortRef.current?.abort();
    activeStreamId.current = null;
    setStreaming(false); setStage(null);
  }

  // 累积所有 assistant 的 citations (跨多轮)
  const allCitations: Citation[] = (() => {
    const m: Record<string, Citation> = {};
    for (const msg of messages) if (msg.role === 'assistant') msg.citations?.forEach(c => m[c.sid] = c);
    return Object.values(m);
  })();

  function onMouseMove(e: React.MouseEvent) {
    const t = e.target as HTMLElement;
    const ref = t.closest('.cite-ref') as HTMLElement | null;
    if (ref) {
      const sid = ref.dataset.sid;
      const c = allCitations.find(x => x.sid === sid);
      if (c) setHoverCite({ c, x: e.clientX, y: e.clientY });
    } else {
      setHoverCite(null);
    }
  }

  return (
    <div className="h-full flex">
      {/* 左侧会话列表 (Qwen 风格: 分组 + 时间) */}
      <aside className="w-64 border-r border-ink-100 bg-white flex flex-col">
        <div className="px-4 py-4 border-b border-ink-100">
          <button onClick={newChat} className="w-full px-3 py-2 bg-accent-500 hover:bg-accent-600 text-white rounded-md text-sm font-medium flex items-center justify-center gap-1.5">
            <span>+</span><span>新建对话</span>
          </button>
        </div>
        <div className="flex-1 overflow-y-auto px-2 py-2 space-y-3">
          {groupConvs(convs).map(g => (
            <div key={g.label}>
              <div className="px-2 py-1 text-[10px] font-medium text-ink-500 uppercase tracking-wide">{g.label}</div>
              <div className="space-y-0.5">
                {g.items.map(c => (
                  <button key={c.id} onClick={() => loadConv(c.id)}
                          className={`w-full text-left px-2.5 py-2 rounded-md text-sm transition ${
                            convId === c.id ? 'bg-accent-50 text-accent-700 font-medium' : 'text-ink-700 hover:bg-ink-50'
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
               const sid = ref.dataset.sid;
               if (sid) { setPinnedSid(sid); setPanelOpen(true); }
             }
           }}>
        {/* 顶部固定: 标题 + 上下滚动提示 */}
        <header className="shrink-0 px-6 py-3 border-b border-ink-100 bg-white flex items-center justify-between">
          <div>
            <div className="text-sm font-semibold text-ink-900">
              {convId ? `对话 #${convId}` : '新对话'}
            </div>
            <div className="text-xs text-ink-500 mt-0.5">个人 AI 学习与求职教练 · 引用 [S#] 可溯源</div>
          </div>
          <div className="text-xs text-ink-500">
            {streaming && stage && <span className="text-accent-600">● {stageLabel(stage)}</span>}
          </div>
        </header>

        <div ref={scrollRef} className="flex-1 overflow-y-auto px-6 py-6">
          <div className="max-w-3xl mx-auto space-y-4">
            {messages.length === 0 && (
              <div className="text-center mt-32 space-y-3 text-ink-500">
                <div className="text-5xl">💬</div>
                <div className="text-lg font-medium text-ink-700">有什么我可以帮你的？</div>
                <div className="text-sm">试试问：如何入门 NLP？我适合做算法岗吗？推荐几本深度学习书？</div>
              </div>
            )}
            {messages.map((m, i) => (
              <div key={i} className={`flex ${m.role === 'user' ? 'justify-end' : 'justify-start'}`}>
                <div className={`max-w-[85%] rounded-2xl px-4 py-3 ${
                  m.role === 'user' ? 'bg-accent-500 text-white' : 'bg-white border border-ink-100 text-ink-900 shadow-soft'
                }`}>
                  {m.role === 'user' ? (
                    <div className="whitespace-pre-wrap break-words">{m.content}</div>
                  ) : (
                    <div className="prose-chat" dangerouslySetInnerHTML={{ __html: renderMarkdown(m.content) }} />
                  )}
                  {m.clarify && (
                    <div className="mt-2 px-3 py-2 bg-accent-50 text-accent-700 text-sm rounded">
                      ❓ 追问: {m.clarify}
                    </div>
                  )}
                </div>
              </div>
            ))}
          </div>
        </div>

        <div className="border-t border-ink-100 bg-white px-6 py-4">
          <div className="max-w-3xl mx-auto flex gap-3">
            <textarea
              value={input}
              onChange={e => setInput(e.target.value)}
              onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); } }}
              placeholder="输入你的问题，Enter 发送 / Shift+Enter 换行"
              rows={1}
              className="flex-1 resize-none px-4 py-3 border border-ink-200 rounded-md focus:outline-none focus:ring-2 focus:ring-accent-500/30 focus:border-accent-500 max-h-32"
            />
            {streaming ? (
              <button onClick={stop} className="px-5 py-3 bg-ink-200 hover:bg-ink-300 text-ink-700 rounded-md font-medium">停止</button>
            ) : (
              <button onClick={send} disabled={!input.trim()} className="px-5 py-3 bg-accent-500 hover:bg-accent-600 disabled:bg-ink-200 text-white rounded-md font-medium">发送</button>
            )}
          </div>
        </div>

        {hoverCite && <CiteHover cite={hoverCite.c} x={hoverCite.x} y={hoverCite.y} />}
      </div>

      {/* 右侧参考材料面板 (Qwen 风格: 列表 + 详情) */}
      {panelOpen && <ReferencePanel citations={allCitations} pinnedSid={pinnedSid} onPin={setPinnedSid} onClose={() => setPanelOpen(false)} />}
    </div>
  );
}

function stageLabel(s: string): string {
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