import { useEffect, useRef, useState } from 'react';
import { api, MessageDto, SessionDto } from '../api/client';
import { streamChat } from '../api/sse';
import { ChatEvent, useChatStore } from '../lib/chatStore';

export default function ChatPage() {
  const {
    events, setEvents,
    busy, setBusy,
    sessionId, setSessionId,
    input, setInput,
    abortRef, lastSentRef, sentEventsIdxRef,
  } = useChatStore();
  const [sessions, setSessions] = useState<SessionDto[]>([]);
  const [sessionsLoading, setSessionsLoading] = useState(true);
  const [sessionsError, setSessionsError] = useState<string | null>(null);
  const [messagesLoading, setMessagesLoading] = useState(false);
  const [deleteBusyId, setDeleteBusyId] = useState<string | null>(null);
  const [selectionMode, setSelectionMode] = useState(false);
  const [selectedSessionIds, setSelectedSessionIds] = useState<Set<string>>(() => new Set());
  const [bulkDeleting, setBulkDeleting] = useState(false);
  const [lightboxSrc, setLightboxSrc] = useState<string | null>(null);
  const scrollRef = useRef<HTMLDivElement | null>(null);

  async function refreshSessions() {
    setSessionsError(null);
    try {
      const data = await api.listSessions();
      setSessions(sortSessions(data));
    } catch (e) {
      setSessionsError(e instanceof Error ? e.message : String(e));
    } finally {
      setSessionsLoading(false);
    }
  }

  async function loadSession(id: string, force = false) {
    if (busy || (!force && id === sessionId)) return;
    setMessagesLoading(true);
    setSessionId(id);
    try {
      const rows = await api.listMessages(id);
      setEvents(rows.map(messageToEvent).filter((ev): ev is ChatEvent => ev !== null));
    } catch (e) {
      setEvents([{ type: 'error', data: { message: e instanceof Error ? e.message : String(e) } }]);
    } finally {
      setMessagesLoading(false);
    }
  }

  function startNewSession() {
    if (busy) return;
    setSessionId(null);
    setEvents([]);
    setInput('');
    lastSentRef.current = '';
    sentEventsIdxRef.current = -1;
  }

  function toggleSelectionMode() {
    if (busy || bulkDeleting) return;
    setSelectionMode(prev => {
      const next = !prev;
      if (!next) setSelectedSessionIds(new Set());
      return next;
    });
  }

  function toggleSessionSelected(id: string) {
    if (busy || bulkDeleting) return;
    setSelectedSessionIds(prev => {
      const next = new Set(prev);
      if (next.has(id)) {
        next.delete(id);
      } else {
        next.add(id);
      }
      return next;
    });
  }

  function selectAllSessions() {
    if (busy || bulkDeleting) return;
    setSelectedSessionIds(prev => {
      if (prev.size === sessions.length) return new Set();
      return new Set(sessions.map(s => s.id));
    });
  }

  async function deleteSession(id: string) {
    if (busy || deleteBusyId) return;
    const target = sessions.find(s => s.id === id);
    const title = target ? sessionTitle(target) : '这个会话';
    if (!window.confirm(`删除「${title}」？`)) return;
    setDeleteBusyId(id);
    try {
      await api.deleteSession(id);
      setSessions(prev => prev.filter(s => s.id !== id));
      setSelectedSessionIds(prev => {
        const next = new Set(prev);
        next.delete(id);
        return next;
      });
      if (sessionId === id) startNewSession();
    } finally {
      setDeleteBusyId(null);
    }
  }

  async function deleteSelectedSessions() {
    if (busy || bulkDeleting || selectedSessionIds.size === 0) return;
    const ids = Array.from(selectedSessionIds);
    if (!window.confirm(`删除选中的 ${ids.length} 个会话？`)) return;
    setBulkDeleting(true);
    try {
      await Promise.all(ids.map(id => api.deleteSession(id)));
      setSessions(prev => prev.filter(s => !selectedSessionIds.has(s.id)));
      setSelectedSessionIds(new Set());
      setSelectionMode(false);
      if (sessionId && selectedSessionIds.has(sessionId)) startNewSession();
    } catch (e) {
      setSessionsError(e instanceof Error ? e.message : String(e));
      await refreshSessions();
    } finally {
      setBulkDeleting(false);
    }
  }

  function handleStop() {
    if (!busy) return;
    abortRef.current?.abort();
    abortRef.current = null;
    if (lastSentRef.current && !input) setInput(lastSentRef.current);
    const cutoff = sentEventsIdxRef.current;
    if (cutoff >= 0) setEvents(prev => prev.slice(0, cutoff));
    setBusy(false);
  }

  useEffect(() => {
    void refreshSessions();
    if (sessionId) void loadSession(sessionId, true);
    // Load the stored active session once on mount; later switches are explicit.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: 'smooth' });
  }, [events]);

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      if (e.key === 'Escape' && busy) {
        e.preventDefault();
        handleStop();
      }
    }
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [busy, input]);

  async function send() {
    const message = input.trim();
    if (!message || busy) return;
    setInput('');
    lastSentRef.current = message;
    setBusy(true);
    setEvents(prev => {
      sentEventsIdxRef.current = prev.length;
      return prev;
    });
    const ctrl = new AbortController();
    abortRef.current = ctrl;

    let assistantBuf = '';
    let streamSessionId = sessionId;
    try {
      await streamChat({
        message,
        sessionId: sessionId ?? undefined,
        signal: ctrl.signal,
        onEvent(name, data) {
          if (name === 'session') {
            if (data?.sessionId) {
              streamSessionId = data.sessionId;
              setSessionId(data.sessionId);
            }
            return;
          }
          if (name === 'assistant_message') {
            assistantBuf += data?.content ?? '';
            setEvents(prev => {
              const copy = [...prev];
              const last = copy[copy.length - 1];
              if (last?.type === 'assistant_message') {
                copy[copy.length - 1] = { type: 'assistant_message', data: { content: assistantBuf } };
              } else {
                copy.push({ type: 'assistant_message', data: { content: assistantBuf } });
              }
              return copy;
            });
          } else {
            assistantBuf = '';
            setEvents(prev => [...prev, { type: name, data }]);
          }
        },
        onError() { /* stop fetch-event-source retry; UI state resets below */ },
        onClose() { setBusy(false); }
      });
      await refreshSessions();
    } catch {
      setBusy(false);
    } finally {
      abortRef.current = null;
      if (streamSessionId) setSessionId(streamSessionId);
    }
  }

  const activeSession = sessionId ? sessions.find(s => s.id === sessionId) : null;

  return (
    <div className="grid grid-cols-1 lg:grid-cols-[280px_minmax(0,1fr)] gap-4 h-[calc(100vh-100px)] min-h-[560px]">
      <aside className="bg-white border rounded flex flex-col min-h-0">
        <div className="p-3 border-b flex items-center justify-between gap-2">
          <div>
            <h1 className="font-semibold leading-tight">会话</h1>
            <div className="text-xs text-slate-500 mt-0.5">{sessions.length} 条历史</div>
          </div>
          <div className="flex items-center gap-1">
            {sessions.length > 0 && (
              <button
                type="button"
                onClick={toggleSelectionMode}
                disabled={busy || bulkDeleting}
                className={[
                  'px-2.5 py-1.5 rounded border text-sm disabled:opacity-50',
                  selectionMode
                    ? 'bg-slate-900 border-slate-900 text-white'
                    : 'bg-white border-slate-200 text-slate-700 hover:bg-slate-50'
                ].join(' ')}
              >
                {selectionMode ? '取消' : '多选'}
              </button>
            )}
            <button
              type="button"
              onClick={startNewSession}
              disabled={busy}
              className="px-2.5 py-1.5 rounded bg-blue-600 hover:bg-blue-700 disabled:opacity-50 text-white text-sm"
            >
              + 新建
            </button>
          </div>
        </div>

        {selectionMode && (
          <div className="px-3 py-2 border-b bg-slate-50 flex items-center justify-between gap-2">
            <button
              type="button"
              onClick={selectAllSessions}
              disabled={busy || bulkDeleting || sessions.length === 0}
              className="text-xs text-slate-600 hover:text-slate-900 disabled:opacity-50"
            >
              {selectedSessionIds.size === sessions.length ? '取消全选' : '全选'}
            </button>
            <button
              type="button"
              onClick={() => void deleteSelectedSessions()}
              disabled={busy || bulkDeleting || selectedSessionIds.size === 0}
              className="px-2.5 py-1 rounded bg-red-600 hover:bg-red-700 disabled:opacity-50 text-white text-xs"
            >
              {bulkDeleting ? '删除中...' : `删除已选 ${selectedSessionIds.size}`}
            </button>
          </div>
        )}

        <div className="p-2 border-b">
          <button
            type="button"
            onClick={startNewSession}
            disabled={busy}
            className={[
              'w-full text-left px-3 py-2 rounded text-sm border transition',
              sessionId === null
                ? 'bg-blue-50 border-blue-200 text-blue-900'
                : 'bg-white border-slate-200 hover:bg-slate-50 text-slate-700',
              busy ? 'opacity-60 cursor-not-allowed' : ''
            ].join(' ')}
          >
            <span className="font-medium">新会话</span>
            <span className="block text-xs text-slate-500 mt-0.5">第一条消息会自动创建历史记录</span>
          </button>
        </div>

        <div className="flex-1 overflow-y-auto p-2 space-y-1">
          {sessionsLoading && <div className="text-sm text-slate-500 px-2 py-3">加载中...</div>}
          {sessionsError && <div className="text-sm text-red-600 px-2 py-3">{sessionsError}</div>}
          {!sessionsLoading && !sessionsError && sessions.length === 0 && (
            <div className="text-sm text-slate-500 px-2 py-3">还没有历史会话。</div>
          )}
          {sessions.map(s => {
            const active = s.id === sessionId;
            const selected = selectedSessionIds.has(s.id);
            return (
              <div
                key={s.id}
                className={[
                  'group flex items-stretch gap-1 rounded border transition',
                  active ? 'bg-slate-900 border-slate-900 text-white' : 'bg-white border-transparent hover:border-slate-200 hover:bg-slate-50',
                  selected && !active ? 'border-blue-300 bg-blue-50' : ''
                ].join(' ')}
              >
                {selectionMode && (
                  <label className="flex items-center pl-2 cursor-pointer">
                    <input
                      type="checkbox"
                      checked={selected}
                      disabled={busy || bulkDeleting}
                      onChange={() => toggleSessionSelected(s.id)}
                      className="h-4 w-4 rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                      aria-label={`选择 ${sessionTitle(s)}`}
                    />
                  </label>
                )}
                <button
                  type="button"
                  onClick={() => selectionMode ? toggleSessionSelected(s.id) : void loadSession(s.id)}
                  disabled={busy || messagesLoading || bulkDeleting}
                  className="min-w-0 flex-1 text-left px-3 py-2 disabled:cursor-not-allowed"
                  aria-current={active ? 'page' : undefined}
                >
                  <div className="font-medium text-sm truncate">{sessionTitle(s)}</div>
                  <div className={['text-xs mt-1 truncate', active ? 'text-slate-300' : 'text-slate-500'].join(' ')}>
                    {formatSessionTime(s)}
                  </div>
                </button>
                <button
                  type="button"
                  onClick={() => void deleteSession(s.id)}
                  disabled={busy || selectionMode || bulkDeleting || deleteBusyId === s.id}
                  className={[
                    'px-2 text-xs rounded-r opacity-0 group-hover:opacity-100 focus:opacity-100 disabled:opacity-40',
                    active ? 'text-slate-300 hover:text-white' : 'text-slate-400 hover:text-red-600'
                  ].join(' ')}
                  title="删除会话"
                >
                  删除
                </button>
              </div>
            );
          })}
        </div>
      </aside>

      <section className="min-w-0 flex flex-col bg-white border rounded min-h-0">
        <div className="px-4 py-3 border-b flex items-center justify-between gap-3">
          <div className="min-w-0">
            <div className="font-semibold truncate">{activeSession ? sessionTitle(activeSession) : '新会话'}</div>
            <div className="text-xs text-slate-500 mt-0.5">
              {sessionId ? `会话 ${sessionId.slice(0, 8)}` : '发送第一条消息后保存到历史'}
              {messagesLoading ? ' · 正在加载消息' : ''}
            </div>
          </div>
          {busy && (
            <button
              type="button"
              onClick={handleStop}
              className="bg-red-600 hover:bg-red-700 text-white px-3 py-1.5 rounded text-sm"
              title="按 Esc 也可中断"
            >
              停止
            </button>
          )}
        </div>

        <div ref={scrollRef} className="flex-1 overflow-y-auto p-4 space-y-3">
          {events.length === 0 && (
            <div className="text-slate-500 text-sm">
              试试:<em>"列出我最近的照片"</em>。助手会在你绑定的设备上调用
              <code className="mx-1 px-1.5 py-0.5 bg-slate-100 rounded">photos.list_recent</code>
              工具。
            </div>
          )}
          {events.map((e, i) => <Bubble key={`${e.type}-${i}`} ev={e} onOpenImage={setLightboxSrc} />)}
        </div>

        <form onSubmit={ev => { ev.preventDefault(); void send(); }} className="border-t p-3 flex gap-2 items-end">
          <textarea
            value={input}
            onChange={e => setInput(e.target.value)}
            onKeyDown={e => {
              if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                void send();
              }
            }}
            rows={1}
            placeholder={busy ? '生成中... 按 Esc 或点"停止"中断' : '输入消息...'}
            className="flex-1 min-h-[42px] max-h-32 resize-none border rounded px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500"
            disabled={busy || messagesLoading}
          />
          <button
            type="submit"
            disabled={!input.trim() || busy || messagesLoading}
            className="bg-blue-600 hover:bg-blue-700 disabled:opacity-50 text-white px-4 py-2 rounded h-[42px]"
          >
            发送
          </button>
        </form>
      </section>

      {lightboxSrc && (
        <div
          className="fixed inset-0 bg-black/80 flex items-center justify-center z-50 cursor-zoom-out"
          onClick={() => setLightboxSrc(null)}
        >
          <img
            src={lightboxSrc}
            className="max-w-[95vw] max-h-[95vh] object-contain"
            alt="预览"
            onClick={e => e.stopPropagation()}
          />
        </div>
      )}
    </div>
  );
}

type OpenImage = (src: string) => void;

function messageToEvent(m: MessageDto): ChatEvent | null {
  if (m.role === 'USER') {
    return { type: 'user_message', data: { content: m.content } };
  }
  if (m.role === 'ASSISTANT') {
    return { type: 'assistant_message', data: { content: m.content } };
  }
  const metadata = asRecord(m.metadata);
  if (m.role === 'TOOL_CALL') {
    return {
      type: 'tool_call_started',
      data: {
        deviceId: metadata?.deviceId,
        tool: metadata?.tool ?? 'tool',
        args: metadata?.args ?? {}
      }
    };
  }
  if (m.role === 'TOOL_RESULT') {
    return {
      type: 'tool_call_result',
      data: {
        tool: metadata?.tool ?? 'tool',
        result: metadata?.result ?? m.content
      }
    };
  }
  return null;
}

function asRecord(value: unknown): Record<string, any> | null {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, any>
    : null;
}

function sortSessions(items: SessionDto[]) {
  return [...items].sort((a, b) => sessionTime(b) - sessionTime(a));
}

function sessionTime(s: SessionDto) {
  return new Date(s.lastMessageAt ?? s.createdAt).getTime();
}

function sessionTitle(s: SessionDto) {
  const title = s.title?.trim();
  return title || '未命名会话';
}

function formatSessionTime(s: SessionDto) {
  const value = s.lastMessageAt ?? s.createdAt;
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '';
  return date.toLocaleString(undefined, {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  });
}

function Bubble({ ev, onOpenImage }: { ev: ChatEvent; onOpenImage: OpenImage }) {
  switch (ev.type) {
    case 'user_message':
      return (
        <div className="flex justify-end">
          <div className="bg-blue-600 text-white px-3 py-2 rounded-lg max-w-prose whitespace-pre-wrap">
            {ev.data?.content}
          </div>
        </div>
      );
    case 'assistant_message':
      return (
        <div className="flex justify-start">
          <div className="bg-slate-100 px-3 py-2 rounded-lg max-w-prose whitespace-pre-wrap">
            {ev.data?.content}
          </div>
        </div>
      );
    case 'tool_call_started':
      return (
        <div className="text-xs text-slate-500 italic flex items-center gap-2">
          <span className="inline-block w-2 h-2 rounded-full bg-amber-400 animate-pulse" />
          调用 <code className="bg-slate-100 px-1 rounded">{ev.data?.tool}</code>
          {ev.data?.args && <span className="truncate">({JSON.stringify(ev.data.args)})</span>}
        </div>
      );
    case 'tool_call_result':
      return <ToolResult tool={ev.data?.tool} result={ev.data?.result} onOpenImage={onOpenImage} />;
    case 'error':
      return (
        <div className="text-sm text-red-600 bg-red-50 border border-red-200 rounded px-3 py-2">
          {ev.data?.message ?? '未知错误'}
        </div>
      );
    default:
      return null;
  }
}

function ToolResult({ tool, result, onOpenImage }: {
  tool: string;
  result: any;
  onOpenImage: OpenImage;
}) {
  if (Array.isArray(result?.photos) &&
      (tool === 'photos.list_recent' ||
       tool === 'photos.list_by_album' ||
       tool === 'photos.recent_screenshots')) {
    return <ThumbGrid items={result.photos} onOpenImage={onOpenImage} />;
  }

  if (tool === 'photos.get_full' && (result?.vision_b64 || result?.thumb_b64)) {
    const big = result.vision_b64 ? `data:image/jpeg;base64,${result.vision_b64}` : null;
    const small = result.thumb_b64 ? `data:image/jpeg;base64,${result.thumb_b64}` : big;
    const w = result.vision_width ?? result.source_width;
    const h = result.vision_height ?? result.source_height;
    return (
      <div className="max-w-md">
        <img src={small ?? ''} alt={result.name}
             title={result.name}
             onClick={() => big && onOpenImage(big)}
             className="w-full max-h-96 object-contain rounded border cursor-zoom-in hover:ring-2 hover:ring-blue-400 transition" />
        <div className="text-xs text-slate-500 mt-1">
          {result.name}{w && h ? ` · ${w}×${h}` : ''}
        </div>
      </div>
    );
  }

  if (tool === 'photos.list_albums' && Array.isArray(result?.albums)) {
    return (
      <div className="grid grid-cols-2 sm:grid-cols-4 gap-2">
        {result.albums.map((a: any) => {
          const src = a.cover_thumb_b64 ? `data:image/jpeg;base64,${a.cover_thumb_b64}` : null;
          return (
            <div key={a.bucket_id} className="border rounded overflow-hidden">
              {src ? (
                <img src={src} alt={a.name}
                     onClick={() => onOpenImage(src)}
                     className="w-full h-32 object-cover cursor-zoom-in hover:ring-2 hover:ring-blue-400 transition" />
              ) : (
                <div className="w-full h-32 bg-slate-200" />
              )}
              <div className="px-2 py-1 text-xs">
                <div className="font-medium truncate" title={a.name}>{a.name}</div>
                <div className="text-slate-500">{a.photo_count} 张</div>
              </div>
            </div>
          );
        })}
      </div>
    );
  }

  if (tool === 'videos.list_recent' && Array.isArray(result?.videos)) {
    return (
      <div className="grid grid-cols-3 sm:grid-cols-5 gap-2">
        {result.videos.map((v: any) => {
          const src = v.thumb_b64 ? `data:image/jpeg;base64,${v.thumb_b64}` : null;
          const sec = v.duration_ms ? Math.round(v.duration_ms / 1000) : 0;
          const m = Math.floor(sec / 60);
          const s = sec % 60;
          const dur = `${m}:${String(s).padStart(2, '0')}`;
          return (
            <div key={v.id} className="relative">
              {src ? (
                <img src={src} alt={v.name}
                     title={v.name}
                     onClick={() => onOpenImage(src)}
                     className="w-full h-32 object-cover rounded border cursor-zoom-in hover:ring-2 hover:ring-blue-400 transition" />
              ) : (
                <div className="w-full h-32 bg-slate-200 rounded" />
              )}
              <span className="absolute bottom-1 right-1 bg-black/70 text-white text-[10px] px-1 rounded">▶ {dur}</span>
            </div>
          );
        })}
      </div>
    );
  }

  return (
    <details className="text-xs text-slate-600">
      <summary>工具 {tool} 返回结果</summary>
      <pre className="mt-1 bg-slate-100 p-2 rounded overflow-x-auto">{JSON.stringify(result, null, 2)}</pre>
    </details>
  );
}

function ThumbGrid({ items, onOpenImage }: { items: any[]; onOpenImage: OpenImage }) {
  return (
    <div className="grid grid-cols-3 sm:grid-cols-5 gap-2">
      {items.map((p: any) => {
        if (!p.thumb_b64) {
          return <div key={p.id} className="w-full h-32 bg-slate-200 rounded text-xs flex items-center justify-center">{p.name}</div>;
        }
        const src = `data:image/jpeg;base64,${p.thumb_b64}`;
        return (
          <img key={p.id} src={src} alt={p.name}
               title={p.name}
               onClick={() => onOpenImage(src)}
               className="w-full h-32 object-cover rounded border cursor-zoom-in hover:ring-2 hover:ring-blue-400 transition" />
        );
      })}
    </div>
  );
}
