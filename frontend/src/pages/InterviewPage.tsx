import { useState } from 'react';
import { api } from '../lib/api';

interface Transcript { speaker: 'ai' | 'me'; content: string; score?: number; }

export default function InterviewPage() {
  const [sessionId] = useState(() => 'iv_' + Math.random().toString(36).slice(2, 10));
  const [role, setRole] = useState('NLP 算法工程师');
  const [transcript, setTranscript] = useState<Transcript[]>([]);
  const [input, setInput] = useState('');
  const [loading, setLoading] = useState(false);
  const [finished, setFinished] = useState(false);
  const [report, setReport] = useState<any>(null);

  async function start() {
    setLoading(true);
    try {
      const r: any = await api.openInterview(sessionId, role);
      setTranscript([{ speaker: 'ai', content: r.message }]);
    } finally { setLoading(false); }
  }

  async function send() {
    const text = input.trim();
    if (!text || loading || finished) return;
    setInput('');
    setTranscript(t => [...t, { speaker: 'me', content: text }]);
    setLoading(true);
    try {
      const r: any = await api.answerInterview(sessionId, text);
      const msg: string = r.message ?? '';
      const isEnd = msg.includes('面试结束');
      setTranscript(t => [...t, { speaker: 'ai', content: msg }]);
      if (isEnd) {
        setFinished(true);
        const rep: any = await api.getReport(sessionId);
        setReport(rep);
      }
    } finally { setLoading(false); }
  }

  return (
    <div className="h-full overflow-y-auto px-6 py-6">
      <div className="max-w-3xl mx-auto space-y-5">
        <div>
          <h1 className="text-2xl font-semibold text-ink-900">模拟面试</h1>
          <p className="text-sm text-ink-500 mt-1">基于目标岗位 requires 技能出题，AI 评分 + 追问，复盘报告</p>
        </div>

        {transcript.length === 0 && (
          <div className="card p-6 space-y-3">
            <div className="text-sm font-medium text-ink-700">目标岗位</div>
            <input
              value={role}
              onChange={e => setRole(e.target.value)}
              className="w-full px-3 py-2 border border-ink-200 rounded-md focus:outline-none focus:ring-2 focus:ring-accent-500/30 focus:border-accent-500"
              placeholder="例如: NLP 算法工程师 / 后端开发"
            />
            <button onClick={start} disabled={loading || !role.trim()}
              className="px-4 py-2 bg-accent-500 hover:bg-accent-600 disabled:bg-ink-200 text-white rounded-md text-sm font-medium">
              {loading ? '准备中…' : '开始面试'}
            </button>
          </div>
        )}

        {transcript.length > 0 && (
          <div className="space-y-3">
            {transcript.map((t, i) => (
              <div key={i} className={`flex ${t.speaker === 'me' ? 'justify-end' : 'justify-start'}`}>
                <div className={`max-w-[85%] rounded-2xl px-4 py-3 ${
                  t.speaker === 'me' ? 'bg-accent-500 text-white' : 'bg-white border border-ink-100 text-ink-900 shadow-soft'
                }`}>
                  <div className="whitespace-pre-wrap text-sm">{t.content}</div>
                </div>
              </div>
            ))}
            {loading && (
              <div className="text-xs text-ink-500 flex items-center gap-2 pl-2">
                <span className="inline-block w-2 h-2 bg-accent-500 rounded-full animate-pulse" />
                AI 评分中…
              </div>
            )}
          </div>
        )}

        {finished && (
          <div className="card p-6 space-y-3 border-l-4 border-l-accent-500">
            <h2 className="text-lg font-semibold text-ink-900">复盘报告</h2>
            {report && (
              <>
                <div className="flex items-center gap-6 text-sm">
                  <div>
                    <div className="text-xs text-ink-500">题目数</div>
                    <div className="text-2xl font-semibold text-ink-900">{report.totalQuestions}</div>
                  </div>
                  <div>
                    <div className="text-xs text-ink-500">平均分</div>
                    <div className="text-2xl font-semibold text-accent-600">{report.avgScore?.toFixed(1)}/10</div>
                  </div>
                </div>
                {Array.isArray(report.strengths) && (
                  <div>
                    <div className="text-xs text-ink-500 mb-1">亮点</div>
                    <ul className="text-sm space-y-1">
                      {report.strengths.map((s: string, i: number) => <li key={i}>✓ {s}</li>)}
                    </ul>
                  </div>
                )}
                {Array.isArray(report.improvements) && (
                  <div>
                    <div className="text-xs text-ink-500 mb-1">改进点</div>
                    <ul className="text-sm space-y-1">
                      {report.improvements.map((s: string, i: number) => <li key={i}>→ {s}</li>)}
                    </ul>
                  </div>
                )}
                {Array.isArray(report.resources) && (
                  <div>
                    <div className="text-xs text-ink-500 mb-1">推荐学习</div>
                    <ul className="text-sm space-y-1">
                      {report.resources.map((s: string, i: number) => <li key={i}>📚 {s}</li>)}
                    </ul>
                  </div>
                )}
              </>
            )}
          </div>
        )}

        {transcript.length > 0 && !finished && (
          <div className="flex gap-2 pt-2">
            <input
              value={input}
              onChange={e => setInput(e.target.value)}
              onKeyDown={e => { if (e.key === 'Enter') send(); }}
              placeholder="输入你的回答"
              className="flex-1 px-4 py-2 border border-ink-200 rounded-md focus:outline-none focus:ring-2 focus:ring-accent-500/30 focus:border-accent-500"
            />
            <button onClick={send} disabled={loading || !input.trim()}
              className="px-4 py-2 bg-accent-500 hover:bg-accent-600 disabled:bg-ink-200 text-white rounded-md text-sm">
              {loading ? '…' : '发送'}
            </button>
          </div>
        )}
      </div>
    </div>
  );
}