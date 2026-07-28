import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../lib/api';

interface Skill { name: string; confidence: number; source: string; last_seen?: string; }

export default function ProfilePage() {
  const qc = useQueryClient();
  const { data, isLoading } = useQuery({ queryKey: ['profile'], queryFn: () => api.getProfile() });
  const confirm = useMutation({
    mutationFn: (vars: { field: string; accept: boolean }) => api.confirmProfile(vars.field, vars.accept),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['profile'] }),
  });

  if (isLoading) return <div className="p-8 text-ink-500">加载中…</div>;
  const p: any = data ?? {};
  const skills: Skill[] = p.skills ?? [];
  const scalars = Object.entries(p).filter(([k]) => !['skills', 'open_items', 'topics'].includes(k));

  return (
    <div className="h-full overflow-y-auto px-6 py-6">
      <div className="max-w-4xl mx-auto space-y-6">
        <div>
          <h1 className="text-2xl font-semibold text-ink-900">个人画像</h1>
          <p className="text-sm text-ink-500 mt-1">基于对话/简历/打卡自动抽取，每日 4 点衰减。"✓" 关键字段确认 → 置信度锁 0.9</p>
        </div>

        <section className="card p-5">
          <h2 className="text-sm font-semibold text-ink-700 uppercase tracking-wide mb-3">基础属性</h2>
          <div className="divide-y divide-ink-100">
            {scalars.length === 0 && <div className="text-sm text-ink-500 py-2">暂无数据</div>}
            {scalars.map(([k, v]) => (
              <div key={k} className="py-2.5 flex items-center justify-between">
                <div className="min-w-0">
                  <div className="text-xs text-ink-500">{k}</div>
                  <div className="text-sm text-ink-900 mt-0.5">{String(v ?? '—')}</div>
                </div>
                <button
                  onClick={() => confirm.mutate({ field: k, accept: true })}
                  disabled={confirm.isPending}
                  className="text-xs px-2.5 py-1 text-accent-700 hover:bg-accent-50 rounded"
                >
                  ✓ 确认
                </button>
              </div>
            ))}
          </div>
        </section>

        <section className="card p-5">
          <h2 className="text-sm font-semibold text-ink-700 uppercase tracking-wide mb-3">技能 ({skills.length})</h2>
          {skills.length === 0 ? (
            <div className="text-sm text-ink-500">对话或上传简历后自动抽取</div>
          ) : (
            <div className="flex flex-wrap gap-2">
              {skills.map((s, i) => (
                <div key={i} className="group inline-flex items-center gap-2 px-3 py-1.5 bg-ink-50 hover:bg-ink-100 border border-ink-100 rounded-full">
                  <span className="text-sm font-medium text-ink-900">{s.name}</span>
                  <span className="text-xs text-ink-500">{Math.round(s.confidence * 100)}%</span>
                  <span className="text-[10px] text-ink-500 uppercase">{s.source}</span>
                </div>
              ))}
            </div>
          )}
        </section>

        {Array.isArray(p.open_items) && p.open_items.length > 0 && (
          <section className="card p-5">
            <h2 className="text-sm font-semibold text-ink-700 uppercase tracking-wide mb-3">待跟进</h2>
            <ul className="space-y-2 text-sm">
              {p.open_items.map((q: string, i: number) => (
                <li key={i} className="flex items-start gap-2">
                  <span className="text-accent-500 mt-0.5">•</span>
                  <span className="text-ink-700">{q}</span>
                </li>
              ))}
            </ul>
          </section>
        )}
      </div>
    </div>
  );
}