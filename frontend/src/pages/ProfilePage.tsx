import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../lib/api';

interface Skill { name: string; confidence: number; source: string; last_seen?: string; }

// 后端 key → 前端中文标签
const KEY_LABEL: Record<string, string> = {
  location: '所在城市',
  daily_hours: '每日学习时长',
  pending_confirm: '待确认字段',
  target_position: '目标岗位',
  experience_years: '工作年限',
  preferred_format: '偏好资源形式',
};

// 标量/数组/对象 多态显示
function renderValue(v: any): string {
  if (v == null) return '—';
  if (typeof v === 'object') {
    if (Array.isArray(v)) {
      // pending_confirm 类型 [{field: 'city', value: '杭州'}, ...]
      if (v.length > 0 && typeof v[0] === 'object' && 'field' in v[0]) {
        return v.map((it: any) => `${it.field}=${it.value ?? ''}`).join(', ');
      }
      return v.join(', ');
    }
    if ('value' in v) return String(v.value ?? '—');
    return '—';
  }
  return String(v);
}

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

  // pending_confirm 在画像中是个嵌套列表; 可"逐条确认" 或整体确认
  const pendingItems: { field: string; value: string }[] = Array.isArray(p.pending_confirm)
    ? p.pending_confirm.filter((x: any) => x && typeof x === 'object' && 'field' in x)
    : [];

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
            {scalars.map(([k, v]) => {
              const display = renderValue(v);
              const confirmed = (v && typeof v === 'object' && 'confirmed' in v && !Array.isArray(v)) ? (v as any).confirmed : false;
              const isPendingArr = k === 'pending_confirm' && Array.isArray(v) && pendingItems.length > 0;
              return (
                <div key={k} className="py-2.5 flex items-start justify-between gap-3">
                  <div className="min-w-0 flex-1">
                    <div className="text-xs text-ink-500">{KEY_LABEL[k] ?? k}</div>
                    <div className="text-sm text-ink-900 mt-0.5 break-all">{display}</div>
                  </div>
                  {!isPendingArr && (
                    <button
                      onClick={() => confirm.mutate({ field: k, accept: true })}
                      disabled={confirm.isPending || confirmed}
                      className={`shrink-0 mt-0.5 text-xs px-2.5 py-1 rounded ${confirmed ? 'text-ink-400 cursor-not-allowed' : 'text-accent-700 hover:bg-accent-50'}`}
                    >
                      {confirmed ? '✓ 已确认' : '✓ 确认'}
                    </button>
                  )}
                </div>
              );
            })}
          </div>
        </section>

        {pendingItems.length > 0 && (
          <section className="card p-5">
            <h2 className="text-sm font-semibold text-ink-700 uppercase tracking-wide mb-3">待确认字段 ({pendingItems.length})</h2>
            <div className="space-y-2">
              {pendingItems.map((it, i) => (
                <div key={i} className="flex items-center justify-between p-3 border border-ink-100 rounded-md">
                  <div>
                    <div className="text-sm text-ink-900">{KEY_LABEL[it.field] ?? it.field} = <span className="font-medium">{it.value}</span></div>
                    <div className="text-xs text-ink-500 mt-0.5">系统推断, 等待你确认</div>
                  </div>
                  <button
                    onClick={() => confirm.mutate({ field: it.field, accept: true })}
                    className="text-xs px-2.5 py-1 text-accent-700 hover:bg-accent-50 rounded"
                  >
                    ✓ 确认
                  </button>
                </div>
              ))}
            </div>
          </section>
        )}

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