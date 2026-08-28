import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { api, type PlanGenerationJob, type PlanTask } from '../lib/api';

const KIND_LABEL: Record<string, string> = { learn: '学习', practice: '练习', review: '复习' };
const KIND_COLOR: Record<string, string> = {
  learn: 'bg-accent-50 text-accent-700',
  practice: 'bg-amber-50 text-amber-700',
  review: 'bg-ink-100 text-ink-700',
};

export default function PlansPage() {
  const qc = useQueryClient();
  const { data: tasks, isLoading } = useQuery({ queryKey: ['today-tasks'], queryFn: () => api.todayTasks() });
  const { data: replanFlag } = useQuery({ queryKey: ['replan'], queryFn: () => api.shouldReplan() });
  const [goal, setGoal] = useState('');
  const [showNew, setShowNew] = useState(false);
  const [generationJobId, setGenerationJobId] = useState<number | null>(null);

  const { data: generationJob } = useQuery({
    queryKey: ['plan-generation', generationJobId],
    queryFn: () => api.getPlanGenerationJob(generationJobId as number),
    enabled: generationJobId !== null,
    refetchInterval: (query) => {
      const status = query.state.data?.status;
      return status === 'queued' || status === 'running' ? 1000 : false;
    },
  });

  const generate = useMutation({
    mutationFn: () => api.generatePlan({ goal }),
    onSuccess: (job: PlanGenerationJob) => {
      setGoal(''); setShowNew(false); setGenerationJobId(job.id);
    },
  });

  useEffect(() => {
    if (generationJob?.status === 'completed') {
      qc.invalidateQueries({ queryKey: ['today-tasks'] });
      qc.invalidateQueries({ queryKey: ['replan'] });
    }
  }, [generationJob?.status, qc]);

  const checkin = useMutation({
    mutationFn: (vars: { taskId: number; status: string }) => api.checkin(vars.taskId, vars.status),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['replan'] }),
  });

  return (
    <div className="h-full overflow-y-auto px-6 py-8">
      <div className="max-w-4xl mx-auto space-y-8">
        <div className="flex items-end justify-between border-b border-ink-200 pb-6">
          <div>
            <div className="editorial-kicker mb-2">Planning / This week</div>
            <h1 className="workspace-heading text-3xl text-ink-900">学习计划</h1>
            <p className="text-sm text-ink-500 mt-1">基于目标 + 当前技能 + 打卡历史生成</p>
          </div>
          <button onClick={() => setShowNew(!showNew)} className="px-4 py-2 bg-accent-500 hover:bg-accent-600 text-white rounded-md text-sm font-medium">
            {showNew ? '取消' : '生成新计划'}
          </button>
        </div>

        {showNew && (
          <div className="card p-5">
            <div className="text-sm font-medium text-ink-700 mb-2">目标 (如: 6 个月内转 NLP 岗)</div>
            <textarea
              value={goal}
              onChange={e => setGoal(e.target.value)}
              rows={3}
              placeholder="描述你的学习目标、可用时间、当前水平…"
              className="w-full px-3 py-2 border border-ink-200 rounded-md focus:outline-none focus:ring-2 focus:ring-accent-500/30 focus:border-accent-500"
            />
            <button
              disabled={!goal.trim() || generate.isPending}
              onClick={() => generate.mutate()}
              className="mt-3 px-4 py-2 bg-accent-500 hover:bg-accent-600 disabled:bg-ink-200 text-white rounded-md text-sm"
            >
              {generate.isPending ? '生成中…' : '生成周计划'}
            </button>
            {generate.error && <div className="mt-2 text-sm text-red-600">生成失败</div>}
          </div>
        )}

        {generationJob && (
          <div className={`card p-4 ${generationJob.status === 'failed' ? 'border-red-200 bg-red-50' : 'border-accent-200 bg-accent-50'}`}>
            <div className="text-sm font-medium text-ink-800">
              {generationJob.status === 'queued' && '计划已排队，等待生成…'}
              {generationJob.status === 'running' && '计划生成中，页面可以继续使用…'}
              {generationJob.status === 'completed' && '新学习计划已生成'}
              {generationJob.status === 'failed' && '计划生成失败'}
            </div>
            {generationJob.status === 'failed' && (
              <div className="text-xs text-red-700 mt-1">服务暂时无法生成计划，请稍后重试。</div>
            )}
          </div>
        )}

        {replanFlag?.should_replan && (
          <div className="card p-4 border-amber-200 bg-amber-50">
            <div className="text-sm font-medium text-amber-800">⚠️ 完成率 &lt; 60%, 建议重规划</div>
            <div className="text-xs text-amber-700 mt-1">系统检测到本周任务完成度偏低, 点击右上角"生成新计划"重新规划</div>
          </div>
        )}

        <section className="card p-5">
          <h2 className="text-sm font-semibold text-ink-700 uppercase tracking-wide mb-3">今日任务</h2>
          {isLoading ? (
            <div className="text-sm text-ink-500">加载中…</div>
          ) : !tasks || tasks.length === 0 ? (
            <div className="text-sm text-ink-500">暂无今日任务</div>
          ) : (
            <div className="space-y-2">
              {tasks.map((t: PlanTask) => (
                <div key={t.id} className="flex items-center gap-3 p-3 border border-ink-100 rounded-md">
                  <span className={`px-2 py-0.5 text-xs rounded ${KIND_COLOR[t.kind] ?? 'bg-ink-100 text-ink-700'}`}>
                    {KIND_LABEL[t.kind] ?? t.kind}
                  </span>
                  <div className="flex-1">
                    <div className="text-sm text-ink-900">{t.content}</div>
                    <div className="text-xs text-ink-500">预计 {t.estimatedMinutes ?? t.minutes ?? 60} 分钟</div>
                    {t.evidenceHint && <div className="text-xs text-accent-700 mt-1">完成证据：{t.evidenceHint}</div>}
                  </div>
                  <button onClick={() => checkin.mutate({ taskId: t.id, status: 'done' })}
                    className="text-xs px-2 py-1 text-accent-700 hover:bg-accent-50 rounded">
                    ✓ 完成
                  </button>
                  <button onClick={() => checkin.mutate({ taskId: t.id, status: 'skipped' })}
                    className="text-xs px-2 py-1 text-ink-500 hover:bg-ink-50 rounded">
                    跳过
                  </button>
                </div>
              ))}
            </div>
          )}
        </section>
      </div>
    </div>
  );
}
