import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, toUserMessage, type ManagedMemory, type UserFact } from '../lib/api';
import { useState } from 'react';

function formatDate(value: string) {
  return new Date(value).toLocaleDateString('zh-CN', { year: 'numeric', month: 'short', day: 'numeric' });
}

export default function MemoryPage() {
  const queryClient = useQueryClient();
  const [removing, setRemoving] = useState<ManagedMemory | null>(null);
  const [removingFact, setRemovingFact] = useState<UserFact | null>(null);
  const [clearOpen, setClearOpen] = useState(false);
  const [consentToggle, setConsentToggle] = useState<boolean | null>(null);
  const { data = [], isLoading, isError, error } = useQuery({ queryKey: ['memories'], queryFn: api.listMemories });
  const { data: facts = [], isLoading: factsLoading } = useQuery({ queryKey: ['facts'], queryFn: () => api.listFacts() });
  const { data: consent } = useQuery({ queryKey: ['memory-consent'], queryFn: api.getMemoryConsent });
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
  const updateConsent = useMutation({
    mutationFn: api.updateMemoryConsent,
    onSuccess: () => {
      setConsentToggle(null);
      queryClient.invalidateQueries({ queryKey: ['memory-consent'] });
      queryClient.invalidateQueries({ queryKey: ['memories'] });
      queryClient.invalidateQueries({ queryKey: ['facts'] });
      queryClient.invalidateQueries({ queryKey: ['memory-remote-deletion'] });
    },
  });
  const retryDeletion = useMutation({
    mutationFn: api.retryRemoteDeletion,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['memory-remote-deletion'] }),
  });
  const removeFact = useMutation({
    mutationFn: api.deleteFact,
    onSuccess: () => {
      setRemovingFact(null);
      queryClient.invalidateQueries({ queryKey: ['facts'] });
    },
  });
  const clear = useMutation({
    mutationFn: api.clearMemories,
    onSuccess: () => {
      setClearOpen(false);
      queryClient.invalidateQueries({ queryKey: ['memories'] });
      queryClient.invalidateQueries({ queryKey: ['facts'] });
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
            <div className="flex flex-wrap items-center justify-between gap-3">
              <span>
                {remoteDeletion.message}{remoteDeletion.attemptCount > 0 ? `（已尝试 ${remoteDeletion.attemptCount} 次）` : ''}
              </span>
              {remoteDeletion.status === 'failed' && (
                <button type="button" onClick={() => retryDeletion.mutate()} disabled={retryDeletion.isPending}
                  className="min-h-9 rounded-lg border border-red-300 px-3 text-xs font-medium text-red-700 hover:bg-red-100 disabled:opacity-50">
                  {retryDeletion.isPending ? '正在重试…' : '重试云端删除'}
                </button>
              )}
            </div>
            {retryDeletion.isError && <p className="mt-2 text-xs">{toUserMessage(retryDeletion.error, '重试失败，请稍后再试。')}</p>}
          </div>}
        </header>

        <section className="border-b border-ink-100 py-6">
          <div className="flex items-start justify-between gap-5">
            <div className="min-w-0">
              <h2 className="text-base font-semibold text-ink-900">云端长期记忆</h2>
              <p className="mt-1 max-w-xl text-sm leading-6 text-ink-500">
                可选的云端增强：把通过安全筛选的记忆摘要同步到云端服务，让跨会话召回更准。默认关闭；
                关闭时会立即删除全部本地跨会话记忆与长期事实，云端副本在后台异步清除。
              </p>
              {updateConsent.isError && <p className="mt-2 text-sm text-red-700">{toUserMessage(updateConsent.error)}</p>}
            </div>
            <button type="button"
              onClick={() => setConsentToggle(consent ? !consent.enabled : null)}
              disabled={!consent || updateConsent.isPending}
              className={`relative min-h-11 w-14 shrink-0 rounded-full border transition disabled:opacity-40 ${consent?.enabled ? 'bg-accent-600 border-accent-600' : 'bg-ink-100 border-ink-200'}`}
              role="switch" aria-checked={!!consent?.enabled} aria-label="云端长期记忆开关">
              <span className={`absolute top-1/2 h-5 w-5 -translate-y-1/2 rounded-full bg-white shadow transition-all ${consent?.enabled ? 'left-8' : 'left-1'}`} />
            </button>
          </div>
        </section>

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

        <section className="border-t border-ink-100 py-6" aria-live="polite">
          <div className="mb-4">
            <h2 className="text-base font-semibold text-ink-900">长期事实</h2>
            <p className="mt-1 text-sm leading-6 text-ink-500">从你的对话中提炼的稳定目标、偏好与技能水平，回答时会优先参考。与上面的记忆相互独立，可单独删除。</p>
          </div>
          {factsLoading && <div className="py-8 text-center text-sm text-ink-500">正在读取长期事实…</div>}
          {!factsLoading && facts.length === 0 && (
            <div className="py-8 text-center text-sm text-ink-500">还没有长期事实。多聊聊你的目标与偏好，它们会随对话逐步沉淀。</div>
          )}
          {!factsLoading && facts.length > 0 && <div className="divide-y divide-ink-100">
            {facts.map(fact => <FactRow key={fact.id} fact={fact} onDelete={() => setRemovingFact(fact)} />)}
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
      {consentToggle !== null && consent && <div className="fixed inset-0 z-[60] flex items-end bg-ink-900/35 p-4 sm:items-center sm:justify-center" role="presentation">
        <div className="w-full max-w-md rounded-2xl bg-white p-6 shadow-xl" role="dialog" aria-modal="true" aria-labelledby="consent-title">
          {consentToggle ? (
            <>
              <h2 id="consent-title" className="text-lg font-semibold text-ink-900">开启云端长期记忆？</h2>
              <p className="mt-2 text-sm leading-6 text-ink-600">开启后，只有通过安全筛选的脱敏记忆摘要会同步到云端，并仅用于你自己的召回。云端旧副本会先被清除再重新同步。</p>
            </>
          ) : (
            <>
              <h2 id="consent-title" className="text-lg font-semibold text-ink-900">关闭云端长期记忆？</h2>
              <p className="mt-2 text-sm leading-6 text-ink-600">关闭会立即删除全部本地跨会话记忆与长期事实；云端删除在后台异步处理，完成后可在上方状态区确认。聊天记录、会话摘要和个人画像不会被删除。</p>
            </>
          )}
          {updateConsent.isError && <p className="mt-3 text-sm text-red-700">{toUserMessage(updateConsent.error)}</p>}
          <div className="mt-6 flex justify-end gap-3">
            <button type="button" onClick={() => setConsentToggle(null)} disabled={updateConsent.isPending} className="min-h-11 px-3 text-sm text-ink-600 hover:text-ink-900 disabled:opacity-50">取消</button>
            <button type="button" onClick={() => updateConsent.mutate(consentToggle)} disabled={updateConsent.isPending}
              className={`min-h-11 rounded-lg px-4 text-sm font-medium text-white disabled:opacity-50 ${consentToggle ? 'bg-accent-600 hover:bg-accent-700' : 'bg-red-600 hover:bg-red-700'}`}>
              {updateConsent.isPending ? '正在处理…' : consentToggle ? '开启' : '关闭并清除'}
            </button>
          </div>
        </div>
      </div>}
      {removingFact && <div className="fixed inset-0 z-[60] flex items-end bg-ink-900/35 p-4 sm:items-center sm:justify-center" role="presentation">
        <div className="w-full max-w-md rounded-2xl bg-white p-6 shadow-xl" role="dialog" aria-modal="true" aria-labelledby="fact-delete-title">
          <h2 id="fact-delete-title" className="text-lg font-semibold text-ink-900">删除这条长期事实？</h2>
          <p className="mt-2 text-sm leading-6 text-ink-600">删除后，它不会再用于后续对话。相似的事实可能在之后的对话中重新沉淀。</p>
          <p className="mt-3 rounded-lg bg-ink-50 px-3 py-2 text-sm text-ink-700">{removingFact.factText}</p>
          {removeFact.isError && <p className="mt-3 text-sm text-red-700">{toUserMessage(removeFact.error)}</p>}
          <div className="mt-6 flex justify-end gap-3">
            <button type="button" onClick={() => setRemovingFact(null)} disabled={removeFact.isPending} className="min-h-11 px-3 text-sm text-ink-600 hover:text-ink-900 disabled:opacity-50">取消</button>
            <button type="button" onClick={() => removeFact.mutate(removingFact.id)} disabled={removeFact.isPending} className="min-h-11 rounded-lg bg-red-600 px-4 text-sm font-medium text-white hover:bg-red-700 disabled:opacity-50">
              {removeFact.isPending ? '正在删除…' : '删除事实'}
            </button>
          </div>
        </div>
      </div>}
      {clearOpen && <div className="fixed inset-0 z-[60] flex items-end bg-ink-900/35 p-4 sm:items-center sm:justify-center" role="presentation">
        <div className="w-full max-w-md rounded-2xl bg-white p-6 shadow-xl" role="dialog" aria-modal="true" aria-labelledby="memory-clear-title">
          <h2 id="memory-clear-title" className="text-lg font-semibold text-ink-900">清除全部跨会话记忆？</h2>
          <p className="mt-2 text-sm leading-6 text-ink-600">所有本地跨会话记忆与长期事实将立即删除。若你曾启用云端记忆，云端删除会在后台继续处理；聊天记录、会话摘要和个人画像不会被删除。</p>
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

const FACT_LABELS: Record<string, string> = {
  goal: '目标',
  preference: '偏好',
  skill: '技能',
  constraint: '约束',
  background: '背景',
};

function FactRow({ fact, onDelete }: { fact: UserFact; onDelete: () => void }) {
  return <article className="py-5 first:pt-0">
    <div className="flex items-start justify-between gap-5">
      <div className="min-w-0 flex-1">
        <p className="text-sm leading-6 text-ink-800">{fact.factText}</p>
        <div className="mt-3 flex flex-wrap items-center gap-2">
          <span className="rounded-full bg-accent-50 px-2.5 py-1 text-xs font-medium text-accent-700">{FACT_LABELS[fact.category] ?? fact.category}</span>
          <span className="text-xs text-ink-400">更新于 {formatDate(fact.updatedAt)}</span>
        </div>
      </div>
      <button type="button" onClick={onDelete} className="min-h-11 shrink-0 px-2 text-sm text-ink-500 hover:text-red-700 focus:outline-none focus:ring-2 focus:ring-red-500/30">删除</button>
    </div>
  </article>;
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
