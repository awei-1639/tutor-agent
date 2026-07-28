import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { api } from '../lib/api';

interface Skill { name: string; confidence: number; source: string; last_seen?: string; }

export default function ProfilePage() {
  const qc = useQueryClient();
  const { data, isLoading } = useQuery({ queryKey: ['profile'], queryFn: () => api.getProfile() });
  const [editing, setEditing] = useState(false);
  const [pending, setPending] = useState<Record<string, unknown> | null>(null);

  if (isLoading) return <div className="p-8 text-ink-500">加载中…</div>;
  const p: any = data?.data ?? {};
  const skills: Skill[] = p.skills ?? [];
  const scalars = Object.entries(p).filter(([k]) => !['skills', 'open_items', 'topics'].includes(k));

  function startEdit() {
    setPending(JSON.parse(JSON.stringify(p)));
    setEditing(true);
  }

  async function confirm() {
    await api.confirmProfile(pending);
    setEditing(false);
    qc.invalidateQueries({ queryKey: ['profile'] });
  }

  return (
    <div className="h-full overflow-y-auto px-6 py-6">
      <div className="max-w-4xl mx-auto space-y-6">
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-2xl font-semibold text-ink-900">个人画像</h1>
            <p className="text-sm text-ink-500 mt-1">基于对话/简历/打卡自动抽取，每日 4 点衰减</p>
          </div>
          {!editing ? (
            <button onClick={startEdit} className="px-4 py-2 bg-white border border-ink-200 hover:border-ink-300 rounded-md text-sm">编辑 / 确认</button>
          ) : (
            <div className="flex gap-2">
              <button onClick={() => setEditing(false)} className="px-4 py-2 bg-white border border-ink-200 rounded-md text-sm">取消</button>
              <button onClick={confirm} className="px-4 py-2 bg-accent-500 hover:bg-accent-600 text-white rounded-md text-sm">提交</button>
            </div>
          )}
        </div>

        <section className="card p-5">
          <h2 className="text-sm font-semibold text-ink-700 uppercase tracking-wide mb-3">基础属性</h2>
          <div className="grid grid-cols-2 gap-x-6 gap-y-3">
            {scalars.length === 0 && <div className="text-sm text-ink-500 col-span-2">暂无数据</div>}
            {scalars.map(([k, v]) => (
              <div key={k}>
                <div className="text-xs text-ink-500">{k}</div>
                <div className="text-sm text-ink-900 mt-0.5">
                  {editing ? (
                    <input
                      defaultValue={String(v ?? '')}
                      onChange={e => setPending({ ...pending, [k]: e.target.value })}
                      className="w-full px-2 py-1 border border-ink-200 rounded"
                    />
                  ) : (
                    <span>{String(v ?? '—')}</span>
                  )}
                </div>
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