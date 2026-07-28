import { useEffect, useRef, useState } from 'react';
import { api, streamChat } from '../lib/api';
import { renderMarkdown } from '../lib/markdown';

interface Citation { sid: string; node_id: string; type: string; title: string; text: string; }
interface Msg { role: 'user' | 'assistant'; content: string; citations?: Citation[]; clarify?: string; trace_id?: string; }
interface Conv { id: number; last_active_at: string | null; title: string | null; msg_count: number; }

const STAGES = ['routing', 'retrieving', 'expert:planner', 'expert:resume', 'expert:career', 'aggregating'];

export default function ChatPage() {
  const [convId, setConvId] = useState<number | null>(null);
  const [messages, setMessages] = useState<Msg[]>([]);
  const [input, setInput] = useState('');
  const [streaming, setStreaming] = useState(false);
  const [stage, setStage] = useState<string | null>(null);
  const [hoverCite, setHoverCite] = useState<Citation | null>(null);
  const [tooltipPos, setTooltipPos] = useState({ x: 0, y: 0 });
  const [convs, setConvs] = useState<Conv[]>([]);
  const [pinnedCite, setPinnedCite] = useState<Citation | null>(null);
  const scrollRef = useRef<HTMLDivElement>(null);
  const abortRef = useRef<AbortController | null>(null);
  const userId = Number(localStorage.getItem('tutor_user_id') ?? 1);

  // 加载会话列表
  useEffect(() => {
    api.listConversations().then(setConvs).catch(() => {});
  }, [convId]);

  // 加载选中会话历史
  async function loadConv(id: number) {
    const msgs = await api.getMessages(id);
    setMessages(msgs.map(m => ({ role: m.role as 'user' | 'assistant', content: m.content })));
    setConvId(id);
  }

  function newChat() {
    setConvId(null);
    setMessages([]);
    setStage(null);
  }

  useEffect(() => { scrollRef.current?.scrollTo({ top: 1e9, behavior: 'smooth' }); }, [messages, stage]);

  function send() {
    const text = input.trim();
    if (!text || streaming) return;
    setInput('');
    const userMsg: Msg = { role: 'user', content: text };
    setMessages(m => [...m, userMsg]);
    setStreaming(true);
    setStage('routing');

    const ctrl = streamChat(
      { conversationId: convId, message: text },
      {
        onMeta: e => setConvId(e.conversation_id),
        onStage: e => setStage(e.phase),
        onToken: t => {
          setMessages(m => {
            const copy = [...m];
            const last = copy[copy.length - 1];
            if (last && last.role === 'assistant') last.content += t;
            else copy.push({ role: 'assistant', content: t });
            return copy;
          });
        },
        onCitation: c => {
          setMessages(m => {
            const copy = [...m];
            const last = copy[copy.length - 1];
            if (last && last.role === 'assistant') {
              last.citations = [...(last.citations ?? []), c];
            }
            return copy;
          });
        },
        onClarify: q => {
          setMessages(m => {
            const copy = [...m];
            const last = copy[copy.length - 1];
            if (last && last.role === 'assistant') last.clarify = q;
            return copy;
          });
        },
        onDone: () => { setStreaming(false); setStage(null); api.listConversations().then(setConvs).catch(() => {}); },
        onError: msg => {
          setStreaming(false); setStage(null);
          setMessages(m => [...m, { role: 'assistant', content: '⚠️ ' + msg }]);
        },
      }
    );
    abortRef.current = ctrl;
  }

  function stop() {
    abortRef.current?.abort();
    setStreaming(false); setStage(null);
  }

  // 引用缓存: 累积所有 assistant 消息的 citations (key=sid → 最新版本)
  const lastCiteRef = useRef<Map<string, Citation> | null>(null);
  useEffect(() => {
    const m: Map<string, Citation> = new Map();
    for (const msg of messages) {
      if (msg.role === 'assistant') msg.citations?.forEach(c => m.set(c.sid, c));
    }
    lastCiteRef.current = m;
  }, [messages]);

  // 全局点击委托: dangerouslySetInnerHTML 渲染的 .cite-ref 不触发 React 合成事件
  useEffect(() => {
    function onDocClick(e: MouseEvent) {
      const t = e.target as HTMLElement | null;
      if (!t) return;
      const ref = t.closest('.cite-ref') as HTMLElement | null;
      if (!ref) return;
      const sid = ref.dataset.sid;
      if (!sid) return;
      const cit = lastCiteRef.current?.get(sid);
      if (cit) setPinnedCite(cit);
    }
    document.addEventListener('click', onDocClick);
    return () => document.removeEventListener('click', onDocClick);
  }, []);

  return (
    <div className="h-full flex">
      {/* 左侧会话列表 */}
      <aside className="w-56 border-r border-ink-100 bg-white flex flex-col">
        <div className="p-3 border-b border-ink-100">
          <button onClick={newChat} className="w-full px-3 py-2 bg-accent-500 hover:bg-accent-600 text-white rounded-md text-sm font-medium">
            + 新对话
          </button>
        </div>
        <div className="flex-1 overflow-y-auto p-2 space-y-1">
          {convs.length === 0 && <div className="text-xs text-ink-500 px-2 py-3">还没有会话</div>}
          {convs.map(c => (
            <button
              key={c.id}
              onClick={() => loadConv(c.id)}
              className={`w-full text-left px-2.5 py-2 rounded-md text-sm transition ${
                convId === c.id ? 'bg-accent-50 text-accent-700' : 'text-ink-700 hover:bg-ink-50'
              }`}
            >
              <div className="truncate">{c.title || '(无标题)'}</div>
              <div className="text-[10px] text-ink-500 mt-0.5">
                {c.msg_count} 条 · {c.last_active_at ? new Date(c.last_active_at).toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: 'numeric', minute: 'numeric' }) : ''}
              </div>
            </button>
          ))}
        </div>
      </aside>

      {/* 主区 */}
      <div className="flex-1 flex flex-col"
           onMouseOver={e => {
             const t = e.target as HTMLElement;
             if (t.closest('.cite-ref')) {
               const ref = t.closest('.cite-ref') as HTMLElement;
               const sid = ref.dataset.sid;
               const cit = sid ? lastCiteRef.current?.get(sid) : null;
               if (cit) { setHoverCite(cit); setTooltipPos({ x: e.clientX, y: e.clientY }); }
             }
           }}
           onMouseMove={e => hoverCite && setTooltipPos({ x: e.clientX, y: e.clientY })}>
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
                  {m.citations && m.citations.length > 0 && (
                    <div className="mt-3 pt-2 border-t border-ink-100 flex flex-wrap gap-1">
                      {m.citations.map(c => (
                        <span key={c.sid} className="inline-flex items-center gap-1 px-2 py-0.5 bg-ink-50 text-ink-700 text-xs rounded">
                          <span className="text-accent-600 font-medium">{c.sid}</span>
                          <span>{c.title?.slice(0, 18) || c.node_id}</span>
                        </span>
                      ))}
                    </div>
                  )}
                </div>
              </div>
            ))}
            {streaming && stage && (
              <div className="text-xs text-ink-500 flex items-center gap-2 pl-2">
                <span className="inline-block w-2 h-2 bg-accent-500 rounded-full animate-pulse" />
                <span>{stageLabel(stage)}</span>
              </div>
            )}
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

        {hoverCite && (
          <div
            className="fixed z-50 max-w-md bg-ink-900 text-ink-50 text-xs px-3 py-2 rounded shadow-lift pointer-events-none"
            style={{ left: tooltipPos.x + 12, top: tooltipPos.y + 12 }}
          >
            <div className="font-medium mb-1">{hoverCite.sid} · {hoverCite.title}</div>
            <div className="text-ink-200 whitespace-pre-wrap">{hoverCite.text?.slice(0, 240)}…</div>
          </div>
        )}

        {/* 持久引用详情面板 (右侧) */}
        {pinnedCite && (
          <aside className="w-72 border-l border-ink-100 bg-white flex flex-col">
            <div className="px-4 py-3 border-b border-ink-100 flex items-center justify-between">
              <div className="text-sm font-semibold text-ink-900">参考材料</div>
              <button onClick={() => setPinnedCite(null)} className="text-xs text-ink-500 hover:text-ink-900">关闭</button>
            </div>
            <div className="flex-1 overflow-y-auto p-4 space-y-3">
              {lastCiteRef.current && [...lastCiteRef.current.values()].map(c => (
                <button
                  key={c.sid}
                  onClick={() => setPinnedCite(c)}
                  className={`w-full text-left p-3 rounded-md border transition ${
                    pinnedCite?.sid === c.sid ? 'bg-accent-50 border-accent-500' : 'bg-white border-ink-100 hover:border-ink-300'
                  }`}
                >
                  <div className="flex items-center gap-2 mb-1">
                    <span className="text-xs font-medium text-accent-700">{c.sid}</span>
                    <span className="text-sm font-medium text-ink-900 truncate">{c.title || c.node_id}</span>
                  </div>
                  <div className="text-[10px] text-ink-500">{c.node_id}</div>
                  <div className="text-xs text-ink-700 mt-1.5 line-clamp-3">{c.text?.slice(0, 200)}</div>
                </button>
              ))}
            </div>
            <div className="px-4 py-3 border-t border-ink-100">
              <div className="text-xs text-ink-500 mb-1">详情</div>
              <div className="text-sm font-medium text-ink-900">{pinnedCite.title || pinnedCite.node_id}</div>
              <div className="text-xs text-ink-500 mt-1">{pinnedCite.node_id} · {pinnedCite.type}</div>
              <div className="text-sm text-ink-700 mt-3 whitespace-pre-wrap">{pinnedCite.text}</div>
            </div>
          </aside>
        )}
      </div>
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