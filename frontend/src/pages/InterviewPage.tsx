import { useEffect, useRef, useState } from 'react';
import { api, toUserMessage, type InterviewCompletionStatus, type InterviewHistoryItem, type InterviewReport, type InterviewTurnJob } from '../lib/api';

interface Transcript { speaker: 'ai' | 'me'; content: string; }
const STORAGE_KEY = 'tutor_active_interview_session';
const TURN_STORAGE_KEY = 'tutor_active_interview_turn';
const RETRY_STORAGE_KEY = 'tutor_active_interview_retry';
const MIN_DURATION_MINUTES = 15;
const MAX_DURATION_MINUTES = 120;

function requestId(): string {
  return typeof crypto?.randomUUID === 'function'
    ? crypto.randomUUID()
    : `iv-answer-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

export default function InterviewPage() {
  const [sessionId, setSessionId] = useState<string | null>(() => sessionStorage.getItem(STORAGE_KEY));
  const [role, setRole] = useState('NLP 算法工程师');
  const [jobDescription, setJobDescription] = useState('');
  const [interviewType, setInterviewType] = useState<'technical' | 'project' | 'behavioral' | 'system_design'>('technical');
  const [difficulty, setDifficulty] = useState<'JUNIOR' | 'MID' | 'SENIOR'>('MID');
  const [durationMinutes, setDurationMinutes] = useState(45);
  const [transcript, setTranscript] = useState<Transcript[]>([]);
  const [input, setInput] = useState('');
  const [loading, setLoading] = useState(false);
  const [finished, setFinished] = useState(false);
  const [report, setReport] = useState<InterviewReport | null>(null);
  const [completion, setCompletion] = useState<InterviewCompletionStatus | null>(null);
  const [error, setError] = useState('');
  const [retry, setRetry] = useState<{ text: string; requestId: string; jobId?: string } | null>(() => {
    try { return JSON.parse(sessionStorage.getItem(RETRY_STORAGE_KEY) ?? 'null') as { text: string; requestId: string; jobId?: string } | null; }
    catch { return null; }
  });
  const [turn, setTurn] = useState<InterviewTurnJob | null>(() => {
    try { return JSON.parse(sessionStorage.getItem(TURN_STORAGE_KEY) ?? 'null') as InterviewTurnJob | null; }
    catch { return null; }
  });
  const [history, setHistory] = useState<InterviewHistoryItem[]>([]);
  const [deadlineAt, setDeadlineAt] = useState<string | null>(null);
  const [remainingSeconds, setRemainingSeconds] = useState<number | null>(null);
  const [feedbackReason, setFeedbackReason] = useState('');
  const [feedbackSent, setFeedbackSent] = useState<'accurate' | 'inaccurate' | null>(null);
  const [historyReport, setHistoryReport] = useState<{ item: InterviewHistoryItem; report: InterviewReport } | null>(null);
  const [reportLoading, setReportLoading] = useState(false);
  const [reportError, setReportError] = useState('');
  const reportRequestRef = useRef(0);

  function loadHistory() {
    api.getInterviewHistory().then(setHistory).catch(() => { /* History is supplementary to the active interview. */ });
  }

  async function loadReport(id: string) {
    const request = ++reportRequestRef.current;
    setReportLoading(true);
    setReportError('');
    try {
      const nextReport = await api.getReport(id);
      if (request === reportRequestRef.current) setReport(nextReport);
    } catch (err) {
      if (request === reportRequestRef.current) setReportError(toUserMessage(err, '复盘报告暂时无法加载，请稍后重试。'));
    } finally {
      if (request === reportRequestRef.current) setReportLoading(false);
    }
  }

  useEffect(() => { loadHistory(); }, []);

  useEffect(() => {
    if (!sessionId || transcript.length > 0) return;
    let active = true;
    setLoading(true);
    api.getInterviewSession(sessionId).then(async session => {
      if (!active) return;
      setRole(current => session.targetRole || current);
      setTranscript(session.transcript);
      setDeadlineAt(session.deadlineAt);
      if (session.status !== 'IN_PROGRESS') {
        setFinished(true);
        void loadReport(sessionId);
      }
    }).catch(err => {
      if (!active) return;
      sessionStorage.removeItem(STORAGE_KEY);
      setSessionId(null);
      setError(toUserMessage(err, '无法恢复上次面试，请重新开始。'));
    }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [sessionId, transcript.length]);

  useEffect(() => {
    if (!deadlineAt || finished) { setRemainingSeconds(null); return; }
    const update = () => setRemainingSeconds(Math.max(0, Math.ceil((new Date(deadlineAt).getTime() - Date.now()) / 1000)));
    update();
    const timer = window.setInterval(update, 1000);
    return () => window.clearInterval(timer);
  }, [deadlineAt, finished]);

  useEffect(() => {
    if (!finished || !sessionId) { setCompletion(null); return; }
    let active = true;
    let timer: number | undefined;
    const poll = async () => {
      try {
        const status = await api.getInterviewCompletion(sessionId);
        if (!active) return;
        setCompletion(status);
        if (status.status === 'queued' || status.status === 'running') {
          timer = window.setTimeout(() => { void poll(); }, 1500);
        }
      } catch {
        // 即使下游完成状态暂不可用，报告仍然可以访问。
      }
    };
    void poll();
    return () => { active = false; if (timer !== undefined) window.clearTimeout(timer); };
  }, [finished, sessionId]);

  useEffect(() => {
    if (turn) sessionStorage.setItem(TURN_STORAGE_KEY, JSON.stringify(turn));
    else sessionStorage.removeItem(TURN_STORAGE_KEY);
  }, [turn]);

  useEffect(() => {
    if (retry) sessionStorage.setItem(RETRY_STORAGE_KEY, JSON.stringify(retry));
    else sessionStorage.removeItem(RETRY_STORAGE_KEY);
  }, [retry]);

  useEffect(() => {
    if (!sessionId || !turn || (turn.status !== 'PENDING' && turn.status !== 'PROCESSING' && turn.status !== 'RETRYABLE_FAILED')) return;
    let active = true;
    const timer = window.setTimeout(async () => {
      try {
        const next = await api.getInterviewTurn(sessionId, turn.id);
        if (!active) return;
        setTurn(next);
        if (next.status === 'COMPLETED' && next.responseMessage) {
          setTranscript(t => [...t, { speaker: 'ai', content: next.responseMessage! }]);
          setRetry(null);
          setTurn(null);
          if (next.responseStatus === 'COMPLETED') {
            setFinished(true);
            sessionStorage.removeItem(STORAGE_KEY);
            loadHistory();
            void loadReport(sessionId);
          }
        } else if (next.status === 'FAILED') {
          setRetry(current => current ? { ...current, jobId: next.id } : current);
          setInput(retry?.text ?? '');
          setTurn(null);
          setError('评分暂时不可用，答案已保留，可重新提交。');
        }
      } catch (err) {
        if (active) setError(toUserMessage(err, '评分状态暂时无法获取，请稍后重试。'));
      }
    }, 900);
    return () => { active = false; window.clearTimeout(timer); };
  }, [sessionId, turn, retry]);

  async function start() {
    if (!isValidDuration(durationMinutes)) {
      setError(`面试时长需为 ${MIN_DURATION_MINUTES}–${MAX_DURATION_MINUTES} 分钟的整数。`);
      return;
    }
    setLoading(true);
    setError('');
    reportRequestRef.current += 1;
    setReportLoading(false);
    setReportError('');
    try {
      const r = await api.openInterview({ targetRole: role, jobDescription, interviewType, difficulty, durationMinutes });
      sessionStorage.setItem(STORAGE_KEY, r.sessionId);
      setSessionId(r.sessionId);
      setTranscript([{ speaker: 'ai', content: r.message }]);
      setFinished(false);
      setReport(null);
      setCompletion(null);
      setRetry(null);
      setHistoryReport(null);
      setFeedbackSent(null);
      setFeedbackReason('');
      setDeadlineAt(new Date(Date.now() + durationMinutes * 60_000).toISOString());
      void api.getInterviewSession(r.sessionId).then(session => setDeadlineAt(session.deadlineAt)).catch(() => { /* The local estimate keeps the timer available. */ });
    } catch (err) {
      setError(toUserMessage(err, '创建面试失败，请稍后重试。'));
    } finally { setLoading(false); }
  }

  async function startRetest(sourceSessionId: string) {
    setLoading(true);
    setError('');
    reportRequestRef.current += 1;
    setReportLoading(false);
    setReportError('');
    try {
      const r = await api.retestInterview(sourceSessionId);
      sessionStorage.setItem(STORAGE_KEY, r.sessionId);
      setSessionId(r.sessionId);
      setTranscript([{ speaker: 'ai', content: r.message }]);
      setFinished(false);
      setReport(null);
      setCompletion(null);
      setRetry(null);
      setHistoryReport(null);
      setFeedbackSent(null);
      setFeedbackReason('');
      void api.getInterviewSession(r.sessionId).then(session => setDeadlineAt(session.deadlineAt)).catch(() => { /* The server still enforces its deadline. */ });
    } catch (err) {
      setError(toUserMessage(err, '创建复测失败，请稍后重试。'));
    } finally { setLoading(false); }
  }

  async function viewHistoryReport(item: InterviewHistoryItem) {
    if (loading) return;
    setLoading(true);
    setError('');
    try {
      setHistoryReport({ item, report: await api.getReport(item.sessionId) });
    } catch (err) {
      setError(toUserMessage(err, '复盘报告暂时无法加载，请稍后重试。'));
    } finally { setLoading(false); }
  }

  async function send() {
    const text = input.trim();
    if (!text || loading || turn || finished || !sessionId) return;
    const answerId = retry?.text === text ? retry.requestId : requestId();
    setRetry({ text, requestId: answerId });
    setInput('');
    setTranscript(t => [...t, { speaker: 'me', content: text }]);
    setLoading(true);
    setError('');
    try {
      const job = await api.answerInterview(sessionId, text, answerId);
      setRetry({ text, requestId: answerId, jobId: job.id });
      setTurn(job);
    } catch (err) {
      setTranscript(t => t.filter((_, index) => index !== t.length - 1));
      setInput(text);
      setRetry({ text, requestId: answerId });
      setError(toUserMessage(err, '提交回答失败，内容已保留，可重新提交。'));
    } finally { setLoading(false); }
  }

  async function retryScoring() {
    if (!sessionId || !retry?.jobId || loading || turn || finished) return;
    setLoading(true);
    setError('');
    try {
      setInput('');
      setTurn(await api.retryInterviewTurn(sessionId, retry.jobId));
    } catch (err) {
      setError(toUserMessage(err, '重新评分失败，答案仍已保留。'));
    } finally { setLoading(false); }
  }

  function resetInterview() {
    reportRequestRef.current += 1;
    sessionStorage.removeItem(STORAGE_KEY);
    sessionStorage.removeItem(TURN_STORAGE_KEY);
    sessionStorage.removeItem(RETRY_STORAGE_KEY);
    setSessionId(null);
    setTranscript([]);
    setInput('');
    setFinished(false);
    setReport(null);
    setReportLoading(false);
    setReportError('');
    setCompletion(null);
    setError('');
    setRetry(null);
    setTurn(null);
    setDeadlineAt(null);
    setFeedbackSent(null);
    setFeedbackReason('');
    setHistoryReport(null);
  }

  async function submitFeedback(rating: 'accurate' | 'inaccurate') {
    if (!sessionId || loading) return;
    setLoading(true);
    try {
      await api.submitInterviewFeedback(sessionId, rating, feedbackReason);
      setFeedbackSent(rating);
    } catch (err) {
      setError(toUserMessage(err, '反馈提交失败，请稍后重试。'));
    } finally { setLoading(false); }
  }

  async function cancel() {
    if (!sessionId || loading || finished) return;
    setLoading(true);
    setError('');
    try {
      await api.cancelInterview(sessionId);
      setFinished(true);
      sessionStorage.removeItem(STORAGE_KEY);
      loadHistory();
      void loadReport(sessionId);
    } catch (err) {
      setError(toUserMessage(err, '结束面试失败，请稍后重试。'));
    } finally { setLoading(false); }
  }

  return (
    <div className="h-full overflow-y-auto px-4 py-4 sm:px-6 sm:py-6">
      <div className="max-w-3xl mx-auto space-y-5">
        <div>
          <h1 className="text-2xl font-semibold text-ink-900">模拟面试</h1>
          <p className="text-sm text-ink-500 mt-1">基于目标岗位技能出题，AI 评分、追问和复盘。刷新页面后可恢复进行中的面试。</p>
        </div>

        {error && <div role="alert" className="flex flex-wrap items-center justify-between gap-3 rounded-md border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700"><span>{error}</span>
          {retry?.jobId && !turn && !finished && <button onClick={() => void retryScoring()} disabled={loading} className="min-h-9 rounded-md border border-red-300 px-3 text-xs font-medium hover:bg-red-100 disabled:opacity-60">重新评分</button>}
        </div>}

        {!sessionId && (
          <div className="card p-6 space-y-3">
            <label htmlFor="interview-role" className="block text-sm font-medium text-ink-700">目标岗位</label>
            <input
              id="interview-role"
              value={role}
              onChange={e => setRole(e.target.value)}
              required
              aria-describedby="interview-role-hint"
              className="w-full px-3 py-2 text-base sm:text-sm border border-ink-200 rounded-md focus:outline-none focus:ring-2 focus:ring-accent-500/30 focus:border-accent-500"
              placeholder="例如: NLP 算法工程师 / 后端开发"
            />
            <span id="interview-role-hint" className="text-xs text-ink-500">面试官会根据岗位名称调整问题范围。</span>
            <label htmlFor="interview-type" className="block text-sm font-medium text-ink-700">面试类型
              <select id="interview-type" value={interviewType} onChange={e => setInterviewType(e.target.value as typeof interviewType)}
                className="mt-1 w-full px-3 py-2 text-base sm:text-sm border border-ink-200 rounded-md bg-white focus:outline-none focus:ring-2 focus:ring-accent-500/30 focus:border-accent-500">
                <option value="technical">技术面试</option><option value="project">项目深挖</option>
                <option value="system_design">系统设计</option><option value="behavioral">行为面试</option>
              </select>
            </label>
            <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
              <label htmlFor="interview-difficulty" className="text-sm font-medium text-ink-700">难度
                <select id="interview-difficulty" value={difficulty} onChange={e => setDifficulty(e.target.value as typeof difficulty)}
                  className="mt-1 w-full px-3 py-2 text-base sm:text-sm border border-ink-200 rounded-md bg-white focus:outline-none focus:ring-2 focus:ring-accent-500/30 focus:border-accent-500">
                  <option value="JUNIOR">初级</option><option value="MID">中级</option><option value="SENIOR">高级</option>
                </select>
              </label>
              <label htmlFor="interview-duration" className="text-sm font-medium text-ink-700">时长（分钟）
                <input id="interview-duration" type="number" min={MIN_DURATION_MINUTES} max={MAX_DURATION_MINUTES} step="1" inputMode="numeric" value={durationMinutes}
                  onChange={e => setDurationMinutes(Number.isFinite(e.currentTarget.valueAsNumber) ? Math.trunc(e.currentTarget.valueAsNumber) : 0)}
                  aria-invalid={!isValidDuration(durationMinutes)}
                  aria-describedby="interview-duration-hint"
                  className="mt-1 w-full px-3 py-2 text-base sm:text-sm border border-ink-200 rounded-md focus:outline-none focus:ring-2 focus:ring-accent-500/30 focus:border-accent-500" />
                <span id="interview-duration-hint" className="mt-1 block text-xs font-normal text-ink-500">请输入 15–120 分钟的整数。</span>
              </label>
            </div>
            <label htmlFor="interview-job-description" className="block text-sm font-medium text-ink-700">职位描述（可选）
              <textarea id="interview-job-description" value={jobDescription} onChange={e => setJobDescription(e.target.value)} rows={4}
                maxLength={12000} placeholder="粘贴岗位职责和任职要求，面试将优先围绕其中的技能与场景展开。"
                className="mt-1 w-full px-3 py-2 text-base sm:text-sm border border-ink-200 rounded-md resize-y focus:outline-none focus:ring-2 focus:ring-accent-500/30 focus:border-accent-500" />
            </label>
            <button onClick={start} disabled={loading || !role.trim() || !isValidDuration(durationMinutes)}
              className="min-h-11 px-4 py-2 bg-accent-500 hover:bg-accent-600 disabled:bg-ink-200 text-white rounded-md text-sm font-medium">
              {loading ? '准备中…' : '开始面试'}
            </button>
          </div>
        )}

        {!sessionId && history.length > 0 && (
          <section className="card p-6 space-y-3" aria-label="最近面试记录">
            <div className="flex items-baseline justify-between"><h2 className="text-base font-semibold text-ink-900">最近面试</h2>
              <span className="text-xs text-ink-500">最近 {Math.min(history.length, 5)} 场</span></div>
            <div className="space-y-2">
              {history.slice(0, 5).map(item => <div key={item.sessionId} className="flex flex-col gap-3 rounded-md border border-ink-100 px-3 py-3 text-sm sm:flex-row sm:items-center sm:justify-between">
                <div className="min-w-0"><div className="break-words font-medium text-ink-800">{item.targetRole || '通用面试'} · {typeLabel(item.interviewType)}</div>
                  <div className="text-xs text-ink-500 mt-0.5">{new Date(item.createdAt).toLocaleDateString()} · {levelLabel(item.difficulty)} · {item.totalQuestions} 题{item.retestOf ? ' · 复测' : ''}</div></div>
                <div className="flex flex-wrap items-center gap-3"><div className={`font-semibold ${item.status === 'COMPLETED' ? 'text-accent-600' : 'text-ink-500'}`}>
                  {item.status === 'COMPLETED' ? `${item.avgScore.toFixed(1)}/10` : '进行中'}</div>
                  {item.scoreDelta != null && <div className={`text-xs font-medium ${item.scoreDelta >= 0 ? 'text-emerald-600' : 'text-red-600'}`}>
                    {item.scoreDelta >= 0 ? '+' : ''}{item.scoreDelta.toFixed(1)}</div>}
                  {item.status !== 'IN_PROGRESS' && <button onClick={() => void viewHistoryReport(item)} disabled={loading}
                    className="min-h-11 px-1 text-xs font-medium text-ink-600 hover:text-ink-900 disabled:text-ink-400">复盘</button>}
                  {item.status === 'COMPLETED' && <button onClick={() => void startRetest(item.sessionId)} disabled={loading}
                    className="min-h-11 px-1 text-xs font-medium text-accent-600 hover:text-accent-700 disabled:text-ink-400">复测</button>}
                </div>
              </div>)}
            </div>
            {historyReport && <div className="rounded-md border border-ink-100 bg-ink-50 p-4 space-y-3">
              <div className="flex items-baseline justify-between"><h3 className="font-medium text-ink-900">{historyReport.item.targetRole || '通用面试'} · 复盘</h3>
                <button onClick={() => setHistoryReport(null)} className="text-xs text-ink-500 hover:text-ink-800">收起</button></div>
              <ReportSummary report={historyReport.report} />
            </div>}
          </section>
        )}

        {transcript.length > 0 && (
          <div className="space-y-3">
            {remainingSeconds != null && <div className={`text-sm font-medium ${remainingSeconds === 0 ? 'text-red-600' : 'text-ink-600'}`}>
              剩余时间：{formatRemaining(remainingSeconds)}{remainingSeconds === 0 ? '，请结束面试查看复盘。' : ''}
            </div>}
            <div role="log" aria-live="polite" aria-relevant="additions" aria-label="面试对话">
              {transcript.map((t, i) => (
                <div key={i} className={`flex ${t.speaker === 'me' ? 'justify-end' : 'justify-start'}`}>
                  <div className={`max-w-[90%] break-words rounded-2xl px-4 py-3 sm:max-w-[85%] ${
                    t.speaker === 'me' ? 'bg-accent-500 text-white' : 'bg-white border border-ink-100 text-ink-900 shadow-soft'
                  }`}>
                    <span className="sr-only">{t.speaker === 'me' ? '我的回答：' : '面试官：'}</span>
                    <div className="whitespace-pre-wrap text-sm">{t.content}</div>
                  </div>
                </div>
              ))}
              {(loading || turn) && (
                <div className="text-xs text-ink-500 flex items-center gap-2 pl-2" role="status" aria-live="polite">
                  <span className="inline-block w-2 h-2 bg-accent-500 rounded-full animate-pulse" />
                  {turn?.status === 'RETRYABLE_FAILED' ? '评分重试中…' : 'AI 评分中…'}
                </div>
              )}
            </div>
          </div>
        )}

        {finished && (
          <div className="card p-6 space-y-3 ring-1 ring-accent-100">
            <h2 className="text-lg font-semibold text-ink-900">复盘报告</h2>
            {reportLoading && <div className="rounded-md bg-ink-50 px-4 py-3 text-sm text-ink-700" role="status" aria-live="polite">正在加载复盘报告…</div>}
            {reportError && <div className="flex flex-col gap-2 rounded-md border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700 sm:flex-row sm:items-center sm:justify-between" role="alert">
              <span>{reportError}</span>
              <button onClick={() => void loadReport(sessionId!)} disabled={reportLoading}
                className="min-h-11 shrink-0 self-start rounded-md border border-red-300 px-3 text-xs font-medium hover:bg-red-100 disabled:opacity-60 sm:self-auto">重新加载报告</button>
            </div>}
            {report && <>
              {completion && <div className="rounded-md bg-ink-50 px-4 py-3 text-sm text-ink-700" role="status" aria-live="polite">
                学习闭环：{completion.status === 'queued' ? '等待处理' : completion.status === 'running' ? '正在生成学习证据' : completion.status === 'completed' ? '已完成' : '处理失败，可稍后重试'}
                {completion.status === 'failed' && completion.lastError ? `（${completion.lastError}）` : ''}
                {completion.evidenceStatus === 'completed' && completion.learningPlanStatus === 'failed' ? '；能力证据已保存，学习任务生成失败，系统将自动重试。' : ''}
              </div>}
              <div className="grid grid-cols-3 gap-3 text-sm sm:flex sm:items-center sm:gap-6">
                <div><div className="text-xs text-ink-500">题目数</div><div className="text-2xl font-semibold text-ink-900">{report.totalQuestions}</div></div>
                <div><div className="text-xs text-ink-500">平均分</div><div className="text-2xl font-semibold text-accent-600">{report.avgScore?.toFixed(1)}/10</div></div>
                <div><div className="text-xs text-ink-500">评分置信度</div><div className="text-2xl font-semibold text-ink-900">{Math.round((report.scoreConfidence ?? 0) * 100)}%</div></div>
              </div>
              {report.retestComparison && <div className="rounded-md bg-ink-50 px-4 py-3 text-sm text-ink-700 space-y-1">
                <div className="font-medium text-ink-900">复测变化</div>
                <div>同配置复测：原测 {report.retestComparison.baselineAvgScore.toFixed(1)}/10，
                  本次 {report.retestComparison.scoreDelta >= 0 ? '+' : ''}{report.retestComparison.scoreDelta.toFixed(1)} 分。</div>
                <div className="text-xs text-ink-500">题目经过排重，变化反映整体表现趋势，并非完全相同题目的等值比较。</div>
                <ReportList title="原测优先补强" items={report.retestComparison.originalFocusAreas} marker="→" />
              </div>}
              <ReportList title="亮点" items={report.strengths} marker="✓" />
              <ReportList title="改进点" items={report.improvements} marker="→" />
              <ReportList title="推荐学习" items={report.resources} marker="📚" />
              <div className="border-t border-ink-100 pt-3 text-sm">
                <div className="text-xs text-ink-500 mb-2">这份评分是否符合你的实际表现？</div>
                {feedbackSent ? <div className="text-emerald-700">感谢反馈，已记录用于后续评分校准。</div> : <div className="space-y-2">
                  <div className="flex gap-2"><button onClick={() => void submitFeedback('accurate')} disabled={loading}
                    className="rounded-md border border-ink-200 px-3 py-1.5 text-xs hover:bg-ink-50">基本准确</button>
                    <button onClick={() => void submitFeedback('inaccurate')} disabled={loading}
                    className="rounded-md border border-ink-200 px-3 py-1.5 text-xs hover:bg-ink-50">需要校准</button></div>
                  <label htmlFor="interview-feedback-reason" className="sr-only">评分反馈原因</label>
                  <textarea id="interview-feedback-reason" value={feedbackReason} onChange={e => setFeedbackReason(e.target.value)} maxLength={1000} rows={2}
                    placeholder="可选：哪些评分或建议不符合你的实际表现？" className="w-full rounded-md border border-ink-200 px-3 py-2 text-base sm:text-xs focus:outline-none focus:ring-2 focus:ring-accent-500/30 focus:border-accent-500" />
                </div>}
              </div>
            </>}
            <button onClick={resetInterview}
              className="min-h-11 px-4 py-2 border border-ink-200 hover:bg-ink-50 text-ink-700 rounded-md text-sm font-medium">
              开始新的面试
            </button>
          </div>
        )}

        {sessionId && transcript.length > 0 && !finished && (
          <div className="flex flex-col gap-2 pt-2 sm:flex-row">
            <label htmlFor="interview-answer" className="sr-only">你的回答</label>
            <input id="interview-answer" value={input} onChange={e => setInput(e.target.value)}
              onKeyDown={e => { if (e.key === 'Enter') void send(); }} placeholder="输入你的回答"
              className="min-w-0 flex-1 px-4 py-2 text-base sm:text-sm border border-ink-200 rounded-md focus:outline-none focus:ring-2 focus:ring-accent-500/30 focus:border-accent-500" />
            <button onClick={() => void send()} disabled={loading || !!turn || !input.trim() || remainingSeconds === 0}
              className="min-h-11 shrink-0 px-4 py-2 bg-accent-500 hover:bg-accent-600 disabled:bg-ink-200 text-white rounded-md text-sm">
              {loading || turn ? '…' : '发送'}
            </button>
            <button onClick={() => void cancel()} disabled={loading}
              className="min-h-11 shrink-0 px-3 py-2 border border-ink-200 hover:bg-ink-50 disabled:bg-ink-100 text-ink-700 rounded-md text-sm">结束面试</button>
          </div>
        )}
      </div>
    </div>
  );
}

function ReportList({ title, items, marker }: { title: string; items?: string[]; marker: string }) {
  if (!Array.isArray(items) || items.length === 0) return null;
  return <div><div className="text-xs text-ink-500 mb-1">{title}</div><ul className="text-sm space-y-1">
    {items.map((item, index) => <li key={index}>{marker} {item}</li>)}
  </ul></div>;
}

function ReportSummary({ report }: { report: InterviewReport }) {
  return <>
    <div className="flex gap-5 text-sm"><span>题目 {report.totalQuestions}</span><span>平均分 {report.avgScore?.toFixed(1)}/10</span><span>置信度 {Math.round((report.scoreConfidence ?? 0) * 100)}%</span></div>
    {report.retestComparison && <div className="text-sm text-ink-700">复测变化：原测 {report.retestComparison.baselineAvgScore.toFixed(1)}/10，
      本次 {report.retestComparison.scoreDelta >= 0 ? '+' : ''}{report.retestComparison.scoreDelta.toFixed(1)} 分。</div>}
    <ReportList title="亮点" items={report.strengths} marker="✓" />
    <ReportList title="改进点" items={report.improvements} marker="→" />
    <ReportList title="推荐学习" items={report.resources} marker="📚" />
  </>;
}

function typeLabel(type: string) {
  return ({ technical: '技术面试', project: '项目深挖', system_design: '系统设计', behavioral: '行为面试' } as Record<string, string>)[type] ?? type;
}

function levelLabel(level: string) {
  return ({ JUNIOR: '初级', MID: '中级', SENIOR: '高级' } as Record<string, string>)[level] ?? level;
}

function formatRemaining(seconds: number) {
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
}

function isValidDuration(value: number) {
  return Number.isInteger(value) && value >= MIN_DURATION_MINUTES && value <= MAX_DURATION_MINUTES;
}
