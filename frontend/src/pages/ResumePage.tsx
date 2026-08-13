import { useState } from 'react';
import { api, toUserMessage, type ResumeUploadResponse } from '../lib/api';

export default function ResumePage() {
  const [file, setFile] = useState<File | null>(null);
  const [result, setResult] = useState<ResumeUploadResponse | null>(null);
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState('');

  async function upload() {
    if (!file) return;
    setLoading(true); setErr(''); setResult(null);
    try {
      const r = await api.uploadResume(file);
      setResult(r);
    } catch (e: unknown) {
      setErr(toUserMessage(e, '简历上传失败，请检查文件后重试。'));
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="h-full overflow-y-auto px-6 py-6">
      <div className="max-w-3xl mx-auto space-y-6">
        <div>
          <h1 className="text-2xl font-semibold text-ink-900">简历上传</h1>
          <p className="text-sm text-ink-500 mt-1">支持 PDF / DOCX / TXT / Markdown，≤5MB。上传后自动脱敏（手机/邮箱/身份证）+ LLM 结构化</p>
        </div>

        <div className="card p-6">
          <label className="block">
            <div className="text-sm font-medium text-ink-700 mb-2">选择文件</div>
            <input
              type="file"
              onChange={e => setFile(e.target.files?.[0] ?? null)}
              className="block w-full text-sm text-ink-700 file:mr-3 file:py-2 file:px-4 file:rounded file:border-0 file:bg-accent-50 file:text-accent-700 hover:file:bg-accent-500 hover:file:text-white file:cursor-pointer"
            />
          </label>
          <div className="mt-2 text-xs text-ink-400">文件选择器展示全部文件；服务端仅接受 PDF、DOCX、TXT、Markdown。</div>
          {file && (
            <div className="mt-3 text-sm text-ink-700">
              已选择: <span className="font-medium">{file.name}</span> ({(file.size / 1024).toFixed(1)} KB)
            </div>
          )}
          <button
            disabled={!file || loading}
            onClick={upload}
            className="mt-4 px-5 py-2.5 bg-accent-500 hover:bg-accent-600 disabled:bg-ink-200 text-white rounded-md font-medium text-sm"
          >
            {loading ? '解析中…' : '上传并解析'}
          </button>
          {err && <div className="mt-3 text-sm text-red-600">{err}</div>}
        </div>

        {result && (
          <div className="card p-6 space-y-4">
            <div className="flex items-center justify-between">
              <h2 className="text-lg font-semibold text-ink-900">结构化结果</h2>
              <span className="text-xs text-ink-500">resume #{result.resume_id} · 已脱敏 {result.masked_pii_count} 处</span>
            </div>
            {result.structured.summary && <p className="text-sm text-ink-700">{result.structured.summary}</p>}
            {result.structured.skills && result.structured.skills.length > 0 && (
              <div>
                <div className="text-xs text-ink-500 mb-1">抽取技能</div>
                <div className="flex flex-wrap gap-1.5">
                  {result.structured.skills.map((s, i) => (
                    <span key={i} className="px-2 py-1 bg-accent-50 text-accent-700 text-xs rounded">{s}</span>
                  ))}
                </div>
              </div>
            )}
            {result.structured.education && result.structured.education.length > 0 && (
              <div>
                <div className="text-xs text-ink-500 mb-1">教育</div>
                <ul className="text-sm text-ink-700 space-y-0.5">
                  {result.structured.education.map((education, i) => <li key={i}>• {describeRecord(education, ['school', 'degree', 'major', 'period'])}</li>)}
                </ul>
              </div>
            )}
            {result.structured.projects && result.structured.projects.length > 0 && (
              <div>
                <div className="text-xs text-ink-500 mb-1">项目</div>
                <ul className="text-sm text-ink-700 space-y-0.5">
                  {result.structured.projects.map((project, i) => <li key={i}>• {describeRecord(project, ['name', 'role', 'description'])}</li>)}
                </ul>
              </div>
            )}
          </div>
        )}
      </div>
    </div>
  );
}

function describeRecord(record: Record<string, unknown>, keys: string[]): string {
  const values = keys.flatMap(key => typeof record[key] === 'string' && record[key].trim() ? [record[key]] : []);
  return values.length > 0 ? values.join(' · ') : '未提供详情';
}
