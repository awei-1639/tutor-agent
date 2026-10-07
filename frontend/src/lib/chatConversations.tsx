import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react';
import { api, type ConversationSummary } from './api';

interface ChatConversationsContextValue {
  convs: ConversationSummary[];
  currentId: number | null;
  refresh: () => void;
  setCurrentId: (id: number | null) => void;
}

const ChatConversationsContext = createContext<ChatConversationsContextValue | null>(null);

// 会话列表提升到 Layout 层级：侧边栏常驻展示对话记录，ChatPage 与侧边栏共享同一份数据。
export function ChatConversationsProvider({ children }: { children: ReactNode }) {
  const [convs, setConvs] = useState<ConversationSummary[]>([]);
  const [currentId, setCurrentId] = useState<number | null>(null);
  const refresh = useCallback(() => {
    api.listConversations().then(setConvs).catch(() => {});
  }, []);
  useEffect(() => { refresh(); }, [refresh]);
  const value = useMemo(
    () => ({ convs, currentId, refresh, setCurrentId }),
    [convs, currentId, refresh],
  );
  return <ChatConversationsContext.Provider value={value}>{children}</ChatConversationsContext.Provider>;
}

export function useChatConversations(): ChatConversationsContextValue {
  const ctx = useContext(ChatConversationsContext);
  if (!ctx) throw new Error('useChatConversations must be used within ChatConversationsProvider');
  return ctx;
}

// 对话分组 (Qwen 风格: 今天 / 昨天 / 过去 7 天 / 过去 30 天)
export function groupConvs(convs: ConversationSummary[]): { label: string; items: ConversationSummary[] }[] {
  const now = new Date();
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
  const yesterday = today - 86400000;
  const week = today - 7 * 86400000;
  const month = today - 30 * 86400000;
  const groups = { 今天: [] as ConversationSummary[], 昨天: [] as ConversationSummary[], '过去 7 天': [] as ConversationSummary[], '过去 30 天': [] as ConversationSummary[] };
  for (const c of convs) {
    const t = c.last_active_at ? new Date(c.last_active_at).getTime() : 0;
    if (t >= today) groups.今天.push(c);
    else if (t >= yesterday) groups.昨天.push(c);
    else if (t >= week) groups['过去 7 天'].push(c);
    else if (t >= month) groups['过去 30 天'].push(c);
  }
  return (['今天', '昨天', '过去 7 天', '过去 30 天'] as const)
    .filter(k => groups[k].length > 0)
    .map(k => ({ label: k, items: groups[k] }));
}
