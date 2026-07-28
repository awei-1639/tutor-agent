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
  if (!p) return null;
  if (p.job_title) {
    return (
      <div>
        <div className="font-medium text-ink-900">{p.job_title} <span className="text-ink-500 font-normal">· {p.company}</span></div>
        {p.matched_skills && <div className="text-xs text-ink-500 mt-1">匹配: {p.matched_skills.join(', ')}</div>}
        {p.match_score && <div className="text-xs text-accent-700 mt-1">得分: {p.match_score}</div>}
      </div>
    );
  }
  if (p.task) return <div className="text-sm text-ink-700">{p.task}</div>;
  return <pre className="text-xs text-ink-500 whitespace-pre-wrap">{JSON.stringify(p, null, 2)}</pre>;
}