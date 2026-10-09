import { NavLink, Outlet, useNavigate } from 'react-router-dom';
import { api, clearToken, getUserId, getUserRole, hasSessionHint } from '../lib/api';
import { groupConvs, useChatConversations } from '../lib/chatConversations';
import { useEffect, useState } from 'react';

const NAV = [
  { to: '/chat', label: '智能对话', icon: 'chat' },
  { to: '/profile', label: '个人画像', icon: 'profile' },
  { to: '/memories', label: '跨会话记忆', icon: 'memory' },
  { to: '/resume', label: '简历档案', icon: 'resume' },
  { to: '/notifications', label: '机会推送', icon: 'bell' },
  { to: '/plans', label: '成长计划', icon: 'calendar' },
  { to: '/interview', label: '模拟面试', icon: 'mic' },
  { to: '/rag-eval', label: 'RAG 评测', icon: 'eval' },
];

export default function Layout() {
  const nav = useNavigate();
  const [name] = useState(() => localStorage.getItem('tutor_user_name') ?? '');
  const [mobileNavOpen, setMobileNavOpen] = useState(false);
  const [search, setSearch] = useState('');
  const isAdmin = getUserRole() === 'ADMIN';
  const { convs, currentId } = useChatConversations();
  // 按标题过滤（纯前端）；空查询时展示全部分组。
  const q = search.trim().toLowerCase();
  const visibleConvs = q ? convs.filter(c => (c.title || '').toLowerCase().includes(q)) : convs;

  useEffect(() => {
    if (!hasSessionHint()) nav('/login');
  }, [nav]);

  // 通过 location.state 把指令带给 ChatPage：选中会话 / 开启新会话。
  function openConversation(id: number) {
    setMobileNavOpen(false);
    nav('/chat', { state: { selectConv: id } });
  }

  function startNewChat() {
    setMobileNavOpen(false);
    nav('/chat', { state: { newChat: true } });
  }

  return (
    <div className="app-shell flex h-screen min-h-0">
      {mobileNavOpen && <button
        type="button"
        aria-label="关闭导航菜单"
        className="fixed inset-0 z-40 bg-ink-900/40 md:hidden"
        onClick={() => setMobileNavOpen(false)}
      />}
      <aside id="app-navigation" className={`app-sidebar fixed inset-y-0 left-0 z-50 flex w-64 shrink-0 -translate-x-full flex-col overflow-hidden text-white transition-transform duration-200 md:static md:z-auto md:translate-x-0 ${mobileNavOpen ? 'translate-x-0' : ''}`}>
        <div className="px-4 py-4 border-b border-white/10 relative">
          <div className="flex items-center gap-3">
            <div className="brand-mark h-8 w-8 rounded-[9px] flex items-center justify-center text-[15px] font-bold">T</div>
            <div>
              <div className="font-serif text-[15px] text-[#efeeea] tracking-[.01em]">学习与求职助手</div>
              <div className="text-[10px] text-white/45 mt-0.5">个人成长工作台</div>
            </div>
          </div>
        </div>
        <div className="px-3 pt-2.5 shrink-0 relative">
          <button
            type="button"
            onClick={startNewChat}
            className="w-full flex items-center justify-center gap-2 px-3 py-2 rounded-[10px] border border-white/20 text-[13px] font-semibold text-white/90 transition hover:bg-white/10 hover:text-white"
          >
            <span className="text-base leading-none">+</span><span>开启新对话</span>
          </button>
        </div>
        <nav className="shrink-0 px-2 pt-2 space-y-px relative">
          <div className="px-3 pb-1 editorial-kicker text-white/35">工作区</div>
          {NAV.map(n => (
            <NavLink
              key={n.to}
              to={n.to}
              onClick={() => setMobileNavOpen(false)}
              className={({ isActive }) =>
                  `flex items-center gap-2.5 px-3 py-1.5 rounded-sm text-[12.5px] transition ${
                  isActive ? 'bg-white/10 text-white font-medium' : 'text-white/55 hover:text-white hover:bg-white/6'
                }`}
              >
              <NavIcon name={n.icon} />
              <span>{n.label}</span>
            </NavLink>
          ))}
          {isAdmin && (
            <>
              <div className="px-3 pt-3 pb-1 editorial-kicker text-white/35">管理</div>
              <NavLink
                to="/admin"
                onClick={() => setMobileNavOpen(false)}
                className={({ isActive }) =>
                    `flex items-center gap-2.5 px-3 py-1.5 rounded-sm text-[12.5px] transition ${
                    isActive ? 'bg-white/10 text-white font-medium' : 'text-white/55 hover:text-white hover:bg-white/6'
                  }`}
              >
                <NavIcon name="admin" />
                <span>管理端</span>
              </NavLink>
              <NavLink
                to="/admin/documents"
                onClick={() => setMobileNavOpen(false)}
                className={({ isActive }) =>
                    `flex items-center gap-2.5 px-3 py-1.5 rounded-sm text-[12.5px] transition ${
                    isActive ? 'bg-white/10 text-white font-medium' : 'text-white/55 hover:text-white hover:bg-white/6'
                  }`}
              >
                <NavIcon name="knowledge" />
                <span>知识库</span>
              </NavLink>
            </>
          )}
        </nav>
        <div className="flex min-h-0 flex-1 flex-col mt-1 border-t border-white/10 relative">
          <div className="px-5 pt-2.5 pb-1.5 editorial-kicker text-white/35 shrink-0">对话记录</div>
          <div className="px-3 pb-1.5 shrink-0">
            <input
              type="search"
              value={search}
              onChange={e => setSearch(e.target.value)}
              placeholder="搜索对话标题"
              aria-label="搜索对话"
              className="w-full rounded-md bg-white/6 border border-transparent px-2.5 py-1.5 text-xs text-white placeholder:text-white/30 outline-none transition focus:border-white/20"
            />
          </div>
          <div className="flex-1 overflow-y-auto px-2 pb-2 space-y-3">
            {visibleConvs.length === 0 && (
              <div className="px-3 py-2 text-[11px] text-white/30">{q ? '无匹配对话' : '暂无历史对话'}</div>
            )}
            {groupConvs(visibleConvs).map(g => (
              <div key={g.label}>
                <div className="px-3 py-1 text-[10px] font-semibold text-white/30 uppercase tracking-[.12em]">{g.label}</div>
                <div className="space-y-0.5">
                  {g.items.map(c => (
                    <button
                      key={c.id}
                      type="button"
                      onClick={() => openConversation(c.id)}
                      className={`w-full text-left px-3 py-2 rounded-md text-xs truncate transition ${
                        currentId === c.id ? 'bg-white/10 text-white font-medium' : 'text-white/55 hover:text-white hover:bg-white/6'
                      }`}
                    >
                      {c.title || '(无标题)'}
                    </button>
                  ))}
                </div>
              </div>
            ))}
          </div>
        </div>
        <div className="p-4 border-t border-white/10 relative">
          <div className="flex items-center gap-2.5 rounded-md bg-white/6 p-2">
            <div className="h-7 w-7 rounded-full bg-white/15 flex items-center justify-center text-[11px] font-bold">{(name || 'U').slice(0, 1).toUpperCase()}</div>
            <div className="min-w-0 flex-1">
              <div className="text-xs text-white font-medium truncate">{name || '用户'}</div>
              <div className="text-[10px] text-white/40 mt-0.5">ID · {getUserId()}</div>
            </div>
          </div>
          <button
            type="button"
            onClick={() => { void api.logout().catch(() => undefined); clearToken(); nav('/login'); }}
            className="mt-2 min-h-9 px-2 text-left text-xs text-white/40 transition hover:text-white"
          >
            退出登录
          </button>
        </div>
      </aside>
      <main className="flex min-w-0 min-h-0 flex-1 flex-col overflow-hidden">
        <div className="flex h-14 shrink-0 items-center gap-3 border-b border-ink-100 bg-white px-4 md:hidden">
          <button
            type="button"
            aria-label="打开导航菜单"
            aria-expanded={mobileNavOpen}
            aria-controls="app-navigation"
            onClick={() => setMobileNavOpen(true)}
            className="inline-flex min-h-11 min-w-11 items-center justify-center rounded-md border border-ink-200 text-ink-700 hover:bg-ink-50 focus:outline-none focus:ring-2 focus:ring-accent-500/30"
          >
            <svg aria-hidden="true" className="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
              <path d="M4 6h16M4 12h16M4 18h16" />
            </svg>
          </button>
          <span className="truncate text-sm font-semibold text-ink-900">学习与求职助手</span>
        </div>
        <div className="min-h-0 flex-1 overflow-hidden">
          <Outlet />
        </div>
      </main>
    </div>
  );
}

function NavIcon({ name }: { name: string }) {
  const path: Record<string, React.ReactNode> = {
    chat: <><path d="M20 15a4 4 0 0 1-4 4H8l-4 3v-7a4 4 0 0 1-2-3.5V7a4 4 0 0 1 4-4h10a4 4 0 0 1 4 4z" /><path d="M7 9h6M7 13h4" /></>,
    profile: <><circle cx="12" cy="8" r="3" /><path d="M5 21a7 7 0 0 1 14 0" /></>,
    memory: <><path d="M6 5.5A2.5 2.5 0 0 1 8.5 3h7A2.5 2.5 0 0 1 18 5.5v13A2.5 2.5 0 0 1 15.5 21h-7A2.5 2.5 0 0 1 6 18.5z" /><path d="M9 8h6M9 12h6M9 16h3" /></>,
    resume: <><path d="M7 3h7l4 4v14H7z" /><path d="M14 3v5h5M10 12h5M10 16h5" /></>,
    bell: <><path d="M18 9a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9M10 21h4" /></>,
    calendar: <><rect x="3" y="5" width="18" height="16" rx="2" /><path d="M16 3v4M8 3v4M3 11h18" /></>,
    mic: <><rect x="9" y="3" width="6" height="11" rx="3" /><path d="M5 11a7 7 0 0 0 14 0M12 18v3M8 21h8" /></>,
    eval: <><path d="M9 3h6M10 3v4l-4.5 8.5A2 2 0 0 0 7.2 18h9.6a2 2 0 0 0 1.7-2.5L14 7V3" /><path d="M8 13h8" /><path d="M18 5h3M19.5 3.5v3" /></>,
    admin: <><rect x="4" y="4" width="16" height="16" rx="2" /><path d="M8 9h8M8 13h5M8 17h3" /></>,
    knowledge: <><path d="M4 5.5A2.5 2.5 0 0 1 6.5 3H11v17H6.5A2.5 2.5 0 0 0 4 22zM20 5.5A2.5 2.5 0 0 0 17.5 3H13v17h4.5A2.5 2.5 0 0 1 20 22z" /></>,
  };
  return <svg className="w-4 h-4 shrink-0" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">{path[name]}</svg>;
}
