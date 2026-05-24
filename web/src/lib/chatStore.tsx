import { createContext, MutableRefObject, ReactNode, useCallback, useContext, useRef, useState } from 'react';

export interface ChatEvent {
  type: string;
  data: any;
}

export interface ChatRunState {
  runId: string;
  sessionId: string | null;
  startedAt: number;
  abortController: AbortController;
  sentEventsIndex: number;
}

export interface PendingDraftImage {
  id: string;
  file: File;
  previewUrl: string;
  width?: number;
  height?: number;
  source?: 'upload' | 'media_gallery';
  mediaRef?: string;
  mediaType?: string;
  mediaId?: string;
  deviceId?: string;
  sourceTool?: string;
  bucketName?: string;
  dateTakenMs?: number;
  dateModifiedSec?: number;
  sizeBytes?: number;
}

export interface QueuedChatTurn {
  id: string;
  message: string;
  attachments: PendingDraftImage[];
  createdAt: string;
}

interface ChatStore {
  events: ChatEvent[];
  setEvents: React.Dispatch<React.SetStateAction<ChatEvent[]>>;
  sessionId: string | null;
  setSessionId: (id: string | null) => void;
  activeDraftKey: string;
  draftByKey: Record<string, string>;
  setDraftForKey: (key: string, value: string) => void;
  pendingImagesByKey: Record<string, PendingDraftImage[]>;
  setPendingImagesForKey: (key: string, updater: React.SetStateAction<PendingDraftImage[]>) => void;
  queuedTurnsByKey: Record<string, QueuedChatTurn[]>;
  setQueuedTurnsForKey: (key: string, updater: React.SetStateAction<QueuedChatTurn[]>) => void;
  moveQueuedTurns: (fromKey: string, toKey: string) => void;
  eventsByKey: Record<string, ChatEvent[]>;
  setEventsForKey: (key: string, updater: React.SetStateAction<ChatEvent[]>) => void;
  runsByKey: Record<string, ChatRunState>;
  setRunsByKey: React.Dispatch<React.SetStateAction<Record<string, ChatRunState>>>;
  // Refs survive ChatPage remount because they live on the Provider, which
  // sits above the Routes Outlet and so doesn't unmount on tab switch.
  abortRef: MutableRefObject<AbortController | null>;
  lastSentRef: MutableRefObject<string>;
  sentEventsIdxRef: MutableRefObject<number>;
  turnStartedAtRef: MutableRefObject<number>;
  eventCacheRef: MutableRefObject<Record<string, ChatEvent[]>>;
}

const ChatStoreContext = createContext<ChatStore | null>(null);
const ACTIVE_SESSION_KEY = 'agent-platform.activeSessionId';
export const NEW_SESSION_KEY = '__new__';

function readStoredSessionId(): string | null {
  try {
    return window.localStorage.getItem(ACTIVE_SESSION_KEY);
  } catch {
    return null;
  }
}

export function ChatStoreProvider({ children }: { children: ReactNode }) {
  const [events, setEvents] = useState<ChatEvent[]>([]);
  const [sessionIdState, setSessionIdState] = useState<string | null>(() => readStoredSessionId());
  const [draftByKey, setDraftByKey] = useState<Record<string, string>>({});
  const [pendingImagesByKey, setPendingImagesByKey] = useState<Record<string, PendingDraftImage[]>>({});
  const [queuedTurnsByKey, setQueuedTurnsByKey] = useState<Record<string, QueuedChatTurn[]>>({});
  const [eventsByKey, setEventsByKey] = useState<Record<string, ChatEvent[]>>({});
  const [runsByKey, setRunsByKey] = useState<Record<string, ChatRunState>>({});
  const abortRef = useRef<AbortController | null>(null);
  const lastSentRef = useRef('');
  const sentEventsIdxRef = useRef(-1);
  const turnStartedAtRef = useRef(0);
  const eventCacheRef = useRef<Record<string, ChatEvent[]>>({});
  const activeDraftKey = sessionIdState ?? NEW_SESSION_KEY;

  function setSessionId(id: string | null) {
    setSessionIdState(id);
    try {
      if (id) {
        window.localStorage.setItem(ACTIVE_SESSION_KEY, id);
      } else {
        window.localStorage.removeItem(ACTIVE_SESSION_KEY);
      }
    } catch {
      // localStorage may be unavailable in private or locked-down contexts.
    }
  }

  const setDraftForKey = useCallback((key: string, value: string) => {
    setDraftByKey(prev => ({ ...prev, [key]: value }));
  }, []);

  const setPendingImagesForKey = useCallback((key: string, updater: React.SetStateAction<PendingDraftImage[]>) => {
    setPendingImagesByKey(prev => {
      const current = prev[key] ?? [];
      const next = typeof updater === 'function'
        ? (updater as (value: PendingDraftImage[]) => PendingDraftImage[])(current)
        : updater;
      return { ...prev, [key]: next };
    });
  }, []);

  const setQueuedTurnsForKey = useCallback((key: string, updater: React.SetStateAction<QueuedChatTurn[]>) => {
    setQueuedTurnsByKey(prev => {
      const current = prev[key] ?? [];
      const next = typeof updater === 'function'
        ? (updater as (value: QueuedChatTurn[]) => QueuedChatTurn[])(current)
        : updater;
      return { ...prev, [key]: next };
    });
  }, []);

  const moveQueuedTurns = useCallback((fromKey: string, toKey: string) => {
    if (fromKey === toKey) return;
    setQueuedTurnsByKey(prev => {
      const moving = prev[fromKey] ?? [];
      if (moving.length === 0) return prev;
      return {
        ...prev,
        [fromKey]: [],
        [toKey]: [...(prev[toKey] ?? []), ...moving]
      };
    });
  }, []);

  const setEventsForKey = useCallback((key: string, updater: React.SetStateAction<ChatEvent[]>) => {
    setEventsByKey(prev => {
      const current = prev[key] ?? [];
      const next = typeof updater === 'function'
        ? (updater as (value: ChatEvent[]) => ChatEvent[])(current)
        : updater;
      return { ...prev, [key]: next };
    });
  }, []);

  return (
    <ChatStoreContext.Provider value={{
      events, setEvents,
      sessionId: sessionIdState, setSessionId,
      activeDraftKey, draftByKey, setDraftForKey,
      pendingImagesByKey, setPendingImagesForKey,
      queuedTurnsByKey, setQueuedTurnsForKey, moveQueuedTurns,
      eventsByKey, setEventsForKey,
      runsByKey, setRunsByKey,
      abortRef, lastSentRef, sentEventsIdxRef, turnStartedAtRef, eventCacheRef,
    }}>
      {children}
    </ChatStoreContext.Provider>
  );
}

export function useChatStore(): ChatStore {
  const ctx = useContext(ChatStoreContext);
  if (!ctx) throw new Error('useChatStore must be used inside ChatStoreProvider');
  return ctx;
}
