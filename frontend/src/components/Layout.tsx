import { NavLink, Outlet, useNavigate, useLocation } from 'react-router-dom';
import { clearToken, getUserId } from '../lib/api';
import { useEffect, useState } from 'react';

const NAV = [
  { to: '/chat', label: '对话', icon: '💬' },
  { to: '/profile', label: '画像', icon: '👤' },
  { to: '/resume', label: '简历', icon: '📄' },
  { to: '/notifications', label: '推送', icon: '🔔' },
  { to: '/plans', label: '计划', icon: '📅' },
  { to: '/interview', label: '面试', icon: '🎤' },
];

export default function Layout() {
  const nav = useNavigate();
  const loc = useLocation();
  const [name, setName] = useState('');

  useEffect(() => {
    setName(localStorage.getItem('tutor_user_name') ?? '');
    if (!localStorage.getItem('tutor_token')) nav('/login');
  }, [nav]);

  // Chat 页面自带对话列表侧栏, 这里不显示导航让位
  const slim = loc.pathname === '/chat';

  return (
    <div className="flex h-screen bg-ink-50">
      {!slim && (
        <aside className="w-56 bg-white border-r border-ink-100 flex flex-col">
          <div className="px-5 py-5 border-b border-ink-100">
            <div className="text-base font-semibold text-ink-900">学习与求职助手</div>
            <div className="text-xs text-ink-500 mt-1">Personal AI Tutor</div>
          </div>
          <nav className="flex-1 p-3 space-y-1">
            {NAV.map(n => (
              <NavLink
                key={n.to}
                to={n.to}
                className={({ isActive }) =>
                  `flex items-center gap-3 px-3 py-2 rounded-md text-sm transition ${
                    isActive ? 'bg-accent-50 text-accent-700 font-medium' : 'text-ink-700 hover:bg-ink-50'
                  }`}
              >
                <span className="text-base">{n.icon}</span>
                <span>{n.label}</span>
              </NavLink>
            ))}
          </nav>
          <div className="p-3 border-t border-ink-100">
            <div className="text-xs text-ink-500 mb-1">已登录</div>
            <div className="text-sm text-ink-900 font-medium">{name || '用户'} #{getUserId()}</div>
            <button
              onClick={() => { clearToken(); nav('/login'); }}
              className="mt-2 text-xs text-ink-500 hover:text-ink-900"
            >
              退出登录
            </button>
          </div>
        </aside>
      )}
      <main className="flex-1 overflow-hidden relative">
        {!slim && (
          <div className="absolute top-3 right-4 z-40 flex items-center gap-3">
            <span className="text-xs text-ink-500">{name || '用户'} #{getUserId()}</span>
            <button onClick={() => { clearToken(); nav('/login'); }}
                    className="text-xs text-ink-500 hover:text-ink-900 px-2 py-1 rounded hover:bg-ink-100">
              退出
            </button>
          </div>
        )}
        <Outlet />
      </main>
    </div>
  );
}