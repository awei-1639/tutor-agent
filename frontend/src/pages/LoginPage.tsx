import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api, setToken } from '../lib/api';

/**
 * Login (dev 单用户模式): 输入昵称 → POST /auth/login → 存 token → 跳 /chat。
 * 真实场景接 OAuth/短信验证码。
 */
export default function LoginPage() {
  const nav = useNavigate();
  const [name, setName] = useState('');
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState('');

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!name.trim()) return;
    setLoading(true);
    setErr('');
    try {
      const r = await api.login(name.trim());
      setToken(r.token, r.user_id, name.trim());
      nav('/chat');
    } catch (ex: any) {
      setErr(ex.message ?? '登录失败');
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="h-full flex items-center justify-center bg-gradient-to-br from-ink-50 to-white">
      <form onSubmit={submit} className="w-96 bg-white rounded-xl shadow-lift border border-ink-100 p-8">
        <div className="text-2xl font-semibold text-ink-900 mb-1">欢迎回来</div>
        <div className="text-sm text-ink-500 mb-6">输入昵称即可登录（dev 单用户模式）</div>
        <input
          autoFocus
          value={name}
          onChange={e => setName(e.target.value)}
          placeholder="例如：小明"
          className="w-full px-4 py-3 border border-ink-200 rounded-md focus:outline-none focus:ring-2 focus:ring-accent-500/30 focus:border-accent-500"
        />
        {err && <div className="mt-2 text-sm text-red-600">{err}</div>}
        <button
          type="submit"
          disabled={loading || !name.trim()}
          className="w-full mt-4 px-4 py-3 bg-accent-500 hover:bg-accent-600 disabled:bg-ink-200 text-white rounded-md font-medium transition"
        >
          {loading ? '登录中…' : '进入助手'}
        </button>
        <div className="mt-6 text-xs text-ink-500 leading-relaxed">
          本地开发模式直连 <code className="px-1 bg-ink-100 rounded">/api</code> 代理到后端 <code className="px-1 bg-ink-100 rounded">localhost:8180</code>
        </div>
      </form>
    </div>
  );
}