import { useCallback, useEffect, useRef, useState } from 'react';
import { AdminDocument, api, toUserMessage } from '../lib/api';

const labels: Record<string, string> = {
  uploading: '上传中', uploaded: '等待处理', processing: '解析中', indexed: '已入库', failed: '失败', deleted: '已删除', deduplicated: '已存在',
};

const stageLabels: Record<string, string> = {
  queued: '排队中', validating: '校验中', parsing: '解析中', embedding: '生成向量中', publishing: '发布索引中',
  completed: '已完成', failed: '处理失败', cancelled: '已取消',
};

function tone(status: string) {
  if (status === 'indexed') return 'bg-emerald-50 text-emerald-700';
  if (status === 'processing' || status === 'uploaded' || status === 'uploading') return 'bg-amber-50 text-amber-700';
  return 'bg-rose-50 text-rose-700';
}

function formatSize(bytes: number) {
  if (bytes < 1024 * 1024) return `${Math.max(1, Math.round(bytes / 1024))} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

export default function KnowledgeBasePage() {
  const [documents, setDocuments] = useState<AdminDocument[]>([]);
  const [file, setFile] = useState<File | null>(null);
  const [title, setTitle] = useState('');
  const [loading, setLoading] = useState(true);
  const [uploading, setUploading] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);
  const [message, setMessage] = useState('');
  const picker = useRef<HTMLInputElement>(null);

  const reload = useCallback(async () => {
    try {
      setDocuments(await api.adminDocuments());
    } catch (e) {
      setMessage(toUserMessage(e, '知识库数据暂时无法加载，请稍后重试。'));
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void reload(); }, [reload]);
  useEffect(() => {
    if (!documents.some(d => ['uploaded', 'processing'].includes(d.status) || ['pending', 'retryable_failed'].includes(d.jobStatus ?? ''))) return;
    const timer = window.setInterval(() => { void reload(); }, 3000);
    return () => window.clearInterval(timer);
  }, [documents, reload]);

  async function upload() {
    if (!file) return;
    setUploading(true); setMessage('');
    try {
      const result = await api.adminUploadDocument(file, title);
      setMessage(result.deduplicated ? '检测到相同内容，已复用已有文档。' : '已上传到 OSS，正在后台解析和向量化。');
      setFile(null); setTitle('');
      if (picker.current) picker.current.value = '';
      await reload();
    } catch (e) {
      setMessage(`上传失败：${toUserMessage(e)}`);
    } finally {
      setUploading(false);
    }
  }

  async function action(document: AdminDocument, action: 'retry' | 'soft-delete') {
    if (action === 'soft-delete' && !window.confirm(`确认删除知识文档「${document.title}」吗？原始文件和检索分块将不再可用。`)) return;
    setBusy(document.id); setMessage('');
    try {
      await api.adminDocumentAction(document.id, action);
      await reload();
    } catch (e) {
      setMessage(`${action === 'retry' ? '重试' : '删除'}失败：${toUserMessage(e)}`);
    } finally {
      setBusy(null);
    }
  }

  return <div className="h-full overflow-y-auto bg-[#f7f8fa]"><div className="mx-auto max-w-[1240px] px-8 py-8">
    <div className="flex items-start justify-between gap-4 mb-7"><div><div className="text-xs uppercase tracking-[.16em] text-ink-400">Knowledge operations</div><h1 className="mt-2 text-2xl font-semibold tracking-tight text-ink-900">知识库</h1><p className="mt-2 text-sm text-ink-500">原文件保存在私有 OSS，解析、切分和向量化在后台执行；仅已入库文档会参与 RAG 召回。</p></div><button onClick={() => void reload()} className="rounded-lg border border-ink-200 bg-white px-3 py-2 text-sm text-ink-700">刷新</button></div>
    {message && <div className="mb-5 rounded-lg border border-ink-200 bg-white px-4 py-3 text-sm text-ink-700">{message}</div>}
    <section className="rounded-xl border border-ink-200 bg-white p-5"><div className="flex flex-wrap items-end gap-3"><div className="min-w-[250px] flex-1"><label className="mb-2 block text-xs font-medium text-ink-600">选择文档</label><input ref={picker} type="file" onChange={e => setFile(e.target.files?.[0] ?? null)} className="block w-full text-sm text-ink-600 file:mr-3 file:rounded-md file:border-0 file:bg-ink-100 file:px-3 file:py-2 file:text-sm file:text-ink-700" /></div><div className="min-w-[220px] flex-1"><label className="mb-2 block text-xs font-medium text-ink-600">标题（可选）</label><input value={title} onChange={e => setTitle(e.target.value)} placeholder={file?.name || '默认使用文件名'} className="w-full rounded-lg border border-ink-200 px-3 py-2 text-sm outline-none focus:border-accent-500" /></div><button disabled={!file || uploading} onClick={() => void upload()} className="rounded-lg bg-ink-900 px-4 py-2 text-sm font-medium text-white disabled:cursor-not-allowed disabled:opacity-40">{uploading ? '上传中…' : '上传并入库'}</button></div><p className="mt-3 text-xs text-ink-400">文件选择器展示全部文件；服务端仅接受 PDF、DOCX、TXT、Markdown（扩展名不区分大小写），默认单文件上限 50MB；文档会进入后台队列处理。</p></section>
    <section className="mt-5 overflow-hidden rounded-xl border border-ink-200 bg-white"><div className="flex items-center justify-between border-b border-ink-100 px-5 py-4"><div><h2 className="text-sm font-semibold text-ink-900">文档列表</h2><p className="mt-1 text-xs text-ink-500">摄取任务会保留阶段、重试次数和失败原因。</p></div><span className="text-xs text-ink-400">{documents.length} 个文档</span></div>{loading ? <div className="px-5 py-12 text-center text-sm text-ink-500">正在加载…</div> : <div className="divide-y divide-ink-100">{documents.map(document => <div key={document.id} className="flex flex-wrap items-center gap-4 px-5 py-4"><div className="min-w-[260px] flex-1"><div className="font-medium text-ink-800">{document.title}</div><div className="mt-1 text-xs text-ink-400">{document.filename} · {formatSize(document.sizeBytes)} · {document.chunkCount} 个分块</div>{document.partialIndexed && <div className="mt-2 text-xs text-amber-700">仅部分索引：{document.truncationReason || '文档超出当前容量限制'}</div>}{document.jobStage && <div className="mt-2 text-xs text-ink-500">摄取阶段：{stageLabels[document.jobStage] ?? document.jobStage}{document.jobAttempts ? ` · 已尝试 ${document.jobAttempts} 次` : ''}</div>}{(document.jobError || document.error) && <div className="mt-1 text-xs text-rose-600">{document.jobError || document.error}</div>}</div><span className={`rounded-full px-2.5 py-1 text-xs ${tone(document.status)}`}>{labels[document.status] || document.status}</span><div className="flex gap-3 text-xs">{document.status === 'failed' && <button disabled={busy === document.id} onClick={() => void action(document, 'retry')} className="text-accent-700 hover:underline disabled:opacity-40">重新处理</button>}{document.status !== 'deleted' && <button disabled={busy === document.id} onClick={() => void action(document, 'soft-delete')} className="text-rose-700 hover:underline disabled:opacity-40">删除</button>}</div></div>)}{!documents.length && <div className="px-5 py-12 text-center text-sm text-ink-400">还没有上传知识文档</div>}</div>}</section>
  </div></div>;
}
