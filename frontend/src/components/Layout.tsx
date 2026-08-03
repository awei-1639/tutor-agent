import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { clearToken, getUserId } from '../lib/api';
import { useEffect, useState } from 'react';

const NAV = [
  { to: '/chat', label: '智能对话', icon: 'chat' },
  { to: '/profile', label: '个人画像', icon: 'profile' },
  { to: '/resume', label: '简历档案', icon: 'resume' },
  { to: '/notifications', label: '机会推送', icon: 'bell' },
  { to: '/plans', label: '成长计划', icon: 'calendar' },
  { to: '/interview', label: '模拟面试', icon: 'mic' },
];

export default function Layout() {
  const nav = useNavigate();
  const [name, setName] = useState('');

  useEffect(() => {
    setName(localStorage.getItem('tutor_user_name') ?? '');
    if (!localStorage.getItem('tutor_token')) nav('/login');
  }, [nav]);

  return (
    <div className="app-shell flex h-screen">
      <aside className="app-sidebar w-64 text-white flex flex-col relative overflow-hidden">
        <div className="px-5 py-6 border-b border-white/10 relative">
          <div className="flex items-center gap-3">
            <div className="brand-mark h-10 w-10 rounded-xl flex items-center justify-center text-lg font-black">T</div>
            <div>
              <div className="text-[15px] font-semibold tracking-tight">学习与求职助手</div>
              <div className="text-[10px] text-white/45 mt-0.5 tracking-[.14em] uppercase">Personal workspace</div>
            </div>
          </div>
        </div>
        <nav className="flex-1 p-3 pt-5 space-y-1 relative">
          <div className="px-3 pb-2 text-[10px] font-medium tracking-[.16em] text-white/35 uppercase">Workspace</div>
          {NAV.map(n => (
            <NavLink
              key={n.to}
              to={n.to}
              className={({ isActive }) =>
                `flex items-center gap-3 px-3 py-2.5 rounded-xl text-sm transition ${
                  isActive ? 'bg-white/13 text-white font-medium shadow-[inset_0_1px_0_rgba(255,255,255,.12)]' : 'text-white/60 hover:text-white hover:bg-white/8'
                }`}
              >
              <NavIcon name={n.icon} />
              <span>{n.label}</span>
            </NavLink>
          ))}
        </nav>
        <div className="p-4 border-t border-white/10 relative">
          <div className="flex items-center gap-2.5 rounded-xl bg-white/7 p-2.5">
            <div className="h-8 w-8 rounded-lg bg-accent-600 flex items-center justify-center text-xs font-bold">{(name || 'U').slice(0, 1).toUpperCase()}</div>
            <div className="min-w-0 flex-1">
              <div className="text-xs text-white font-medium truncate">{name || '用户'}</div>
              <div className="text-[10px] text-white/40 mt-0.5">ID · {getUserId()}</div>
            </div>
          </div>
          <button
            onClick={() => { clearToken(); nav('/login'); }}
            className="mt-3 px-2 text-xs text-white/45 hover:text-white transition"
          >
            退出登录
          </button>
        </div>
      </aside>
      <main className="flex-1 overflow-hidden">
        <Outlet />
      </main>
    </div>
  );
}

function NavIcon({ name }: { name: string }) {
  const path: Record<string, React.ReactNode> = {
    chat: <><path d="M20 15a4 4 0 0 1-4 4H8l-4 3v-7a4 4 0 0 1-2-3.5V7a4 4 0 0 1 4-4h10a4 4 0 0 1 4 4z" /><path d="M7 9h6M7 13h4" /></>,
    profile: <><circle cx="12" cy="8" r="3" /><path d="M5 21a7 7 0 0 1 14 0" /></>,
    resume: <><path d="M7 3h7l4 4v14H7z" /><path d="M14 3v5h5M10 12h5M10 16h5" /></>,
    bell: <><path d="M18 9a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4" /></>,
    calendar: <><rect x="3" y="5" width="18" height="16" rx="2" /><path d="M16 3v4M8 3v4M3 11h18" /></>,
    mic: <><rect x="9" y="3" width="6" height="11" rx="3" /><path d="M5 11a7 7 0 0 0 14 0M12 18v3M8 21h8" /></>,
  };
  return <svg className="w-[18px] h-[18px] shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">{path[name]}</svg>;
}
