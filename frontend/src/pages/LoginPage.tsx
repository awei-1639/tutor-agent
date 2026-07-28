import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api, setToken } from '../lib/api';

/**
 * 登录/注册页 (Phase 4 V4 4.x): 单一表单, mode 切换。
 * - login: POST /auth/login (email + password)
 * - register: POST /auth/register (email + password + name)
 * - dev 模式 (向后兼容): 留 dev-login 按钮, 输入昵称直接进
 */
export default function LoginPage() {
  const nav = useNavigate();
  const [mode, setMode] = useState<'login' | 'register'>('login');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [name, setName] = useState('');
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState('');

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setErr(''); setLoading(true);
    try {
      const r = mode === 'register'
        ? await api.register(email.trim(), password, name.trim() || email.split('@')[0])
        : await api.login(email.trim(), password);
      setToken(r.token, r.user_id, r.name || name.trim() || email.split('@')[0]);
      nav('/chat');
    } catch (ex: any) {
      setErr(ex.message ?? (mode === 'register' ? '注册失败' : '登录失败'));
    } finally {
      setLoading(false);
    }
  }

  async function devLogin() {
    if (!name.trim()) return;
    setLoading(true); setErr('');
    try {
      const r = await api.devLogin(name.trim());
      setToken(r.token, r.user_id, r.name);
      nav('/chat');
    } catch (ex: any) {
      setErr(ex.message ?? 'dev 登录失败');
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="h-full flex items-center justify-center bg-gradient-to-br from-ink-50 to-white">
      <div className="w-96 bg-white rounded-xl shadow-lift border border-ink-100 p-8">
        <div className="text-2xl font-semibold text-ink-900 mb-1">欢迎回来</div>
        <div className="text-sm text-ink-500 mb-6">
          {mode === 'login' ? '登录继续你的学习' : '创建账号开始'}
        </div>

        {/* Tab 切换 */}
        <div className="flex gap-1 mb-5 p-1 bg-ink-50 rounded-md">
          <button
            type="button"
            onClick={() => setMode('login')}
            className={`flex-1 py-1.5 text-sm rounded ${mode === 'login' ? 'bg-white text-ink-900 shadow-soft font-medium' : 'text-ink-500'}`}
          >
            登录
          </button>
          <button
            type="button"
            onClick={() => setMode('register')}
            className={`flex-1 py-1.5 text-sm rounded ${mode === 'register' ? 'bg-white text-ink-900 shadow-soft font-medium' : 'text-ink-500'}`}
          >
            注册
          </button>
        </div>

        <form onSubmit={submit} className="space-y-3">
          {mode === 'register' && (
            <input
              value={name}
              onChange={e => setName(e.target.value)}
              placeholder="昵称 (可选, 默认取邮箱前缀)"
              className="w-full px-4 py-3 border border-ink-200 rounded-md focus:outline-none focus:ring-2 focus:ring-accent-500/30 focus:border-accent-500"
            />
          )}
          <input
            type="email"
            value={email}
            onChange={e => setEmail(e.target.value)}
            placeholder="邮箱"
            autoComplete="email"
            required
            className="w-full px-4 py-3 border border-ink-200 rounded-md focus:outline-none focus:ring-2 focus:ring-accent-500/30 focus:border-accent-500"
          />
          <input
            type="password"
            value={password}
            onChange={e => setPassword(e.target.value)}
            placeholder={mode === 'register' ? '密码 (≥6 字符)' : '密码'}
            autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
            required
            minLength={6}
            className="w-full px-4 py-3 border border-ink-200 rounded-md focus:outline-none focus:ring-2 focus:ring-accent-500/30 focus:border-accent-500"
          />
          {err && <div className="text-sm text-red-600 bg-red-50 px-3 py-2 rounded">{err}</div>}
          <button
            type="submit"
            disabled={loading || !email.trim() || !password}
            className="w-full px-4 py-3 bg-accent-500 hover:bg-accent-600 disabled:bg-ink-200 text-white rounded-md font-medium transition"
          >
            {loading ? '处理中…' : mode === 'login' ? '登录' : '注册并登录'}
          </button>
        </form>

        <div className="mt-5 pt-5 border-t border-ink-100">
          <div className="text-xs text-ink-500 mb-2">dev 模式 (单用户快速进入)</div>
          <div className="flex gap-2">
            <input
              value={name}
              onChange={e => setName(e.target.value)}
              placeholder="昵称"
              className="flex-1 px-3 py-2 border border-ink-200 rounded-md text-sm"
            />
            <button
              type="button"
              onClick={devLogin}
              disabled={loading || !name.trim()}
              className="px-3 py-2 bg-ink-100 hover:bg-ink-200 disabled:bg-ink-50 text-ink-700 rounded-md text-sm"
            >
              dev 进入
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}