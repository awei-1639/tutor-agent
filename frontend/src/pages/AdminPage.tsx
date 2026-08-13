import { useCallback, useEffect, useState } from 'react';
import { api, AdminAudit, AdminOverview, AdminUser, InterviewAnnotationQueueItem, InterviewScoreReplayResult, toUserMessage } from '../lib/api';

const statusNames: Record<string, string> = {
  active: '正常',
  disabled: '已禁用',
  deleted: '已软删除',
};

const evalStatusNames: Record<string, string> = {
  running: '运行中',
  completed: '已完成',
  failed: '失败',
};

function formatTime(value?: string) {
  if (!value) return '—';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString('zh-CN', { hour12: false });
}

function statusClass(status: string) {
  if (status === 'active' || status === 'completed') return 'bg-emerald-50 text-emerald-700';
  if (status === 'running') return 'bg-amber-50 text-amber-700';
  return 'bg-rose-50 text-rose-700';
}

export default function AdminPage() {
  const [overview, setOverview] = useState<AdminOverview | null>(null);
  const [users, setUsers] = useState<AdminUser[]>([]);
  const [audits, setAudits] = useState<AdminAudit[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [status, setStatus] = useState('all');
  const [search, setSearch] = useState('');
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');
  const [busyId, setBusyId] = useState<number | null>(null);
  const [annotationQueue, setAnnotationQueue] = useState<InterviewAnnotationQueueItem[]>([]);
  const [annotationDrafts, setAnnotationDrafts] = useState<Record<number, { score: string; rationale: string }>>({});
  const [annotationBusyId, setAnnotationBusyId] = useState<number | null>(null);
  const [replay, setReplay] = useState<InterviewScoreReplayResult | null>(null);
  const [replayBusy, setReplayBusy] = useState(false);

  const reload = useCallback(async () => {
    setLoading(true);
    setError('');
    try {
      const [summary, userPage, audit, queue] = await Promise.all([
        api.adminOverview(),
        api.adminUsers({ search, status, page, size: 12 }),
        api.adminAudit(12),
        api.adminInterviewAnnotationQueue(20, 2),
      ]);
      setOverview(summary);
      setUsers(userPage.items);
      setTotal(userPage.total);
      setAudits(audit);
      setAnnotationQueue(queue);
      setAnnotationDrafts(prev => {
        const next = { ...prev };
        queue.forEach(item => {
          if (!next[item.questionId]) next[item.questionId] = { score: '', rationale: '' };
        });
        return next;
      });
    } catch (e) {
      setError(toUserMessage(e, '管理数据暂时无法加载，请稍后重试。'));
    } finally {
      setLoading(false);
    }
  }, [page, search, status]);

  useEffect(() => { void reload(); }, [reload]);

  async function action(user: AdminUser, action: 'disable' | 'restore' | 'soft-delete') {
    const labels = { disable: '禁用', restore: '恢复', 'soft-delete': '软删除' };
    if (action === 'soft-delete' && !window.confirm(`确认软删除用户「${user.name || user.email || user.id}」吗？`)) return;
    setBusyId(user.id);
    try {
      await api.adminUserAction(user.id, action);
      await reload();
    } catch (e) {
      setError(`${labels[action]}失败：${toUserMessage(e)}`);
    } finally {
      setBusyId(null);
    }
  }

  function updateAnnotationDraft(questionId: number, patch: Partial<{ score: string; rationale: string }>) {
    const current = annotationDrafts[questionId] ?? { score: '', rationale: '' };
    setAnnotationDrafts(prev => ({
      ...prev,
      [questionId]: { ...current, ...patch },
    }));
  }

  async function saveAnnotation(item: InterviewAnnotationQueueItem) {
    const draft = annotationDrafts[item.questionId] ?? { score: '', rationale: '' };
    const score = Number(draft.score);
    if (!Number.isInteger(score) || score < 0 || score > 10) {
      setError('人工评分必须是 0 到 10 的整数。');
      return;
    }
    setAnnotationBusyId(item.questionId);
    setError('');
    try {
      await api.adminUpsertInterviewAnnotation(item.questionId, score, draft.rationale);
      await reload();
    } catch (e) {
      setError(`提交人工评分失败：${toUserMessage(e)}`);
    } finally {
      setAnnotationBusyId(null);
    }
  }

  async function runAnnotationReplay() {
    setReplayBusy(true);
    setError('');
    try {
      setReplay(await api.adminReplayInterviewAnnotations('human-gold-current', 2));
      await reload();
    } catch (e) {
      setError(`运行评分 replay 失败：${toUserMessage(e, '当前还没有足够的双人标注样本。')}`);
    } finally {
      setReplayBusy(false);
    }
  }

  const pageCount = Math.max(1, Math.ceil(total / 12));

  return (
    <div className="h-full overflow-y-auto bg-[#f7f8fa]">
      <div className="mx-auto max-w-[1400px] px-8 py-8">
        <div className="flex items-start justify-between gap-4 mb-7">
          <div>
            <div className="text-xs uppercase tracking-[.16em] text-ink-400">Operations console</div>
            <h1 className="mt-2 text-2xl font-semibold tracking-tight text-ink-900">管理端</h1>
            <p className="mt-2 text-sm text-ink-500">集中查看用户状态、RAG 评测运行和关键操作审计。</p>
          </div>
          <button onClick={() => void reload()} className="rounded-lg border border-ink-200 bg-white px-3 py-2 text-sm text-ink-700 hover:border-ink-300">刷新数据</button>
        </div>

        {error && <div className="mb-5 rounded-lg border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700">{error}</div>}
        {loading && !overview ? <Loading /> : overview ? (
          <>
            <div className="grid grid-cols-2 gap-3 lg:grid-cols-5">
              <Metric label="用户总数" value={overview.users.total} />
              <Metric label="正常用户" value={overview.users.active} tone="green" />
              <Metric label="已禁用" value={overview.users.disabled} tone="amber" />
              <Metric label="已软删除" value={overview.users.deleted} tone="rose" />
              <Metric label="管理员" value={overview.users.admins} />
            </div>

            <div className="mt-5 grid gap-5 xl:grid-cols-[1.2fr_.8fr]">
              <section className="rounded-xl border border-ink-200 bg-white">
                <div className="flex items-center justify-between border-b border-ink-100 px-5 py-4">
                  <div><h2 className="text-sm font-semibold text-ink-900">用户管理</h2><p className="mt-1 text-xs text-ink-500">变更账号状态会记录到审计日志。</p></div>
                  <span className="text-xs text-ink-400">共 {total} 个</span>
                </div>
                <div className="flex flex-wrap gap-2 border-b border-ink-100 px-5 py-3">
                  <input value={search} onChange={e => { setPage(0); setSearch(e.target.value); }} placeholder="搜索邮箱或姓名" className="min-w-[220px] flex-1 rounded-lg border border-ink-200 px-3 py-2 text-sm outline-none focus:border-accent-500" />
                  <select value={status} onChange={e => { setPage(0); setStatus(e.target.value); }} className="rounded-lg border border-ink-200 bg-white px-3 py-2 text-sm text-ink-700">
                    <option value="all">全部状态</option><option value="active">正常</option><option value="disabled">已禁用</option><option value="deleted">已软删除</option>
                  </select>
                </div>
                <div className="overflow-x-auto">
                  <table className="w-full text-left text-sm">
                    <thead className="bg-ink-50 text-xs text-ink-500"><tr><th className="px-5 py-3 font-medium">用户</th><th className="px-3 py-3 font-medium">角色</th><th className="px-3 py-3 font-medium">状态</th><th className="px-5 py-3 text-right font-medium">操作</th></tr></thead>
                    <tbody className="divide-y divide-ink-100">
                      {users.map(user => <UserRow key={user.id} user={user} busy={busyId === user.id} onAction={action} />)}
                      {!users.length && <tr><td colSpan={4} className="px-5 py-10 text-center text-sm text-ink-400">没有匹配的用户</td></tr>}
                    </tbody>
                  </table>
                </div>
                <div className="flex items-center justify-between border-t border-ink-100 px-5 py-3 text-xs text-ink-500">
                  <span>第 {page + 1} / {pageCount} 页</span>
                  <div className="flex gap-2"><button disabled={page === 0} onClick={() => setPage(p => p - 1)} className="rounded border border-ink-200 px-2.5 py-1.5 disabled:cursor-not-allowed disabled:opacity-40">上一页</button><button disabled={page + 1 >= pageCount} onClick={() => setPage(p => p + 1)} className="rounded border border-ink-200 px-2.5 py-1.5 disabled:cursor-not-allowed disabled:opacity-40">下一页</button></div>
                </div>
              </section>

              <div className="space-y-5">
                <section className="rounded-xl border border-ink-200 bg-white">
                  <div className="border-b border-ink-100 px-5 py-4"><h2 className="text-sm font-semibold text-ink-900">系统状态</h2></div>
                  <div className="grid grid-cols-2 gap-3 p-5"><StatusCard label="数据库" value={overview.checks.database} /><StatusCard label="RAG 评测" value={overview.checks.evaluation} /></div>
                </section>
                <section className="rounded-xl border border-ink-200 bg-white">
                  <div className="border-b border-ink-100 px-5 py-4"><h2 className="text-sm font-semibold text-ink-900">最近评测</h2></div>
                  <div className="divide-y divide-ink-100">
                    {overview.recentEvalRuns.map(run => <div key={run.id} className="flex items-center justify-between gap-3 px-5 py-3"><div><div className="text-sm font-medium text-ink-800">RAG Eval · #{run.id}</div><div className="mt-1 text-xs text-ink-400">{run.datasetVersion || '默认数据集'} · {run.totalCases ?? '—'} 条用例</div></div><span className={`rounded-full px-2 py-1 text-[11px] ${statusClass(run.status)}`}>{evalStatusNames[run.status] || run.status}</span></div>)}
                    {!overview.recentEvalRuns.length && <div className="px-5 py-8 text-center text-xs text-ink-400">暂无评测记录</div>}
                  </div>
                </section>
                <section className="rounded-xl border border-ink-200 bg-white">
                  <div className="border-b border-ink-100 px-5 py-4"><h2 className="text-sm font-semibold text-ink-900">面试评分校准</h2><p className="mt-1 text-xs text-ink-500">仅展示聚合指标与用户主动提交的校准意见。</p></div>
                  <div className="grid grid-cols-3 gap-3 p-5"><MiniMetric label="已收卷" value={overview.interviewQuality.finalizedSessions} /><MiniMetric label="校准反馈" value={overview.interviewQuality.totalFeedback} /><MiniMetric label="待校准率" value={`${Math.round(overview.interviewQuality.inaccurateRate * 100)}%`} /></div>
                  <div className="border-t border-ink-100 px-5 py-3 text-xs text-ink-500">平均评分置信度：{Math.round(overview.interviewQuality.avgConfidence * 100)}%</div>
                  <div className="divide-y divide-ink-100">{overview.interviewQuality.recentCalibration.map((item, index) => <div key={`${item.createdAt}-${index}`} className="px-5 py-3"><div className="text-xs text-ink-700">{item.reason}</div><div className="mt-1 text-[11px] text-ink-400">{formatTime(item.createdAt)}</div></div>)}{!overview.interviewQuality.recentCalibration.length && <div className="px-5 py-6 text-center text-xs text-ink-400">暂无待校准样本</div>}</div>
                </section>
                <InterviewCalibrationPanel
                  queue={annotationQueue}
                  drafts={annotationDrafts}
                  busyId={annotationBusyId}
                  replay={replay}
                  replayBusy={replayBusy}
                  onDraft={updateAnnotationDraft}
                  onSave={saveAnnotation}
                  onReplay={() => void runAnnotationReplay()}
                />
                <AuditPanel audits={audits} />
              </div>
            </div>
          </>
        ) : null}
      </div>
    </div>
  );
}

function UserRow({ user, busy, onAction }: { user: AdminUser; busy: boolean; onAction: (user: AdminUser, action: 'disable' | 'restore' | 'soft-delete') => void }) {
  return <tr className="hover:bg-ink-50/50">
    <td className="px-5 py-3"><div className="font-medium text-ink-800">{user.name || '未命名用户'}</div><div className="mt-0.5 text-xs text-ink-400">{user.email || `用户 ID ${user.id}`} · #{user.id}</div></td>
    <td className="px-3 py-3"><span className="text-xs text-ink-600">{user.role === 'ADMIN' ? '管理员' : '用户'}</span></td>
    <td className="px-3 py-3"><span className={`rounded-full px-2 py-1 text-[11px] ${statusClass(user.status)}`}>{statusNames[user.status] || user.status}</span></td>
    <td className="px-5 py-3 text-right"><div className="flex justify-end gap-2">{user.status === 'active' && <button disabled={busy} onClick={() => onAction(user, 'disable')} className="text-xs text-amber-700 hover:underline disabled:opacity-40">禁用</button>}{user.status !== 'active' && <button disabled={busy} onClick={() => onAction(user, 'restore')} className="text-xs text-emerald-700 hover:underline disabled:opacity-40">恢复</button>}{user.status !== 'deleted' && <button disabled={busy} onClick={() => onAction(user, 'soft-delete')} className="text-xs text-rose-700 hover:underline disabled:opacity-40">软删除</button>}</div></td>
  </tr>;
}

function Metric({ label, value, tone = 'default' }: { label: string; value: number; tone?: string }) {
  const colors: Record<string, string> = { default: 'text-ink-900', green: 'text-emerald-700', amber: 'text-amber-700', rose: 'text-rose-700' };
  return <div className="rounded-xl border border-ink-200 bg-white px-5 py-4"><div className="text-xs text-ink-500">{label}</div><div className={`mt-2 text-2xl font-semibold ${colors[tone] || colors.default}`}>{value}</div></div>;
}

function StatusCard({ label, value }: { label: string; value: string }) {
  return <div className="rounded-lg bg-ink-50 px-4 py-3"><div className="text-xs text-ink-500">{label}</div><div className="mt-1 text-sm font-semibold text-emerald-700">● {value}</div></div>;
}

function MiniMetric({ label, value }: { label: string; value: string | number }) {
  return <div className="rounded-lg bg-ink-50 px-3 py-2"><div className="text-[11px] text-ink-500">{label}</div><div className="mt-1 text-lg font-semibold text-ink-900">{value}</div></div>;
}

function AuditPanel({ audits }: { audits: AdminAudit[] }) {
  return <section className="rounded-xl border border-ink-200 bg-white"><div className="border-b border-ink-100 px-5 py-4"><h2 className="text-sm font-semibold text-ink-900">操作审计</h2></div><div className="divide-y divide-ink-100">{audits.slice(0, 5).map(item => <div key={item.id} className="px-5 py-3"><div className="text-xs font-medium text-ink-700">{auditName(item.action)}</div><div className="mt-1 text-[11px] text-ink-400">管理员 {item.adminName || item.adminUserId || '—'} · 目标 {item.targetName || item.targetUserId || '—'} · {formatTime(item.createdAt)}</div></div>)}{!audits.length && <div className="px-5 py-8 text-center text-xs text-ink-400">暂无操作记录</div>}</div></section>;
}

function InterviewCalibrationPanel({
  queue,
  drafts,
  busyId,
  replay,
  replayBusy,
  onDraft,
  onSave,
  onReplay,
}: {
  queue: InterviewAnnotationQueueItem[];
  drafts: Record<number, { score: string; rationale: string }>;
  busyId: number | null;
  replay: InterviewScoreReplayResult | null;
  replayBusy: boolean;
  onDraft: (questionId: number, patch: Partial<{ score: string; rationale: string }>) => void;
  onSave: (item: InterviewAnnotationQueueItem) => void;
  onReplay: () => void;
}) {
  return <section className="rounded-xl border border-ink-200 bg-white">
    <div className="flex items-start justify-between gap-3 border-b border-ink-100 px-5 py-4">
      <div><h2 className="text-sm font-semibold text-ink-900">双人标注队列</h2><p className="mt-1 text-xs text-ink-500">仅管理员可见；默认盲评，回答已做邮箱/手机号脱敏，不返回用户或会话身份。</p></div>
      <button disabled={replayBusy} onClick={onReplay} className="shrink-0 rounded-lg border border-accent-200 bg-accent-50 px-3 py-2 text-xs font-medium text-accent-700 hover:bg-accent-100 disabled:opacity-50">{replayBusy ? '评测中…' : '运行双人 replay'}</button>
    </div>
    {replay && <div className={`border-b px-5 py-3 text-xs ${replay.metrics.releaseEligible ? 'border-emerald-200 bg-emerald-50 text-emerald-800' : 'border-rose-200 bg-rose-50 text-rose-800'}`}>
      <div className="font-medium">{replay.metrics.releaseEligible ? '发布门禁通过' : '发布门禁阻断'} · {replay.metrics.n} 条样本 · MAE {replay.metrics.mae.toFixed(2)} · 三级一致率 {Math.round(replay.metrics.gradeAgreement * 100)}%</div>
      <div className="mt-1">双人覆盖率 {Math.round(replay.metrics.doubleLabelCoverage * 100)}% · 评审分歧率 {Math.round(replay.metrics.reviewerDisagreementRate * 100)}% · 高置信大误差率 {Math.round(replay.metrics.highConfidenceErrorRate * 100)}%</div>
    </div>}
    <div className="divide-y divide-ink-100">
      {queue.map(item => {
        const draft = drafts[item.questionId] ?? { score: '', rationale: '' };
        return <div key={item.questionId} className="space-y-3 px-5 py-4">
          <div className="flex flex-wrap items-center justify-between gap-2 text-xs text-ink-500"><span>样本 #{item.questionId} · 已有 {item.reviewerCount}/2 名评审</span>{typeof item.modelScore === 'number' && typeof item.modelConfidence === 'number' ? <span>模型评分 {item.modelScore}/10 · 置信度 {Math.round(item.modelConfidence * 100)}%</span> : <span className="rounded-full bg-ink-100 px-2 py-1 text-ink-600">盲评：模型信号暂不展示</span>}</div>
          {item.feedbackRating === 'inaccurate' && <div className="rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-xs text-amber-800">用户反馈评分可能不准确{item.feedbackReason ? `：${item.feedbackReason}` : ''}</div>}
          <div><div className="text-xs font-medium text-ink-700">问题</div><div className="mt-1 whitespace-pre-wrap text-sm text-ink-800">{item.prompt}</div></div>
          <div><div className="text-xs font-medium text-ink-700">候选人回答</div><div className="mt-1 max-h-36 overflow-y-auto whitespace-pre-wrap rounded-lg bg-ink-50 px-3 py-2 text-sm leading-6 text-ink-700">{item.answer || '（空回答）'}</div></div>
          <div className="grid gap-2 sm:grid-cols-[110px_1fr_auto]">
            <input type="number" min={0} max={10} step={1} value={draft.score} onChange={e => onDraft(item.questionId, { score: e.target.value })} placeholder="人工分数" className="rounded-lg border border-ink-200 px-3 py-2 text-sm outline-none focus:border-accent-500" />
            <input value={draft.rationale} maxLength={2000} onChange={e => onDraft(item.questionId, { rationale: e.target.value })} placeholder="评分理由（可选）" className="rounded-lg border border-ink-200 px-3 py-2 text-sm outline-none focus:border-accent-500" />
            <button disabled={busyId === item.questionId} onClick={() => onSave(item)} className="rounded-lg bg-ink-900 px-3 py-2 text-sm text-white hover:bg-ink-700 disabled:opacity-50">{busyId === item.questionId ? '提交中…' : '提交标注'}</button>
          </div>
        </div>;
      })}
      {!queue.length && <div className="px-5 py-8 text-center text-xs text-ink-400">暂无待双人标注的已收卷样本</div>}
    </div>
  </section>;
}

function auditName(action: string) {
  return ({
    USER_DISABLED: '禁用了用户', USER_RESTORED: '恢复了用户', USER_SOFT_DELETED: '软删除了用户',
    INTERVIEW_SCORE_ANNOTATED: '提交了面试评分标注',
    INTERVIEW_SCORE_QUEUE_VIEWED: '查看了面试评分标注队列',
    INTERVIEW_SCORE_REPLAY_EXPORTED: '导出了面试评分 replay',
  } as Record<string, string>)[action] || action;
}

function Loading() {
  return <div className="rounded-xl border border-ink-200 bg-white px-5 py-14 text-center text-sm text-ink-500">正在加载管理数据…</div>;
}
