import { Navigate, Route, Routes } from 'react-router-dom';
import Layout from './components/Layout';
import ChatPage from './pages/ChatPage';
import InterviewPage from './pages/InterviewPage';
import LoginPage from './pages/LoginPage';
import NotificationsPage from './pages/NotificationsPage';
import PlansPage from './pages/PlansPage';
import ProfilePage from './pages/ProfilePage';
import ResumePage from './pages/ResumePage';

function RequireAuth({ children }: { children: JSX.Element }) {
  if (!localStorage.getItem('tutor_token')) return <Navigate to="/login" replace />;
  return children;
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route element={<RequireAuth><Layout /></RequireAuth>}>
        <Route index element={<Navigate to="/chat" replace />} />
        <Route path="/chat" element={<ChatPage />} />
        <Route path="/profile" element={<ProfilePage />} />
        <Route path="/resume" element={<ResumePage />} />
        <Route path="/notifications" element={<NotificationsPage />} />
        <Route path="/plans" element={<PlansPage />} />
        <Route path="/interview" element={<InterviewPage />} />
      </Route>
      <Route path="*" element={<Navigate to="/chat" replace />} />
    </Routes>
  );
}