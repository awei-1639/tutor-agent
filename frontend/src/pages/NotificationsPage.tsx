import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, type Notification } from '../lib/api';

type Notif = Notification;

export default function NotificationsPage() {
  const qc = useQueryClient();
  const { data, isLoading } = useQuery({ queryKey: ['notifications'], queryFn: () => api.listNotifications() });
  const mark = useMutation({
    mutationFn: (id: number) => api.markRead(id),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['notifications'] }),
  });

  if (isLoading) return <div className="p-8 text-ink-500">加载中…</div>;
  const items: Notif[] = data ?? [];

  return (
    <div className="h-full overflow-y-auto px-6 py-6">
      <div className="max-w-3xl mx-auto space-y-4">
        <div>
          <h1 className="text-2xl font-semibold text-ink-900">推送</h1>
          <p className="text-sm text-ink-500 mt-1">岗位匹配 / 学习任务 / 系统消息</p>
        </div>

        {items.length === 0 ? (
          <div className="card p-8 text-center text-ink-500">暂无推送</div>
        ) : (
          <div className="space-y-2">
            {items.map(n => (
              <div key={n.id} className={`card p-4 ${n.read ? '' : 'border-l-4 border-l-accent-500'}`}>
                <div className="flex items-start justify-between gap-3">
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2 mb-1">
                      <span className="text-xs font-medium text-accent-700 uppercase">{typeLabel(n.type)}</span>
                      <span className="text-xs text-ink-500">{new Date(n.created_at).toLocaleString('zh-CN')}</span>
                    </div>
                    <PayloadView p={n.payload} />
                  </div>
                  {!n.read && (
                    <button onClick={() => mark.mutate(n.id)} className="text-xs text-ink-500 hover:text-ink-900 px-2 py-1">
                      标已读
                    </button>
                  )}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

function typeLabel(t: string) {
  return { job_push: '岗位', guide: '学习任务', system: '系统' }[t] ?? t;
}

function asRecord(value: unknown): Record<string, unknown> | null {
  return value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, unknown> : null;
}

function stringList(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string') : [];
}

function PayloadView({ p }: { p: unknown }) {
  // 后端 payload 是 JSON string (PG JSONB 反序列化), 先解析
  let obj: unknown = p;
  if (typeof p === 'string') {
    try { obj = JSON.parse(p); } catch { return <pre className="text-xs text-ink-500 whitespace-pre-wrap">{p}</pre>; }
  }
  const record = asRecord(obj);
  if (!record) return obj == null ? null : <pre className="text-xs text-ink-500 whitespace-pre-wrap">{JSON.stringify(obj, null, 2)}</pre>;
  const title = typeof record.job_title === 'string' ? record.job_title : typeof record.title === 'string' ? record.title : undefined;
  if (title) {
    const company = typeof record.company === 'string' ? record.company : '';
    const city = typeof record.city === 'string' ? record.city : undefined;
    const salary = typeof record.salary === 'string' ? record.salary : undefined;
    const score = typeof record.score === 'number' ? record.score : undefined;
    const matched = stringList(record.matched);
    const missing = stringList(record.missing);
    const speedup = stringList(record.speedup);
    return (
      <div>
        <div className="font-medium text-ink-900">{title} <span className="text-ink-500 font-normal">· {company}</span></div>
        <div className="text-xs text-ink-500 mt-1 space-x-3">
          {city && <span>📍 {city}</span>}
          {salary && <span>💰 {salary}</span>}
        </div>
        {matched.length > 0 && (
          <div className="text-xs text-ink-500 mt-1">✅ 匹配: {matched.join(', ')}</div>
        )}
        {missing.length > 0 && (
          <div className="text-xs text-ink-500 mt-0.5">⚠️ 缺失: {missing.join(', ')}</div>
        )}
        {speedup.length > 0 && (
          <div className="text-xs text-accent-700 mt-0.5">⚡ 已具备: {speedup.join(', ')}</div>
        )}
        {score !== undefined && (
          <div className="text-xs text-accent-700 mt-1">匹配分: {score.toFixed(2)}</div>
        )}
      </div>
    );
  }
  if (typeof record.task === 'string') return <div className="text-sm text-ink-700">{record.task}</div>;
  return <pre className="text-xs text-ink-500 whitespace-pre-wrap">{JSON.stringify(record, null, 2)}</pre>;
}
