import { createContext, MutableRefObject, ReactNode, useContext, useRef, useState } from 'react';

export interface ChatEvent {
  type: string;
  data: any;
}

interface ChatStore {
  events: ChatEvent[];
  setEvents: React.Dispatch<React.SetStateAction<ChatEvent[]>>;
  busy: boolean;
  setBusy: (b: boolean) => void;
  sessionId: string | null;
  setSessionId: (id: string | null) => void;
  input: string;
  setInput: (s: string) => void;
  // Refs survive ChatPage remount because they live on the Provider, which
  // sits above the Routes Outlet and so doesn't unmount on tab switch.
  abortRef: MutableRefObject<AbortController | null>;
  lastSentRef: MutableRefObject<string>;
  sentEventsIdxRef: MutableRefObject<number>;
}

const ChatStoreContext = createContext<ChatStore | null>(null);
const ACTIVE_SESSION_KEY = 'agent-platform.activeSessionId';

function readStoredSessionId(): string | null {
  try {
    return window.localStorage.getItem(ACTIVE_SESSION_KEY);
  } catch {
    return null;
  }
}

export function ChatStoreProvider({ children }: { children: ReactNode }) {
  const [events, setEvents] = useState<ChatEvent[]>([]);
  const [busy, setBusy] = useState(false);
  const [sessionIdState, setSessionIdState] = useState<string | null>(() => readStoredSessionId());
  const [input, setInput] = useState('');
  const abortRef = useRef<AbortController | null>(null);
  const lastSentRef = useRef('');
  const sentEventsIdxRef = useRef(-1);

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

  return (
    <ChatStoreContext.Provider value={{
      events, setEvents,
      busy, setBusy,
      sessionId: sessionIdState, setSessionId,
      input, setInput,
      abortRef, lastSentRef, sentEventsIdxRef,
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
