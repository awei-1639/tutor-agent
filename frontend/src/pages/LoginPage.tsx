import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api, setSessionHint, toUserMessage } from '../lib/api';

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
      setSessionHint(r.user_id, r.name || name.trim() || email.split('@')[0], r.role);
      nav('/chat');
    } catch (ex: unknown) {
      setErr(toUserMessage(ex, mode === 'register' ? '注册失败，请稍后重试。' : '登录失败，请稍后重试。'));
    } finally {
      setLoading(false);
    }
  }

  async function devLogin() {
    if (!name.trim()) return;
    setLoading(true); setErr('');
    try {
      const r = await api.devLogin(name.trim());
      setSessionHint(r.user_id, r.name, r.role);
      nav('/chat');
    } catch (ex: unknown) {
      setErr(toUserMessage(ex, '开发登录失败，请稍后重试。'));
    } finally {
      setLoading(false);
    }
  }

  return (
    <div className="login-shell relative h-full min-h-[680px] flex items-center justify-center overflow-hidden p-6">
      <div className="absolute -left-24 top-20 h-72 w-72 rounded-full bg-accent-300/20 blur-3xl" />
      <div className="relative grid w-full max-w-5xl overflow-hidden rounded-[28px] border border-white/70 bg-white/78 shadow-[0_32px_90px_rgba(45,35,107,.18)] backdrop-blur-xl lg:grid-cols-[1.05fr_.95fr]">
        <section className="hidden min-h-[620px] flex-col justify-between bg-[#20184d] p-12 text-white lg:flex">
          <div className="flex items-center gap-3"><div className="brand-mark h-11 w-11 rounded-xl flex items-center justify-center text-lg font-black">T</div><span className="font-semibold">学习与求职助手</span></div>
          <div><h1 className="max-w-md text-5xl font-semibold leading-[1.08] tracking-[-.035em]">让每一次投入，都成为更好的自己。</h1><p className="mt-6 max-w-sm text-sm leading-7 text-white/60">把学习、项目与职业选择放进同一个清晰的成长工作台。</p></div>
          <div className="flex gap-7 text-xs text-white/55"><span>AI 深度陪练</span><span>可溯源建议</span><span>持续成长</span></div>
        </section>
        <div className="relative w-full p-8 sm:p-12">
        <div className="flex items-center gap-3 mb-10 lg:hidden">
          <div className="brand-mark h-11 w-11 rounded-xl text-white flex items-center justify-center text-lg font-black">T</div>
          <div><div className="text-sm font-semibold text-ink-900">学习与求职助手</div><div className="text-[11px] text-ink-500 mt-0.5">你的成长工作台</div></div>
        </div>
        <div className="text-3xl font-semibold tracking-[-.03em] text-ink-900 mb-2">{mode === 'login' ? '欢迎回来' : '创建你的工作台'}</div>
        <div className="text-sm text-ink-500 mb-6 leading-relaxed">
          {mode === 'login' ? '登录继续你的学习' : '创建账号开始'}
        </div>

        {/* Tab 切换 */}
        <div className="flex gap-1 mb-7 p-1 bg-ink-100/70 rounded-xl">
          <button
            type="button"
            onClick={() => setMode('login')}
            className={`flex-1 py-2.5 text-sm rounded-lg transition ${mode === 'login' ? 'bg-white text-ink-900 shadow-soft font-semibold' : 'text-ink-500 hover:text-ink-700'}`}
          >
            登录
          </button>
          <button
            type="button"
            onClick={() => setMode('register')}
            className={`flex-1 py-2.5 text-sm rounded-lg transition ${mode === 'register' ? 'bg-white text-ink-900 shadow-soft font-semibold' : 'text-ink-500 hover:text-ink-700'}`}
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
              className="w-full px-4 py-3 border border-ink-200 bg-ink-50/50 rounded-xl focus:outline-none focus:ring-4 focus:ring-accent-500/15 focus:border-accent-500 transition"
            />
          )}
          <input
            type="email"
            value={email}
            onChange={e => setEmail(e.target.value)}
            placeholder="邮箱"
            autoComplete="email"
            required
            className="w-full px-4 py-3 border border-ink-200 bg-ink-50/50 rounded-xl focus:outline-none focus:ring-4 focus:ring-accent-500/15 focus:border-accent-500 transition"
          />
          <input
            type="password"
            value={password}
            onChange={e => setPassword(e.target.value)}
            placeholder={mode === 'register' ? '密码 (≥6 字符)' : '密码'}
            autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
            required
            minLength={6}
            className="w-full px-4 py-3 border border-ink-200 bg-ink-50/50 rounded-xl focus:outline-none focus:ring-4 focus:ring-accent-500/15 focus:border-accent-500 transition"
          />
          {err && <div className="text-sm text-red-600 bg-red-50 px-3 py-2 rounded">{err}</div>}
          <button
            type="submit"
            disabled={loading || !email.trim() || !password}
            className="w-full px-4 py-3.5 bg-accent-600 hover:bg-accent-700 disabled:bg-ink-200 text-white rounded-xl font-semibold shadow-[0_12px_22px_rgba(96,69,213,.25)] transition"
          >
            {loading ? '处理中…' : mode === 'login' ? '登录' : '注册并登录'}
          </button>
        </form>

        <div className="mt-6 pt-5 border-t border-ink-100">
          <div className="text-xs text-ink-500 mb-2">开发模式 · 单用户快速进入</div>
          <div className="flex gap-2">
            <input
              value={name}
              onChange={e => setName(e.target.value)}
              placeholder="昵称"
              className="flex-1 px-3 py-2 border border-ink-200 bg-ink-50/50 rounded-lg text-sm"
            />
            <button
              type="button"
              onClick={devLogin}
              disabled={loading || !name.trim()}
              className="px-3 py-2 bg-ink-100 hover:bg-ink-200 disabled:bg-ink-50 text-ink-700 rounded-lg text-sm transition"
            >
              dev 进入
            </button>
          </div>
        </div>
        </div>
      </div>
    </div>
  );
}
