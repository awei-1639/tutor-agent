import { Suspense, lazy } from 'react';
import { Navigate, Route, Routes } from 'react-router-dom';
import Layout from './components/Layout';
import ChatPage from './pages/ChatPage';
import LoginPage from './pages/LoginPage';
import { hasSessionHint } from './lib/api';
import { ChatConversationsProvider } from './lib/chatConversations';

// 路由级代码分割 (诊断报告 2026-09-10 §修复清单 #10): LoginPage (首屏) 与
// ChatPage (默认落地页) 保持同步加载, 其余页面按需加载, /admin 等重页面不再拖累首包。
const InterviewPage = lazy(() => import('./pages/InterviewPage'));
const NotificationsPage = lazy(() => import('./pages/NotificationsPage'));
const PlansPage = lazy(() => import('./pages/PlansPage'));
const ProfilePage = lazy(() => import('./pages/ProfilePage'));
const ResumePage = lazy(() => import('./pages/ResumePage'));
const RagEvalPage = lazy(() => import('./pages/RagEvalPage'));
const AdminPage = lazy(() => import('./pages/AdminPage'));
const KnowledgeBasePage = lazy(() => import('./pages/KnowledgeBasePage'));
const MemoryPage = lazy(() => import('./pages/MemoryPage'));

function RequireAuth({ children }: { children: JSX.Element }) {
  if (!hasSessionHint()) return <Navigate to="/login" replace />;
  return children;
}

function PageLoading() {
  return (
    <div className="flex h-full items-center justify-center text-sm text-slate-500">
      页面加载中…
    </div>
  );
}

export default function App() {
  return (
    <Suspense fallback={<PageLoading />}>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route element={<RequireAuth><ChatConversationsProvider><Layout /></ChatConversationsProvider></RequireAuth>}>
          <Route index element={<Navigate to="/chat" replace />} />
          <Route path="/chat" element={<ChatPage />} />
          <Route path="/profile" element={<ProfilePage />} />
          <Route path="/memories" element={<MemoryPage />} />
          <Route path="/resume" element={<ResumePage />} />
          <Route path="/notifications" element={<NotificationsPage />} />
          <Route path="/plans" element={<PlansPage />} />
          <Route path="/interview" element={<InterviewPage />} />
          <Route path="/rag-eval" element={<RagEvalPage />} />
          <Route path="/admin" element={<AdminPage />} />
          <Route path="/admin/documents" element={<KnowledgeBasePage />} />
        </Route>
        <Route path="*" element={<Navigate to="/chat" replace />} />
      </Routes>
    </Suspense>
  );
}
