import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, toUserMessage, type ManagedMemory } from '../lib/api';
import { useState } from 'react';

function formatDate(value: string) {
  return new Date(value).toLocaleDateString('zh-CN', { year: 'numeric', month: 'short', day: 'numeric' });
}

export default function MemoryPage() {
  const queryClient = useQueryClient();
  const [removing, setRemoving] = useState<ManagedMemory | null>(null);
  const [clearOpen, setClearOpen] = useState(false);
  const { data = [], isLoading, isError, error } = useQuery({ queryKey: ['memories'], queryFn: api.listMemories });
  const { data: remoteDeletion } = useQuery({ queryKey: ['memory-remote-deletion'], queryFn: api.getRemoteMemoryDeletion, refetchInterval: query => {
    const status = query.state.data?.status;
    return status === 'pending' || status === 'processing' || status === 'retryable' ? 3000 : false;
  } });
  const remove = useMutation({
    mutationFn: api.deleteMemory,
    onSuccess: () => {
      setRemoving(null);
      queryClient.invalidateQueries({ queryKey: ['memories'] });
    },
  });
  const clear = useMutation({
    mutationFn: api.clearMemories,
    onSuccess: () => {
      setClearOpen(false);
      queryClient.invalidateQueries({ queryKey: ['memories'] });
      queryClient.invalidateQueries({ queryKey: ['memory-remote-deletion'] });
    },
  });

  return (
    <div className="h-full overflow-y-auto px-5 py-6 md:px-8 md:py-8">
      <div className="mx-auto max-w-3xl">
        <header className="border-b border-ink-100 pb-6">
          <div className="flex flex-wrap items-start justify-between gap-4">
            <div>
              <h1 className="text-2xl font-semibold tracking-tight text-ink-900">跨会话记忆</h1>
              <p className="mt-2 max-w-2xl text-sm leading-6 text-ink-500">这些内容仅用于让后续对话更贴合你的学习与求职目标。删除不会移除聊天记录、会话摘要或个人画像。</p>
            </div>
            <button type="button" onClick={() => setClearOpen(true)} disabled={data.length === 0} className="min-h-11 rounded-lg border border-red-200 px-3 text-sm font-medium text-red-700 hover:bg-red-50 disabled:cursor-not-allowed disabled:opacity-40">清除全部</button>
          </div>
          {remoteDeletion && remoteDeletion.status !== 'not_requested' && remoteDeletion.status !== 'completed' && <div className={`mt-5 rounded-xl px-4 py-3 text-sm ${remoteDeletion.status === 'failed' ? 'bg-red-50 text-red-700' : 'bg-amber-50 text-amber-800'}`}>
            {remoteDeletion.message}{remoteDeletion.attemptCount > 0 ? `（已尝试 ${remoteDeletion.attemptCount} 次）` : ''}
          </div>}
        </header>

        <section className="py-6" aria-live="polite">
          {isLoading && <div className="py-12 text-center text-sm text-ink-500">正在读取记忆…</div>}
          {isError && <div className="rounded-xl bg-red-50 px-4 py-3 text-sm text-red-700">{toUserMessage(error, '记忆暂时无法读取，请稍后刷新。')}</div>}
          {!isLoading && !isError && data.length === 0 && (
            <div className="py-14 text-center">
              <h2 className="text-base font-medium text-ink-800">还没有跨会话记忆</h2>
              <p className="mx-auto mt-2 max-w-sm text-sm leading-6 text-ink-500">当你明确说明长期目标、偏好或经验后，系统会先经过安全筛选，再保存为可管理的记忆。</p>
            </div>
          )}
          {!isLoading && data.length > 0 && <div className="divide-y divide-ink-100">
            {data.map(memory => <MemoryRow key={memory.id} memory={memory} onDelete={() => setRemoving(memory)} />)}
          </div>}
        </section>
      </div>

      {removing && <div className="fixed inset-0 z-[60] flex items-end bg-ink-900/35 p-4 sm:items-center sm:justify-center" role="presentation">
        <div className="w-full max-w-md rounded-2xl bg-white p-6 shadow-xl" role="dialog" aria-modal="true" aria-labelledby="memory-delete-title">
          <h2 id="memory-delete-title" className="text-lg font-semibold text-ink-900">删除这条记忆？</h2>
          <p className="mt-2 text-sm leading-6 text-ink-600">删除后，它不会再用于后续对话的跨会话上下文。此操作不会删除原始聊天记录。</p>
          <p className="mt-3 rounded-lg bg-ink-50 px-3 py-2 text-sm text-ink-700">{removing.summary}</p>
          {remove.isError && <p className="mt-3 text-sm text-red-700">{toUserMessage(remove.error)}</p>}
          <div className="mt-6 flex justify-end gap-3">
            <button type="button" onClick={() => setRemoving(null)} disabled={remove.isPending} className="min-h-11 px-3 text-sm text-ink-600 hover:text-ink-900 disabled:opacity-50">取消</button>
            <button type="button" onClick={() => remove.mutate(removing.id)} disabled={remove.isPending} className="min-h-11 rounded-lg bg-red-600 px-4 text-sm font-medium text-white hover:bg-red-700 disabled:opacity-50">
              {remove.isPending ? '正在删除…' : '删除记忆'}
            </button>
          </div>
        </div>
      </div>}
      {clearOpen && <div className="fixed inset-0 z-[60] flex items-end bg-ink-900/35 p-4 sm:items-center sm:justify-center" role="presentation">
        <div className="w-full max-w-md rounded-2xl bg-white p-6 shadow-xl" role="dialog" aria-modal="true" aria-labelledby="memory-clear-title">
          <h2 id="memory-clear-title" className="text-lg font-semibold text-ink-900">清除全部跨会话记忆？</h2>
          <p className="mt-2 text-sm leading-6 text-ink-600">所有本地跨会话记忆将立即删除。若你曾启用云端记忆，云端删除会在后台继续处理；聊天记录、会话摘要和个人画像不会被删除。</p>
          {clear.isError && <p className="mt-3 text-sm text-red-700">{toUserMessage(clear.error)}</p>}
          <div className="mt-6 flex justify-end gap-3">
            <button type="button" onClick={() => setClearOpen(false)} disabled={clear.isPending} className="min-h-11 px-3 text-sm text-ink-600 hover:text-ink-900 disabled:opacity-50">取消</button>
            <button type="button" onClick={() => clear.mutate()} disabled={clear.isPending} className="min-h-11 rounded-lg bg-red-600 px-4 text-sm font-medium text-white hover:bg-red-700 disabled:opacity-50">{clear.isPending ? '正在清除…' : '清除全部'}</button>
          </div>
        </div>
      </div>}
    </div>
  );
}

function MemoryRow({ memory, onDelete }: { memory: ManagedMemory; onDelete: () => void }) {
  return <article className="py-5 first:pt-0">
    <div className="flex items-start justify-between gap-5">
      <div className="min-w-0 flex-1">
        <p className="text-sm leading-6 text-ink-800">{memory.summary}</p>
        {memory.topics.length > 0 && <div className="mt-3 flex flex-wrap gap-2">{memory.topics.map(topic => <span key={topic} className="rounded-full bg-accent-50 px-2.5 py-1 text-xs font-medium text-accent-700">{topic}</span>)}</div>}
        {memory.openItems.length > 0 && <p className="mt-3 text-xs leading-5 text-ink-500">待继续：{memory.openItems.join('、')}</p>}
        <p className="mt-3 text-xs text-ink-400">记录于 {formatDate(memory.createdAt)}{memory.expiresAt ? ` · 将于 ${formatDate(memory.expiresAt)} 自动过期` : ''}</p>
      </div>
      <button type="button" onClick={onDelete} className="min-h-11 shrink-0 px-2 text-sm text-ink-500 hover:text-red-700 focus:outline-none focus:ring-2 focus:ring-red-500/30">删除</button>
    </div>
  </article>;
}
