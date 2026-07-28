import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../lib/api';

interface Notif { id: number; type: string; payload: any; read: boolean; created_at: string; }

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

function PayloadView({ p }: { p: any }) {
  // 后端 payload 是 JSON string (PG JSONB 反序列化), 先解析
  let obj: any = p;
  if (typeof p === 'string') {
    try { obj = JSON.parse(p); } catch { return <pre className="text-xs text-ink-500 whitespace-pre-wrap">{p}</pre>; }
  }
  if (!obj) return null;
  if (obj.job_title || obj.title) {
    return (
      <div>
        <div className="font-medium text-ink-900">{obj.job_title ?? obj.title} <span className="text-ink-500 font-normal">· {obj.company}</span></div>
        <div className="text-xs text-ink-500 mt-1 space-x-3">
          {obj.city && <span>📍 {obj.city}</span>}
          {obj.salary && <span>💰 {obj.salary}</span>}
        </div>
        {Array.isArray(obj.matched) && obj.matched.length > 0 && (
          <div className="text-xs text-ink-500 mt-1">✅ 匹配: {obj.matched.join(', ')}</div>
        )}
        {Array.isArray(obj.missing) && obj.missing.length > 0 && (
          <div className="text-xs text-ink-500 mt-0.5">⚠️ 缺失: {obj.missing.join(', ')}</div>
        )}
        {Array.isArray(obj.speedup) && obj.speedup.length > 0 && (
          <div className="text-xs text-accent-700 mt-0.5">⚡ 已具备: {obj.speedup.join(', ')}</div>
        )}
        {typeof obj.score === 'number' && (
          <div className="text-xs text-accent-700 mt-1">匹配分: {obj.score.toFixed(2)}</div>
        )}
      </div>
    );
  }
  if (obj.task) return <div className="text-sm text-ink-700">{obj.task}</div>;
  return <pre className="text-xs text-ink-500 whitespace-pre-wrap">{JSON.stringify(obj, null, 2)}</pre>;
}