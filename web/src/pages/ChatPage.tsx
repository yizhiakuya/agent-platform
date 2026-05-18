import { useEffect, useMemo, useRef, useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { api, MessageDto, SessionDto } from '../api/client';
import { streamChat } from '../api/sse';
import { ChatEvent, ChatRunState, NEW_SESSION_KEY, PendingDraftImage, QueuedChatTurn, useChatStore } from '../lib/chatStore';
import { getToken } from '../lib/auth';

export default function ChatPage() {
  const {
    events, setEvents,
    sessionId, setSessionId,
    activeDraftKey, draftByKey, setDraftForKey,
    pendingImagesByKey, setPendingImagesForKey,
    queuedTurnsByKey, setQueuedTurnsForKey, moveQueuedTurns,
    eventsByKey, setEventsForKey,
    runsByKey, setRunsByKey,
    abortRef, lastSentRef, sentEventsIdxRef, turnStartedAtRef, eventCacheRef,
  } = useChatStore();
  const input = draftByKey[activeDraftKey] ?? '';
  const pendingImages = pendingImagesByKey[activeDraftKey] ?? [];
  const queuedTurns = queuedTurnsByKey[activeDraftKey] ?? [];
  const activeRun = runsByKey[activeDraftKey];
  const busy = Boolean(activeRun);
  const anyBusy = Object.keys(runsByKey).length > 0;
  const [sessions, setSessions] = useState<SessionDto[]>([]);
  const [sessionsLoading, setSessionsLoading] = useState(true);
  const [sessionsError, setSessionsError] = useState<string | null>(null);
  const [messagesLoading, setMessagesLoading] = useState(false);
  const [deleteBusyId, setDeleteBusyId] = useState<string | null>(null);
  const [selectionMode, setSelectionMode] = useState(false);
  const [selectedSessionIds, setSelectedSessionIds] = useState<Set<string>>(() => new Set());
  const [bulkDeleting, setBulkDeleting] = useState(false);
  const [lightboxSrc, setLightboxSrc] = useState<string | null>(null);
  const [lightboxScale, setLightboxScale] = useState(1);
  const [composerError, setComposerError] = useState<string | null>(null);
  const [now, setNow] = useState(() => Date.now());
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const selectAllRef = useRef<HTMLInputElement | null>(null);
  const activeDraftKeyRef = useRef(activeDraftKey);
  const queuedTurnsRef = useRef(queuedTurnsByKey);
  const sessionCount = sessions.length;
  const selectedCount = selectedSessionIds.size;
  const allSessionsSelected = sessionCount > 0 && selectedCount === sessionCount;
  const partiallySelected = selectedCount > 0 && selectedCount < sessionCount;

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
    if (!force && id === sessionId) return;
    setMessagesLoading(true);
    setEvents(eventsByKey[id] ?? eventCacheRef.current[id] ?? []);
    setSessionId(id);
    try {
      const rows = await api.listMessages(id);
      const loaded = rows.map(messageToEvent).filter((ev): ev is ChatEvent => ev !== null);
      const cached = eventsByKey[id] ?? eventCacheRef.current[id];
      const nextEvents = cached && hasRicherToolHistory(cached, loaded) ? cached : loaded;
      eventCacheRef.current[id] = nextEvents;
      setEventsForKey(id, nextEvents);
      setEvents(nextEvents);
    } catch (e) {
      setEvents([{ type: 'error', data: { message: e instanceof Error ? e.message : String(e) } }]);
    } finally {
      setMessagesLoading(false);
    }
  }

  function startNewSession() {
    setSessionId(null);
    setEvents(eventsByKey[NEW_SESSION_KEY] ?? []);
    clearPendingImages();
    lastSentRef.current = '';
    sentEventsIdxRef.current = -1;
  }

  function toggleSelectionMode() {
    if (bulkDeleting) return;
    setSelectionMode(prev => {
      const next = !prev;
      if (!next) setSelectedSessionIds(new Set());
      return next;
    });
  }

  function toggleSessionSelected(id: string) {
    if (bulkDeleting) return;
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
    if (bulkDeleting) return;
    setSelectedSessionIds(new Set(sessions.map(s => s.id)));
  }

  function invertSelectedSessions() {
    if (bulkDeleting) return;
    setSelectedSessionIds(prev => {
      const next = new Set<string>();
      sessions.forEach(s => {
        if (!prev.has(s.id)) next.add(s.id);
      });
      return next;
    });
  }

  function clearSelectedSessions() {
    if (bulkDeleting) return;
    setSelectedSessionIds(new Set());
  }

  function exitSelectionMode() {
    if (bulkDeleting) return;
    setSelectedSessionIds(new Set());
    setSelectionMode(false);
  }

  async function deleteSession(id: string) {
    if (runsByKey[id] || deleteBusyId) return;
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

  async function exportSession(id: string) {
    try {
      await api.downloadSessionExport(id);
    } catch (e) {
      setSessionsError(e instanceof Error ? e.message : String(e));
    }
  }

  function clearPendingImages() {
    setPendingImagesForKey(activeDraftKey, prev => {
      prev.forEach(img => URL.revokeObjectURL(img.previewUrl));
      return [];
    });
    setComposerError(null);
  }

  function removePendingImage(id: string) {
    setPendingImagesForKey(activeDraftKey, prev => {
      const target = prev.find(img => img.id === id);
      if (target) URL.revokeObjectURL(target.previewUrl);
      return prev.filter(img => img.id !== id);
    });
  }

  async function addImageFiles(files: Iterable<File>) {
    const incoming = Array.from(files).filter(file => file.type.startsWith('image/'));
    if (incoming.length === 0) return;
    setComposerError(null);
    const slots = Math.max(0, MAX_ATTACHMENTS - pendingImages.length);
    if (slots === 0) {
      setComposerError(`最多一次添加 ${MAX_ATTACHMENTS} 张图片`);
      return;
    }
    const accepted: PendingDraftImage[] = [];
    for (const file of incoming.slice(0, slots)) {
      if (!SUPPORTED_IMAGE_TYPES.has(file.type)) {
        setComposerError('仅支持 JPG、PNG、WebP 图片');
        continue;
      }
      if (file.size > MAX_IMAGE_BYTES) {
        setComposerError('单张图片不能超过 10MB');
        continue;
      }
      const dimensions = await readImageDimensions(file).catch(() => ({ width: undefined, height: undefined }));
      accepted.push({
        id: `${Date.now()}-${Math.random().toString(16).slice(2)}`,
        file,
        previewUrl: URL.createObjectURL(file),
        width: dimensions.width,
        height: dimensions.height
      });
    }
    if (incoming.length > slots) {
      setComposerError(`最多一次添加 ${MAX_ATTACHMENTS} 张图片`);
    }
    if (accepted.length > 0) {
      setPendingImagesForKey(activeDraftKey, prev => [...prev, ...accepted]);
    }
  }

  function handleFileChange(e: React.ChangeEvent<HTMLInputElement>) {
    const files = e.currentTarget.files;
    if (files) void addImageFiles(files);
    e.currentTarget.value = '';
  }

  function handlePaste(e: React.ClipboardEvent<HTMLTextAreaElement>) {
    const files = Array.from(e.clipboardData.files).filter(file => file.type.startsWith('image/'));
    if (files.length === 0) return;
    e.preventDefault();
    void addImageFiles(files);
  }

  function handleDrop(e: React.DragEvent<HTMLFormElement>) {
    const files = Array.from(e.dataTransfer.files).filter(file => file.type.startsWith('image/'));
    if (files.length === 0) return;
    e.preventDefault();
    void addImageFiles(files);
  }

  function handleDragOver(e: React.DragEvent<HTMLFormElement>) {
    if (Array.from(e.dataTransfer.items).some(item => item.type.startsWith('image/'))) {
      e.preventDefault();
    }
  }

  async function deleteSelectedSessions() {
    if (bulkDeleting || selectedSessionIds.size === 0) return;
    if (Array.from(selectedSessionIds).some(id => runsByKey[id])) {
      setSessionsError('有会话仍在处理中，请先停止后再删除。');
      return;
    }
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
    if (!activeRun) return;
    void api.cancelChatRun(activeRun.runId).catch(() => {
      // Local abort still gives immediate UI feedback if the cancel request is lost.
    });
    activeRun.abortController.abort();
    appendCancelEventToKey(activeDraftKey, activeRun.sentEventsIndex);
    setRunsByKey(prev => {
      const next = { ...prev };
      delete next[activeDraftKey];
      return next;
    });
    if (abortRef.current === activeRun.abortController) abortRef.current = null;
  }

  function setEventsForDraftKey(key: string, updater: React.SetStateAction<ChatEvent[]>) {
    const current = eventCacheRef.current[key] ?? eventsByKey[key] ?? [];
    const next = typeof updater === 'function'
      ? (updater as (value: ChatEvent[]) => ChatEvent[])(current)
      : updater;
    setEventsForKey(key, next);
    eventCacheRef.current[key] = next;
    if (key === activeDraftKeyRef.current) setEvents(next);
  }

  function appendCancelEventToKey(key: string, sentEventsIndex: number) {
    setEventsForDraftKey(key, prev => appendCancelEvent(prev, sentEventsIndex));
  }

  function setQueuedTurnsForKeySync(key: string, updater: React.SetStateAction<QueuedChatTurn[]>) {
    const current = queuedTurnsRef.current[key] ?? [];
    const next = typeof updater === 'function'
      ? (updater as (value: QueuedChatTurn[]) => QueuedChatTurn[])(current)
      : updater;
    queuedTurnsRef.current = { ...queuedTurnsRef.current, [key]: next };
    setQueuedTurnsForKey(key, next);
  }

  function moveQueuedTurnsSync(fromKey: string, toKey: string) {
    if (fromKey === toKey) return;
    const moving = queuedTurnsRef.current[fromKey] ?? [];
    if (moving.length === 0) return;
    queuedTurnsRef.current = {
      ...queuedTurnsRef.current,
      [fromKey]: [],
      [toKey]: [...(queuedTurnsRef.current[toKey] ?? []), ...moving]
    };
    moveQueuedTurns(fromKey, toKey);
  }

  function enqueueTurn(key: string, message: string, attachments: PendingDraftImage[]) {
    setQueuedTurnsForKeySync(key, prev => [
      ...prev,
      {
        id: `${Date.now()}-${Math.random().toString(16).slice(2)}`,
        message,
        attachments,
        createdAt: new Date().toISOString()
      }
    ]);
  }

  function runNextQueuedTurn(key: string, sessionIdForKey: string | null) {
    const queueKey = sessionIdForKey ?? key;
    const queued = queuedTurnsRef.current[queueKey] ?? [];
    const [nextTurn] = queued;
    if (!nextTurn) return;
    setQueuedTurnsForKeySync(queueKey, prev => prev.filter(turn => turn.id !== nextTurn.id));
    void runTurn(nextTurn, queueKey, sessionIdForKey);
  }

  function openLightbox(src: string) {
    setLightboxSrc(src);
    setLightboxScale(1);
  }

  function closeLightbox() {
    setLightboxSrc(null);
    setLightboxScale(1);
  }

  function zoomLightbox(delta: number) {
    setLightboxScale(scale => clampZoom(scale + delta));
  }

  function handleLightboxWheel(e: React.WheelEvent<HTMLDivElement>) {
    e.preventDefault();
    const direction = e.deltaY > 0 ? -1 : 1;
    const step = e.ctrlKey ? 0.35 : 0.18;
    zoomLightbox(direction * step);
  }

  useEffect(() => {
    activeDraftKeyRef.current = activeDraftKey;
  }, [activeDraftKey]);

  useEffect(() => {
    queuedTurnsRef.current = queuedTurnsByKey;
  }, [queuedTurnsByKey]);

  useEffect(() => {
    void refreshSessions();
    if (sessionId) void loadSession(sessionId, true);
    // Load the stored active session once on mount; later switches are explicit.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    const cached = eventsByKey[activeDraftKey] ?? eventCacheRef.current[activeDraftKey];
    if (cached && cached !== events) {
      setEvents(cached);
    }
    // Keep visible events aligned when a background stream updates the active session.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeDraftKey, eventsByKey]);

  useEffect(() => {
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: 'smooth' });
  }, [events]);

  useEffect(() => {
    if (sessionId && !messagesLoading && events.length > 0) {
      eventCacheRef.current[sessionId] = events;
      setEventsForKey(sessionId, events);
    }
  }, [eventCacheRef, events, messagesLoading, sessionId, setEventsForKey]);

  useEffect(() => {
    if (selectAllRef.current) {
      selectAllRef.current.indeterminate = partiallySelected;
    }
  }, [partiallySelected]);

  useEffect(() => {
    const availableIds = new Set(sessions.map(s => s.id));
    setSelectedSessionIds(prev => {
      if (prev.size === 0) return prev;
      const next = new Set<string>();
      prev.forEach(id => {
        if (availableIds.has(id)) next.add(id);
      });
      return next.size === prev.size ? prev : next;
    });
    if (selectionMode && sessions.length === 0) setSelectionMode(false);
  }, [sessions, selectionMode]);

  useEffect(() => {
    if (!anyBusy) return;
    setNow(Date.now());
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [anyBusy]);

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
    const attachmentsToSend = pendingImages;
    if (!message && attachmentsToSend.length === 0) return;
    const originalKey = activeDraftKey;
    const runId = `${Date.now()}-${Math.random().toString(16).slice(2)}`;
    setDraftForKey(originalKey, '');
    setPendingImagesForKey(originalKey, []);
    setComposerError(null);
    if (busy) {
      enqueueTurn(originalKey, message, attachmentsToSend);
      return;
    }
    void runTurn({
      id: runId,
      message,
      attachments: attachmentsToSend,
      createdAt: new Date().toISOString()
    }, originalKey, sessionId);
  }

  async function runTurn(turn: QueuedChatTurn, originalKey: string, originalSessionId: string | null) {
    const message = turn.message;
    const attachmentsToSend = turn.attachments;
    const runId = turn.id;
    let targetKey = originalKey;
    let targetSessionId = originalSessionId;
    lastSentRef.current = message;
    const startedAt = Date.now();
    turnStartedAtRef.current = startedAt;
    sentEventsIdxRef.current = -1;
    const ctrl = new AbortController();
    abortRef.current = ctrl;

    let assistantBuf = '';
    let uploadedAttachments: UploadedChatImage[] = [];
    let turnStarted = false;
    let sentEventsIndex = -1;
    const run: ChatRunState = {
      runId,
      sessionId: targetSessionId,
      startedAt,
      abortController: ctrl,
      sentEventsIndex: -1
    };
    setRunsByKey(prev => ({ ...prev, [targetKey]: run }));
    try {
      if (!targetSessionId) {
        const created = await api.createSession(message || '图片对话');
        const previousKey = targetKey;
        targetSessionId = created.id;
        targetKey = created.id;
        setSessions(prev => sortSessions([created, ...prev.filter(s => s.id !== created.id)]));
        setSessionId(created.id);
        setEventsForKey(NEW_SESSION_KEY, []);
        setEventsForKey(created.id, eventsByKey[NEW_SESSION_KEY] ?? []);
        setEvents(eventsByKey[NEW_SESSION_KEY] ?? []);
        moveQueuedTurnsSync(NEW_SESSION_KEY, created.id);
        setRunsByKey(prev => {
          const current = prev[previousKey];
          if (current?.runId !== runId) return prev;
          const next = { ...prev };
          delete next[previousKey];
          next[targetKey] = { ...current, sessionId: targetSessionId };
          return next;
        });
      }
      uploadedAttachments = await uploadPendingImages(attachmentsToSend);
      attachmentsToSend.forEach(img => URL.revokeObjectURL(img.previewUrl));
      setEventsForDraftKey(targetKey, prev => {
        sentEventsIndex = prev.length;
        sentEventsIdxRef.current = sentEventsIndex;
        return [...prev, {
          type: 'user_message',
          data: { content: message, attachments: uploadedAttachments, createdAt: turn.createdAt }
        }];
      });
      setRunsByKey(prev => {
        const current = prev[targetKey];
        if (current?.runId !== runId) return prev;
        return { ...prev, [targetKey]: { ...current, sentEventsIndex } };
      });
      turnStarted = true;
      await streamChat({
        message,
        sessionId: targetSessionId ?? undefined,
        clientRunId: runId,
        attachments: uploadedAttachments.map(toChatAttachment),
        signal: ctrl.signal,
        onEvent(name, data) {
          if (name === 'session') {
            if (data?.sessionId) {
              targetSessionId = data.sessionId;
              if (targetKey === NEW_SESSION_KEY) targetKey = data.sessionId;
            }
            return;
          }
          if (name === 'user_message') {
            return;
          }
          if (name === 'assistant_message') {
            assistantBuf += data?.content ?? '';
            setEventsForDraftKey(targetKey, prev => {
              const copy = [...prev];
              const last = copy[copy.length - 1];
              const createdAt = last?.type === 'assistant_message'
                ? last.data?.createdAt
                : new Date().toISOString();
              if (last?.type === 'assistant_message') {
                copy[copy.length - 1] = {
                  type: 'assistant_message',
                  data: { ...last.data, content: assistantBuf, createdAt }
                };
              } else {
                copy.push({ type: 'assistant_message', data: { content: assistantBuf, createdAt } });
              }
              return copy;
            });
          } else {
            assistantBuf = '';
            setEventsForDraftKey(targetKey, prev => [
              ...prev,
              { type: name, data: { ...data, createdAt: new Date().toISOString() } }
            ]);
          }
        },
        onError() { /* stop fetch-event-source retry; UI state resets below */ },
        onClose() {
          setEventsForDraftKey(targetKey, prev => markLatestAssistantDuration(prev, Date.now() - startedAt));
          setRunsByKey(prev => {
            const next = { ...prev };
            if (next[targetKey]?.runId === runId) delete next[targetKey];
            return next;
          });
        }
      });
      await refreshSessions();
    } catch {
      attachmentsToSend.forEach(img => URL.revokeObjectURL(img.previewUrl));
      if (ctrl.signal.aborted) {
        appendCancelEventToKey(targetKey, sentEventsIndex);
        if (!turnStarted) {
          if (message && !draftByKey[originalKey]) setDraftForKey(originalKey, message);
          if (attachmentsToSend.length > 0) restorePendingImages(originalKey, attachmentsToSend);
        }
        setEventsForDraftKey(targetKey, prev => markLatestAssistantDuration(prev, Date.now() - startedAt));
      } else {
        if (attachmentsToSend.length > 0) restorePendingImages(originalKey, attachmentsToSend);
        setComposerError('图片上传或发送失败，请重试');
      }
    } finally {
      setRunsByKey(prev => {
        const next = { ...prev };
        if (next[targetKey]?.runId === runId) delete next[targetKey];
        return next;
      });
      abortRef.current = null;
      runNextQueuedTurn(targetKey, targetSessionId);
    }
  }

  function restorePendingImages(key: string, items: PendingDraftImage[]) {
    setPendingImagesForKey(key, items.map(img => ({
      ...img,
      previewUrl: URL.createObjectURL(img.file)
    })));
  }

  const activeSession = sessionId ? sessions.find(s => s.id === sessionId) : null;
  const timelineItems = useMemo(() => buildTimelineItems(events), [events]);
  const toolCallCount = events.filter(ev => ev.type === 'tool_call_started').length;
  const toolResultCount = events.filter(ev => ev.type === 'tool_call_result').length;
  const lastActivity = activeSession?.lastMessageAt ?? activeSession?.createdAt;

  return (
    <div className="workbench chat-shell">
      <aside className="sidebar-panel">
        <div className="panel-header">
          <div className="flex items-start justify-between gap-3">
            <div className="min-w-0">
              <div className="section-title">Sessions</div>
              <h1 className="mt-1 truncate text-xl font-semibold text-slate-950">会话工作区</h1>
              <div className="mt-1 text-xs text-slate-500">
                {selectionMode ? `已选 ${selectedCount} / ${sessionCount}` : `${sessionCount} 条历史`}
              </div>
            </div>
            <button
              type="button"
              onClick={startNewSession}
              className="btn-primary min-h-9 px-3 py-1.5"
            >
              新建
            </button>
          </div>
          <div className="mt-3 flex items-center gap-2">
            {sessionCount > 0 && (
              <button
                type="button"
                onClick={toggleSelectionMode}
                disabled={bulkDeleting}
                className={selectionMode ? 'btn-primary min-h-8 px-3 py-1 text-xs' : 'btn-secondary min-h-8 px-3 py-1 text-xs'}
              >
                {selectionMode ? '完成' : '多选'}
              </button>
            )}
          </div>
        </div>

        {selectionMode && (
          <div className="space-y-2 border-b border-slate-200 bg-slate-50 px-3 py-3">
            <div className="flex items-center justify-between gap-2">
              <label className="inline-flex min-w-0 items-center gap-2 text-xs font-semibold text-slate-700">
                <input
                  ref={selectAllRef}
                  type="checkbox"
                  checked={allSessionsSelected}
                  onChange={e => e.currentTarget.checked ? selectAllSessions() : clearSelectedSessions()}
                  disabled={bulkDeleting || sessionCount === 0}
                  className="h-4 w-4 rounded border-slate-300 text-blue-600 focus:ring-blue-500 disabled:opacity-50"
                  aria-label="选择全部会话"
                />
                <span className="truncate">已选 {selectedCount} / {sessionCount}</span>
              </label>
              <button
                type="button"
                onClick={exitSelectionMode}
                disabled={bulkDeleting}
                className="text-xs font-medium text-slate-500 transition hover:text-slate-900 disabled:opacity-50"
              >
                退出
              </button>
            </div>
            <div className="grid grid-cols-3 gap-1.5">
              <button type="button" onClick={selectAllSessions}
                      disabled={bulkDeleting || sessionCount === 0 || allSessionsSelected}
                      className="btn-secondary min-h-8 px-2 py-1 text-xs">
                全选
              </button>
              <button type="button" onClick={invertSelectedSessions}
                      disabled={bulkDeleting || sessionCount === 0}
                      className="btn-secondary min-h-8 px-2 py-1 text-xs">
                反选
              </button>
              <button type="button" onClick={clearSelectedSessions}
                      disabled={bulkDeleting || selectedCount === 0}
                      className="btn-secondary min-h-8 px-2 py-1 text-xs">
                清空
              </button>
            </div>
            <button
              type="button"
              onClick={() => void deleteSelectedSessions()}
              disabled={bulkDeleting || selectedCount === 0}
              className="btn-danger w-full min-h-8 px-3 py-1 text-xs"
            >
              {bulkDeleting ? '删除中...' : `删除已选 ${selectedCount}`}
            </button>
          </div>
        )}

        <div className="border-b border-slate-200 bg-white px-3 py-3">
          <button
            type="button"
            onClick={startNewSession}
            className={[
              'w-full rounded-md border px-3 py-3 text-left transition',
              sessionId === null
                ? 'border-blue-200 bg-blue-50 text-blue-950'
                : 'border-slate-200 bg-slate-50 hover:border-slate-300 hover:bg-white'
            ].join(' ')}
          >
            <span className="block text-sm font-semibold">新会话</span>
            <span className="mt-1 block text-xs text-slate-500">待创建历史记录</span>
          </button>
        </div>

        <div className="flex-1 space-y-1 overflow-y-auto bg-slate-50/80 p-2">
          {sessionsLoading && <div className="px-2 py-3 text-sm text-slate-500">加载中...</div>}
          {sessionsError && (
            <div className="m-1 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
              会话加载失败: {sessionsError}
            </div>
          )}
          {!sessionsLoading && !sessionsError && sessions.length === 0 && (
            <div className="rounded-md border border-dashed border-slate-300 bg-white px-3 py-4 text-sm text-slate-500">
              暂无历史会话。
            </div>
          )}
          {sessions.map(s => {
            const active = s.id === sessionId;
            const selected = selectedSessionIds.has(s.id);
            return (
              <div
                key={s.id}
                className={[
                  'session-row group',
                  active ? 'session-row-active' : 'session-row-idle',
                  selected && !active ? 'session-row-selected' : ''
                ].join(' ')}
              >
                {selectionMode && (
                  <label className="flex cursor-pointer items-center pl-2">
                    <input
                      type="checkbox"
                      checked={selected}
                      disabled={bulkDeleting}
                      onChange={() => toggleSessionSelected(s.id)}
                      className="h-4 w-4 rounded border-slate-300 text-blue-600 focus:ring-blue-500"
                      aria-label={`选择 ${sessionTitle(s)}`}
                    />
                  </label>
                )}
                <button
                  type="button"
                  onClick={() => selectionMode ? toggleSessionSelected(s.id) : void loadSession(s.id)}
                  disabled={messagesLoading || bulkDeleting}
                  className="min-w-0 flex-1 px-3 py-2.5 text-left disabled:cursor-not-allowed"
                  aria-current={active ? 'page' : undefined}
                >
                  <div className="truncate text-sm font-semibold">{sessionTitle(s)}</div>
                  <div className={['mt-1 flex items-center gap-2 text-xs', active ? 'text-slate-300' : 'text-slate-500'].join(' ')}>
                    <span className="h-1.5 w-1.5 rounded-full bg-current opacity-60" />
                    <span className="truncate">{formatSessionTime(s)}</span>
                  </div>
                </button>
                <div className="flex shrink-0 items-center pr-1 opacity-0 transition group-hover:opacity-100 focus-within:opacity-100">
                  <button
                    type="button"
                    onClick={() => void exportSession(s.id)}
                    disabled={selectionMode || bulkDeleting}
                    className={active ? 'px-2 text-xs text-slate-300 hover:text-white disabled:opacity-40' : 'px-2 text-xs text-slate-400 hover:text-slate-800 disabled:opacity-40'}
                    title="导出 JSONL"
                  >
                    导出
                  </button>
                  <button
                    type="button"
                    onClick={() => void deleteSession(s.id)}
                    disabled={Boolean(runsByKey[s.id]) || selectionMode || bulkDeleting || deleteBusyId === s.id}
                    className={active ? 'px-2 text-xs text-slate-300 hover:text-white disabled:opacity-40' : 'px-2 text-xs text-slate-400 hover:text-red-600 disabled:opacity-40'}
                    title="删除会话"
                  >
                    删除
                  </button>
                </div>
              </div>
            );
          })}
        </div>
      </aside>

      <section className="chat-panel">
        <div className="panel-header bg-white">
          <div className="flex items-start justify-between gap-3">
            <div className="min-w-0">
              <div className="section-title">Conversation</div>
              <div className="mt-1 truncate text-xl font-semibold text-slate-950">
                {activeSession ? sessionTitle(activeSession) : '新会话'}
              </div>
              <div className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-slate-500">
                <span>{sessionId ? `会话 ${sessionId.slice(0, 8)}` : '尚未保存'}</span>
                {messagesLoading && <span>加载消息中</span>}
                <span>{events.length} 条事件</span>
              </div>
            </div>
            {busy && (
              <button
                type="button"
                onClick={handleStop}
                className="btn-danger min-h-9 px-3 py-1.5"
                title="中断当前回复"
              >
                停止
              </button>
            )}
          </div>
        </div>

        <div ref={scrollRef} className="message-stream">
          {events.length === 0 && (
            <div className="mx-auto flex min-h-[48vh] max-w-2xl flex-col items-center justify-center text-center">
              <div className="grid h-14 w-14 place-items-center rounded-lg bg-blue-600 text-sm font-black text-white">
                AP
              </div>
              <h2 className="mt-4 text-2xl font-semibold text-slate-950">新会话已准备</h2>
              <p className="mt-2 max-w-sm text-sm leading-6 text-slate-500">
                当前没有消息记录。
              </p>
              <div className="mt-6 grid w-full gap-2 sm:grid-cols-3">
                <EmptyHint title="图片理解" body="粘贴或上传图片给 agent 分析。" />
                <EmptyHint title="手机操作" body="让 agent 调用已连接设备工具。" />
                <EmptyHint title="工作流" body="复杂任务会展示工具运行轨迹。" />
              </div>
            </div>
          )}
          {timelineItems.map(item => {
            if (item.kind === 'event') {
              return (
                <Bubble
                  key={`${item.event.type}-${item.index}`}
                  ev={item.event}
                  nextToolResult={matchingToolResult(events, item.index)}
                  onOpenImage={openLightbox}
                />
              );
            }
            if (item.kind === 'media_result') {
              return (
                <VisibleToolResult
                  key={`media-${item.event.data?.tool}-${item.index}`}
                  ev={item.event}
                  onOpenImage={openLightbox}
                />
              );
            }
            return (
              <ProcessPanel
                key={`process-${item.startIndex}`}
                item={item}
                busy={busy && item.endIndex >= events.length - 1}
                now={now}
                onOpenImage={openLightbox}
              />
            );
          })}
          {activeRun && <ThinkingRow startedAt={activeRun.startedAt} now={now} />}
          {queuedTurns.length > 0 && (
            <QueuedTurnsNotice count={queuedTurns.length} />
          )}
        </div>

        <form
          onSubmit={ev => { ev.preventDefault(); void send(); }}
          onDrop={handleDrop}
          onDragOver={handleDragOver}
          className="composer space-y-3"
        >
          {(pendingImages.length > 0 || composerError) && (
            <div className="space-y-2">
              {pendingImages.length > 0 && (
                <PendingImageGrid
                  items={pendingImages}
                  disabled={messagesLoading}
                  onRemove={removePendingImage}
                />
              )}
              {composerError && <div className="status-error py-1.5 text-xs">{composerError}</div>}
            </div>
          )}
          <div className="flex items-end gap-2">
            <input
              ref={fileInputRef}
              type="file"
              accept="image/jpeg,image/png,image/webp"
              multiple
              className="hidden"
              onChange={handleFileChange}
              disabled={messagesLoading}
            />
            <button
              type="button"
              onClick={() => fileInputRef.current?.click()}
              disabled={messagesLoading || pendingImages.length >= MAX_ATTACHMENTS}
              className="icon-button h-[42px] w-[42px]"
              title="添加图片"
              aria-label="添加图片"
            >
              +
            </button>
            <textarea
              value={input}
              onChange={e => setDraftForKey(activeDraftKey, e.target.value)}
              onPaste={handlePaste}
              onKeyDown={e => {
                if (e.key === 'Enter' && !e.shiftKey) {
                  e.preventDefault();
                  void send();
                }
              }}
              rows={1}
              placeholder={busy ? '当前会话生成中，可以继续输入下一条...' : '输入消息或粘贴图片...'}
              className="field-input mt-0 min-h-[42px] max-h-32 flex-1 resize-none"
              disabled={messagesLoading}
            />
            <button
              type="submit"
              disabled={(!input.trim() && pendingImages.length === 0) || messagesLoading}
              className="btn-primary h-[42px] px-4"
            >
              {busy ? '排队' : '发送'}
            </button>
          </div>
        </form>
      </section>

      <aside className="context-panel">
        <div className="panel-header">
          <div className="section-title">Status</div>
          <div className="mt-1 text-xl font-semibold text-slate-950">运行状态</div>
        </div>
        <div className="flex-1 space-y-3 overflow-y-auto bg-slate-50/80 p-3">
          <div className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
            <div className="flex items-center gap-2 text-xs text-slate-500">
              <span className={['h-2 w-2 rounded-full', anyBusy ? 'animate-pulse bg-amber-300' : 'bg-emerald-300'].join(' ')} />
              <span>{anyBusy ? `处理中 ${Object.keys(runsByKey).length}` : '空闲'}</span>
            </div>
            <div className="mt-4 grid grid-cols-2 gap-3 xl:grid-cols-3">
              <Metric label="消息" value={String(events.filter(ev => ev.type === 'user_message' || ev.type === 'assistant_message').length)} />
              <Metric label="工具" value={String(toolCallCount)} />
              <Metric label="结果" value={String(toolResultCount)} />
              <Metric label="图片" value={String(pendingImages.length)} />
              <Metric label="排队" value={String(queuedTurns.length)} />
            </div>
          </div>

          <div className="rounded-lg border border-slate-200 bg-white p-3">
            <div className="text-xs font-semibold uppercase text-slate-500">当前会话</div>
            <div className="mt-2 min-w-0 truncate text-sm font-semibold text-slate-950">
              {activeSession ? sessionTitle(activeSession) : '新会话'}
            </div>
            <div className="mt-1 text-xs text-slate-500">
              {sessionId ? sessionId : 'pending'}
            </div>
            {lastActivity && (
              <div className="mt-3 border-t border-slate-100 pt-3 text-xs text-slate-500">
                最近活动 {new Date(lastActivity).toLocaleString()}
              </div>
            )}
          </div>

          <div className="rounded-lg border border-slate-200 bg-white p-3">
            <div className="text-xs font-semibold uppercase text-slate-500">通道</div>
            <div className="mt-3 grid gap-2 text-xs text-slate-600">
              <div className="flex items-center justify-between">
                <span>SSE</span>
                <span className="font-semibold text-emerald-600">ready</span>
              </div>
              <div className="flex items-center justify-between">
                <span>Uploads</span>
                <span className="font-semibold text-emerald-600">ready</span>
              </div>
              <div className="flex items-center justify-between">
                <span>Device tools</span>
                <span className={anyBusy ? 'font-semibold text-amber-600' : 'font-semibold text-slate-500'}>
                  {anyBusy ? 'active' : 'idle'}
                </span>
              </div>
            </div>
          </div>
        </div>
      </aside>

      {lightboxSrc && (
        <div
          className="fixed inset-0 z-50 bg-slate-950/90"
          onClick={closeLightbox}
          onWheel={handleLightboxWheel}
        >
          <div
            className="flex h-full w-full cursor-zoom-out items-center justify-center overflow-auto p-4"
            onContextMenu={e => e.stopPropagation()}
          >
            <div
              className="cursor-default rounded-md bg-white/5 p-2 shadow-2xl"
              style={{ transform: `scale(${lightboxScale})`, transformOrigin: 'center center' }}
              onClick={e => e.stopPropagation()}
            >
              <AuthImage
                src={lightboxSrc}
                className="max-h-[82vh] max-w-[92vw] select-none object-contain"
                alt="预览"
                draggable={false}
              />
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

type OpenImage = (src: string) => void;

const MAX_ATTACHMENTS = 4;
const MAX_IMAGE_BYTES = 10 * 1024 * 1024;
const SUPPORTED_IMAGE_TYPES = new Set(['image/jpeg', 'image/png', 'image/webp']);

type PendingImage = PendingDraftImage;

interface UploadedChatImage {
  type: 'image';
  imageUrl: string;
  assetId?: string;
  contentType?: string;
  bytes?: number;
  name?: string;
  width?: number;
  height?: number;
}

type TimelineItem =
  | { kind: 'event'; event: ChatEvent; index: number }
  | { kind: 'process'; events: ChatEvent[]; startIndex: number; endIndex: number }
  | { kind: 'media_result'; event: ChatEvent; index: number };

type StandardToolMedia =
  | { mode: 'primary'; primary: any }
  | { mode: 'grid'; items: any[] }
  | { mode: 'collapsed'; items: any[]; summary: string }
  | { mode: 'details'; result: any; summary: string };

async function uploadPendingImages(items: PendingImage[]): Promise<UploadedChatImage[]> {
  const out: UploadedChatImage[] = [];
  for (const item of items) {
    const uploaded = await api.uploadPhoto(item.file);
    out.push({
      type: 'image',
      imageUrl: uploaded.imageUrl,
      assetId: uploaded.assetId,
      contentType: uploaded.contentType || item.file.type,
      bytes: uploaded.bytes || item.file.size,
      name: item.file.name || uploaded.assetId,
      width: item.width,
      height: item.height
    });
  }
  return out;
}

function toChatAttachment(item: UploadedChatImage) {
  return {
    imageUrl: item.imageUrl,
    assetId: item.assetId,
    contentType: item.contentType,
    bytes: item.bytes,
    name: item.name,
    width: item.width,
    height: item.height
  };
}

function readImageDimensions(file: File): Promise<{ width?: number; height?: number }> {
  return new Promise(resolve => {
    const url = URL.createObjectURL(file);
    const img = new Image();
    img.onload = () => {
      const result = { width: img.naturalWidth || undefined, height: img.naturalHeight || undefined };
      URL.revokeObjectURL(url);
      resolve(result);
    };
    img.onerror = () => {
      URL.revokeObjectURL(url);
      resolve({});
    };
    img.src = url;
  });
}

function buildTimelineItems(events: ChatEvent[]): TimelineItem[] {
  const items: TimelineItem[] = [];
  let i = 0;
  while (i < events.length) {
    const ev = events[i];
    if (ev.type === 'user_message') {
      items.push({ kind: 'event', event: ev, index: i });
      i += 1;
      continue;
    }

    const segmentStart = i;
    const segment: ChatEvent[] = [];
    while (i < events.length && events[i].type !== 'user_message') {
      segment.push(events[i]);
      i += 1;
    }

    if (!segment.some(isToolActivityEvent)) {
      segment.forEach((event, offset) => {
        items.push({ kind: 'event', event, index: segmentStart + offset });
      });
      continue;
    }

    const finalAssistantOffset = finalAssistantIndex(segment);
    if (finalAssistantOffset < 0) {
      items.push({
        kind: 'process',
        events: segment,
        startIndex: segmentStart,
        endIndex: segmentStart + segment.length - 1
      });
      pushVisibleToolResults(items, segment, segmentStart);
      continue;
    }

    const processEvents = segment.slice(0, finalAssistantOffset);
    if (processEvents.length > 0) {
      items.push({
        kind: 'process',
        events: processEvents,
        startIndex: segmentStart,
        endIndex: segmentStart + finalAssistantOffset - 1
      });
    }
    pushVisibleToolResults(items, processEvents, segmentStart);
    items.push({
      kind: 'event',
      event: segment[finalAssistantOffset],
      index: segmentStart + finalAssistantOffset
    });

    const trailing = segment.slice(finalAssistantOffset + 1);
    if (trailing.length > 0) {
      if (trailing.some(isToolActivityEvent)) {
        items.push({
          kind: 'process',
          events: trailing,
          startIndex: segmentStart + finalAssistantOffset + 1,
          endIndex: segmentStart + segment.length - 1
        });
        pushVisibleToolResults(items, trailing, segmentStart + finalAssistantOffset + 1);
      } else {
        trailing.forEach((event, offset) => {
          items.push({ kind: 'event', event, index: segmentStart + finalAssistantOffset + 1 + offset });
        });
      }
    }
  }
  return items;
}

function pushVisibleToolResults(items: TimelineItem[], events: ChatEvent[], startIndex: number) {
  events.forEach((event, offset) => {
    if (shouldShowToolResultOutsideProcess(event)) {
      items.push({ kind: 'media_result', event, index: startIndex + offset });
    }
  });
}

function isToolActivityEvent(ev: ChatEvent | undefined) {
  return ev?.type === 'tool_call_started' || ev?.type === 'tool_call_result';
}

function finalAssistantIndex(events: ChatEvent[]) {
  let lastToolOffset = -1;
  let lastAssistantOffset = -1;
  events.forEach((ev, index) => {
    if (isToolActivityEvent(ev)) lastToolOffset = index;
    if (ev.type === 'assistant_message') lastAssistantOffset = index;
  });
  return lastAssistantOffset > lastToolOffset ? lastAssistantOffset : -1;
}

function messageToEvent(m: MessageDto): ChatEvent | null {
  const metadata = asRecord(m.metadata);
  const base = {
    content: m.content,
    attachments: normalizeMessageAttachments(metadata?.attachments),
    createdAt: m.createdAt,
    durationMs: numberOrNull(metadata?.durationMs)
  };
  if (m.role === 'USER') {
    return { type: 'user_message', data: base };
  }
  if (m.role === 'ASSISTANT') {
    return { type: 'assistant_message', data: base };
  }
  if (m.role === 'TOOL_CALL') {
    return {
      type: 'tool_call_started',
      data: {
        deviceId: metadata?.deviceId,
        tool: metadata?.tool ?? 'tool',
        args: metadata?.args ?? {},
        createdAt: m.createdAt
      }
    };
  }
  if (m.role === 'TOOL_RESULT') {
    return {
      type: 'tool_call_result',
      data: {
        tool: metadata?.tool ?? 'tool',
        result: metadata?.result ?? m.content,
        createdAt: m.createdAt
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

function normalizeMessageAttachments(value: unknown): UploadedChatImage[] {
  if (!Array.isArray(value)) return [];
  const out: UploadedChatImage[] = [];
  value.forEach(item => {
    const r = asRecord(item);
    if (!r) return;
    const imageUrlValue = typeof r.imageUrl === 'string'
      ? r.imageUrl
      : typeof r.image_url === 'string'
        ? r.image_url
        : null;
    if (!imageUrlValue) return;
    out.push({
      type: 'image' as const,
      imageUrl: imageUrlValue,
      assetId: typeof r.assetId === 'string' ? r.assetId : typeof r.asset_id === 'string' ? r.asset_id : undefined,
      contentType: typeof r.contentType === 'string' ? r.contentType : typeof r.content_type === 'string' ? r.content_type : undefined,
      bytes: typeof r.bytes === 'number' ? r.bytes : undefined,
      name: typeof r.name === 'string' ? r.name : undefined,
      width: typeof r.width === 'number' ? r.width : undefined,
      height: typeof r.height === 'number' ? r.height : undefined
    });
  });
  return out;
}

function numberOrNull(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

function markLatestAssistantDuration(
  events: ChatEvent[],
  durationMs: number
) {
  if (!Number.isFinite(durationMs) || durationMs < 0) return events;
  const copy = [...events];
  for (let i = copy.length - 1; i >= 0; i -= 1) {
    const ev = copy[i];
    if (ev.type === 'assistant_message') {
      copy[i] = {
        ...ev,
        data: { ...ev.data, durationMs }
      };
      return copy;
    }
  }
  return events;
}

function appendCancelEvent(
  events: ChatEvent[],
  sentEventsIndex: number
) {
  if (sentEventsIndex < 0 || events.length <= sentEventsIndex) return events;
  const last = events[events.length - 1];
  if (last?.type === 'error' && last.data?.code === 'client_cancelled') return events;
  return [
    ...events,
    {
      type: 'error',
      data: {
        code: 'client_cancelled',
        message: '已取消本次执行',
        createdAt: new Date().toISOString()
      }
    }
  ];
}

function hasRicherToolHistory(cached: ChatEvent[], loaded: ChatEvent[]) {
  const cachedToolResults = cached.filter(ev => ev.type === 'tool_call_result').length;
  const loadedToolResults = loaded.filter(ev => ev.type === 'tool_call_result').length;
  if (cachedToolResults > loadedToolResults) return true;

  const cachedRenderable = cached.some(ev => ev.type === 'tool_call_result' && isRenderableToolResult(ev));
  const loadedRenderable = loaded.some(ev => ev.type === 'tool_call_result' && isRenderableToolResult(ev));
  return cachedRenderable && !loadedRenderable;
}

function shouldShowToolResultOutsideProcess(ev: ChatEvent) {
  if (ev.type !== 'tool_call_result') return false;
  const tool = ev.data?.tool;
  const result = ev.data?.result;
  if (isStandardDisplayableToolResult(result)) return true;
  if (isStandardHiddenToolResult(result)) return false;
  return (
    (isPhotoListTool(tool) && hasAnyPhotoImage(result?.photos)) ||
    (tool === 'photos.semantic_search' && (Boolean(semanticPrimaryPhoto(result)) || hasAnyPhotoImage(result?.photos))) ||
    (tool === 'photos.get_full' && hasPhotoImage(result)) ||
    (tool === 'videos.list_recent' && Array.isArray(result?.videos))
  );
}

function isRenderableToolResult(ev: ChatEvent) {
  const tool = ev.data?.tool;
  const result = ev.data?.result;
  if (isStandardDisplayableToolResult(result)) return true;
  if (isStandardHiddenToolResult(result)) return false;
  return (
    (isPhotoListTool(tool) && hasAnyPhotoImage(result?.photos)) ||
    (tool === 'photos.semantic_search' && (Boolean(semanticPrimaryPhoto(result)) || hasAnyPhotoImage(result?.photos))) ||
    (tool === 'photos.get_full' && hasPhotoImage(result)) ||
    (tool === 'photos.list_albums' && Array.isArray(result?.albums)) ||
    (tool === 'videos.list_recent' && Array.isArray(result?.videos))
  );
}

function isPhotoListTool(tool: unknown) {
  return (
    tool === 'photos.list_recent' ||
    tool === 'photos.list_by_album' ||
    tool === 'photos.recent_screenshots'
  );
}

function toolDisplayPolicy(result: unknown): string | null {
  const r = asRecord(result);
  const display = asRecord(r?.display);
  const value = r?.display_policy ?? display?.policy;
  return typeof value === 'string' ? value : null;
}

function toolResultType(result: unknown): string | null {
  const value = asRecord(result)?.result_type;
  return typeof value === 'string' ? value : null;
}

function isStandardHiddenToolResult(result: unknown) {
  const policy = toolDisplayPolicy(result);
  return policy === 'hidden_candidates' || policy === 'debug_only';
}

function isStandardDisplayableToolResult(result: unknown) {
  const policy = toolDisplayPolicy(result);
  if (policy === 'show_primary' || policy === 'show_grid' || policy === 'collapsed_candidates') {
    return hasDisplayableToolMedia(result);
  }
  if (isStandardHiddenToolResult(result)) return false;
  const type = toolResultType(result);
  return (
    (type === 'confirmed' || type === 'results') &&
    !asRecord(result)?.candidate_only &&
    hasDisplayableToolMedia(result)
  );
}

function hasDisplayableToolMedia(result: unknown) {
  return Boolean(primaryToolImage(result)) ||
    hasAnyPhotoImage(toolResultItems(result)) ||
    hasAnyPhotoImage(asRecord(result)?.photos);
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

function formatMessageTime(value: unknown) {
  const date = typeof value === 'string' ? new Date(value) : null;
  if (!date || Number.isNaN(date.getTime())) return '';
  return date.toLocaleTimeString(undefined, {
    hour: '2-digit',
    minute: '2-digit'
  });
}

function formatDuration(ms: unknown) {
  if (typeof ms !== 'number' || !Number.isFinite(ms) || ms < 0) return '';
  if (ms < 1000) return `${Math.max(1, Math.round(ms))}ms`;
  const seconds = ms / 1000;
  if (seconds < 60) return `${seconds.toFixed(seconds < 10 ? 1 : 0)}s`;
  const minutes = Math.floor(seconds / 60);
  const rest = Math.round(seconds % 60);
  return `${minutes}m ${rest}s`;
}

function clampZoom(value: number) {
  return Math.min(5, Math.max(0.25, Number(value.toFixed(2))));
}

function elapsedSince(startedAt: number, now: number) {
  if (!startedAt) return '0s';
  return formatDuration(Math.max(0, now - startedAt)) || '0s';
}

function processStartTime(item: Extract<TimelineItem, { kind: 'process' }>) {
  for (const ev of item.events) {
    const value = ev.data?.createdAt;
    const time = typeof value === 'string' ? new Date(value).getTime() : NaN;
    if (Number.isFinite(time)) return time;
  }
  return null;
}

function processDuration(item: Extract<TimelineItem, { kind: 'process' }>, now: number, isActive: boolean) {
  const start = processStartTime(item);
  if (!start) return '';
  if (isActive) return elapsedSince(start, now);

  for (let i = item.events.length - 1; i >= 0; i -= 1) {
    const value = item.events[i].data?.createdAt;
    const end = typeof value === 'string' ? new Date(value).getTime() : NaN;
    if (Number.isFinite(end) && end >= start) {
      return formatDuration(end - start);
    }
  }
  return '';
}

function processStatus(item: Extract<TimelineItem, { kind: 'process' }>) {
  const hasPendingCall = item.events.some((ev, index) => {
    if (ev.type !== 'tool_call_started') return false;
    const result = findResultForToolCall(item.events, index);
    return !result;
  });
  const hasError = item.events.some(ev => ev.type === 'tool_call_result' && hasToolError(ev.data?.result));
  if (hasError) return 'failed';
  if (hasPendingCall) return 'running';
  return 'succeeded';
}

function processToolNames(item: Extract<TimelineItem, { kind: 'process' }>) {
  const names: string[] = [];
  item.events.forEach(ev => {
    if (ev.type !== 'tool_call_started') return;
    const name = String(ev.data?.tool ?? 'tool');
    if (!names.includes(name)) names.push(name);
  });
  return names;
}

function toolProgress(ev: ChatEvent | undefined) {
  const index = numberOrNull(ev?.data?.toolIndex);
  const max = numberOrNull(ev?.data?.maxToolCalls);
  if (!index || !max) return null;
  return { index, max };
}

function processToolProgress(item: Extract<TimelineItem, { kind: 'process' }>) {
  for (let i = item.events.length - 1; i >= 0; i -= 1) {
    const progress = toolProgress(item.events[i]);
    if (progress) return progress;
  }
  return null;
}

function processSummary(item: Extract<TimelineItem, { kind: 'process' }>) {
  const notes = item.events
    .filter(ev => ev.type === 'assistant_message')
    .map(ev => String(ev.data?.content ?? '').trim())
    .filter(Boolean);
  const lastNote = notes[notes.length - 1];
  if (lastNote) return lastNote.replace(/\s+/g, ' ');

  const names = processToolNames(item);
  if (names.length === 0) return '';
  if (names.length === 1) return names[0];
  return `${names[0]} 等 ${names.length} 种工具`;
}

function findResultForToolCall(events: ChatEvent[], index: number) {
  const current = events[index];
  if (current?.type !== 'tool_call_started') return null;
  const tool = current.data?.tool;
  for (let i = index + 1; i < events.length; i += 1) {
    const ev = events[i];
    if (ev.type === 'tool_call_started') return null;
    if (ev.type === 'tool_call_result' && ev.data?.tool === tool) return ev;
    if (ev.type === 'error') {
      return {
        type: 'tool_call_result',
        data: {
          tool,
          result: { error: { message: ev.data?.message ?? 'tool failed' } },
          createdAt: ev.data?.createdAt
        }
      };
    }
  }
  return null;
}

function matchingToolResult(events: ChatEvent[], index: number) {
  return findResultForToolCall(events, index);
}

function hasToolError(result: unknown) {
  if (!result || typeof result !== 'object') return false;
  const r = result as Record<string, any>;
  if (r.error || r.error_message) return true;
  if (r.ok === false || r.success === false) return true;
  if (r.result && typeof r.result === 'object') return hasToolError(r.result);
  return false;
}

function Metric({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <div className="text-[11px] uppercase text-slate-500">{label}</div>
      <div className="mt-1 text-xl font-semibold text-slate-950">{value}</div>
    </div>
  );
}

function EmptyHint({ title, body }: { title: string; body: string }) {
  return (
    <div className="rounded-lg border border-slate-200 bg-white/80 p-3 text-left shadow-sm">
      <div className="text-sm font-semibold text-slate-950">{title}</div>
      <div className="mt-1 text-xs leading-5 text-slate-500">{body}</div>
    </div>
  );
}

function ToolCallRow({ ev, resultEvent }: { ev: ChatEvent; resultEvent?: ChatEvent | null }) {
  const status = resultEvent
    ? hasToolError(resultEvent.data?.result) ? 'failed' : 'succeeded'
    : 'running';
  const dotClass = status === 'running'
    ? 'bg-amber-400 animate-pulse'
    : status === 'failed'
      ? 'bg-red-500'
      : 'bg-emerald-500';
  const label = status === 'running'
    ? '调用'
    : status === 'failed'
      ? '调用失败'
      : '调用完成';

  return (
    <div className="flex min-w-0 items-center gap-2 rounded-md border border-slate-200 bg-white/90 px-3 py-2 text-xs text-slate-500 shadow-sm">
      <span className={`inline-block h-2 w-2 shrink-0 rounded-full ${dotClass}`} />
      <span className="shrink-0">{label}</span>
      <code className="tool-chip">{ev.data?.tool}</code>
      {ev.data?.args && <span className="min-w-0 truncate text-slate-400">({JSON.stringify(ev.data.args)})</span>}
    </div>
  );
}

function PendingImageGrid({
  items,
  disabled,
  onRemove
}: {
  items: PendingImage[];
  disabled: boolean;
  onRemove: (id: string) => void;
}) {
  return (
    <div className="flex flex-wrap gap-2">
      {items.map(item => (
        <div key={item.id} className="relative h-20 w-20 overflow-hidden rounded-md border border-slate-200 bg-slate-100 shadow-sm">
          <img src={item.previewUrl} alt={item.file.name || '待发送图片'} className="h-full w-full object-cover" />
          <button
            type="button"
            onClick={() => onRemove(item.id)}
            disabled={disabled}
            className="absolute right-1 top-1 h-6 w-6 rounded-full bg-black/65 text-sm leading-6 text-white transition hover:bg-black/80 disabled:opacity-50"
            title="移除图片"
            aria-label="移除图片"
          >
            x
          </button>
        </div>
      ))}
    </div>
  );
}

function MessageAttachmentGrid({
  items,
  onOpenImage
}: {
  items: UploadedChatImage[];
  onOpenImage: OpenImage;
}) {
  return (
    <div className="grid max-w-xs grid-cols-2 justify-end gap-2">
      {items.map(item => {
        const src = imageUrl(item.imageUrl);
        if (!src) return null;
        return (
          <AuthImage
            key={item.imageUrl}
            src={src}
            alt={item.name ?? '图片附件'}
            title={item.name}
            onOpen={() => onOpenImage(src)}
            className="media-thumb h-32 w-32"
          />
        );
      })}
    </div>
  );
}

function ProcessPanel({
  item,
  busy,
  now,
  onOpenImage
}: {
  item: Extract<TimelineItem, { kind: 'process' }>;
  busy: boolean;
  now: number;
  onOpenImage: OpenImage;
}) {
  const status = processStatus(item);
  const active = busy && status === 'running';
  const duration = processDuration(item, now, active);
  const toolCalls = item.events.filter(ev => ev.type === 'tool_call_started');
  const toolResults = item.events.filter(ev => ev.type === 'tool_call_result');
  const progress = processToolProgress(item);
  const names = processToolNames(item);
  const summary = processSummary(item);
  const visibleNames = names.slice(0, 3);
  const overflowNames = names.length - visibleNames.length;
  const statusText = active
    ? '处理中'
    : status === 'failed'
      ? '处理遇到问题'
      : '已处理';
  const dotClass = active
    ? 'bg-amber-400 animate-pulse'
    : status === 'failed'
      ? 'bg-red-500'
      : 'bg-emerald-500';

  return (
    <details className="group/process rounded-lg border border-slate-200 bg-white shadow-sm open:bg-slate-50/80">
      <summary className="flex cursor-pointer list-none items-start gap-3 px-3 py-2.5 text-xs text-slate-500 marker:hidden">
        <span className={`mt-1 h-2 w-2 shrink-0 rounded-full ${dotClass}`} />
        <span className="grid min-w-0 flex-1 gap-1">
          <span className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1">
            <span className="font-semibold text-slate-700">{statusText}</span>
            {duration && <span>{duration}</span>}
            <span>{progress ? `${progress.index}/${progress.max}` : toolCalls.length} 次调用</span>
            {toolResults.length > 0 && <span>{toolResults.length} 个结果</span>}
            {visibleNames.map(name => (
              <code key={name} className="tool-chip">
                {name}
              </code>
            ))}
            {overflowNames > 0 && <span>+{overflowNames}</span>}
          </span>
          {summary && <span className="min-w-0 truncate text-slate-500">{summary}</span>}
        </span>
        <span className="mt-0.5 shrink-0 text-slate-400 transition group-open/process:rotate-90">›</span>
      </summary>
      <div className="border-t border-slate-100 px-3 py-3">
        <div className="space-y-2">
          {item.events.map((ev, index) => ev.type === 'tool_call_started' ? (
            <ToolCallDetail
              key={`call-${item.startIndex}-${index}`}
              ev={ev}
              resultEvent={findResultForToolCall(item.events, index)}
            />
          ) : ev.type === 'tool_call_result' ? (
            <div key={`result-${item.startIndex}-${index}`} className="tool-card">
              <div className="mb-2 flex items-center gap-2 text-xs font-medium text-slate-600">
                <span className="text-slate-400">工具</span>
                <code className="tool-chip">{ev.data?.tool}</code>
                <span>返回结果</span>
              </div>
              {shouldShowToolResultOutsideProcess(ev) ? (
                <ToolResultExternalSummary ev={ev} />
              ) : (
                <ToolResult tool={ev.data?.tool} result={ev.data?.result} onOpenImage={onOpenImage} />
              )}
            </div>
          ) : ev.type === 'assistant_message' ? (
            <div key={`note-${item.startIndex}-${index}`} className="assistant-card">
              <MarkdownMessage content={ev.data?.content ?? ''} />
            </div>
          ) : (
            <Bubble
              key={`event-${item.startIndex}-${index}`}
              ev={ev}
              nextToolResult={findResultForToolCall(item.events, index)}
              onOpenImage={onOpenImage}
            />
          ))}
        </div>
      </div>
    </details>
  );
}

function ToolResultExternalSummary({ ev }: { ev: ChatEvent }) {
  const count = visibleToolResultCount(ev);
  return (
    <div className="flex min-w-0 items-center gap-2 text-xs text-slate-500">
      <span className="h-1.5 w-1.5 shrink-0 rounded-full bg-blue-500" />
      <span className="min-w-0 truncate">
        结果已在对话中单独展示{count ? ` · ${count} 项` : ''}
      </span>
    </div>
  );
}

function visibleToolResultCount(ev: ChatEvent): number | null {
  const result = ev.data?.result;
  const media = standardToolMedia(result);
  if (media?.mode === 'primary') return 1;
  if (media?.mode === 'grid' || media?.mode === 'collapsed') return media.items.length;

  const tool = ev.data?.tool;
  if (tool === 'photos.get_full' && hasPhotoImage(result)) return 1;
  if ((isPhotoListTool(tool) || tool === 'photos.semantic_search') && Array.isArray(result?.photos)) {
    return result.photos.length;
  }
  if (tool === 'videos.list_recent' && Array.isArray(result?.videos)) return result.videos.length;
  const items = toolResultItems(result);
  return items.length > 0 ? items.length : null;
}

function ToolCallDetail({ ev, resultEvent }: { ev: ChatEvent; resultEvent?: ChatEvent | null }) {
  const status = resultEvent
    ? hasToolError(resultEvent.data?.result) ? 'failed' : 'succeeded'
    : 'running';
  const dotClass = status === 'running'
    ? 'bg-amber-400 animate-pulse'
    : status === 'failed'
      ? 'bg-red-500'
      : 'bg-emerald-500';
  const label = status === 'running'
    ? '调用中'
    : status === 'failed'
      ? '调用失败'
      : '调用完成';
  const args = ev.data?.args;
  const progress = toolProgress(ev);

  return (
    <div className="rounded-md border border-slate-200 bg-white p-2 text-xs text-slate-600">
      <div className="flex min-w-0 items-center gap-2">
        <span className={`h-2 w-2 shrink-0 rounded-full ${dotClass}`} />
        <span className="shrink-0">{label}</span>
        {progress && <span className="shrink-0 text-slate-400">第 {progress.index}/{progress.max} 个工具</span>}
        <code className="tool-chip">{ev.data?.tool}</code>
        {args && <span className="min-w-0 truncate text-slate-400">({JSON.stringify(args)})</span>}
      </div>
    </div>
  );
}

function VisibleToolResult({ ev, onOpenImage }: { ev: ChatEvent; onOpenImage: OpenImage }) {
  const tool = String(ev.data?.tool ?? 'tool');
  return (
    <div className="flex justify-start">
      <div className="tool-card w-full max-w-3xl">
        <div className="mb-2 flex items-center gap-2 text-xs text-slate-500">
          <span className="h-2 w-2 rounded-full bg-blue-500" />
          <span className="font-medium text-slate-600">{toolResultTitle(tool)}</span>
          <code className="tool-chip">{tool}</code>
        </div>
        <ToolResult tool={tool} result={ev.data?.result} onOpenImage={onOpenImage} />
      </div>
    </div>
  );
}

function toolResultTitle(tool: string) {
  if (isPhotoListTool(tool) || tool === 'photos.semantic_search' || tool === 'photos.get_full') return '图片结果';
  if (tool === 'videos.list_recent') return '视频结果';
  return '工具结果';
}

function Bubble({
  ev,
  nextToolResult,
  onOpenImage
}: {
  ev: ChatEvent;
  nextToolResult?: ChatEvent | null;
  onOpenImage: OpenImage;
}) {
  switch (ev.type) {
    case 'user_message': {
      const time = formatMessageTime(ev.data?.createdAt);
      const attachments = normalizeMessageAttachments(ev.data?.attachments);
      return (
        <div className="flex justify-end">
          <div className="max-w-[min(42rem,86%)]">
            <div className="space-y-2">
              {attachments.length > 0 && (
                <MessageAttachmentGrid
                  items={attachments}
                  onOpenImage={onOpenImage}
                />
              )}
              {ev.data?.content && (
                <div className="user-bubble whitespace-pre-wrap">
                  {ev.data.content}
                </div>
              )}
            </div>
            {time && <div className="mt-1 text-right text-[11px] text-slate-400">{time}</div>}
          </div>
        </div>
      );
    }
    case 'assistant_message': {
      const time = formatMessageTime(ev.data?.createdAt);
      const duration = formatDuration(ev.data?.durationMs);
      return (
        <div className="flex justify-start">
          <div className="max-w-[min(46rem,92%)]">
            <div className="assistant-card">
              <MarkdownMessage content={ev.data?.content ?? ''} />
            </div>
            {(time || duration) && (
              <div className="mt-1 text-[11px] text-slate-400">
                {time}{duration ? ` · 用时 ${duration}` : ''}
              </div>
            )}
          </div>
        </div>
      );
    }
    case 'tool_call_started':
      return <ToolCallRow ev={ev} resultEvent={nextToolResult} />;
    case 'tool_call_result':
      return <ToolResult tool={ev.data?.tool} result={ev.data?.result} onOpenImage={onOpenImage} />;
    case 'error':
      return (
        <div className="status-error">
          {ev.data?.message ?? '未知错误'}
        </div>
      );
    default:
      return null;
  }
}

function MarkdownMessage({ content }: { content: string }) {
  return (
    <div className="markdown-body">
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
          a: ({ node: _node, ...props }) => (
            <a {...props} target="_blank" rel="noreferrer" />
          )
        }}
      >
        {content}
      </ReactMarkdown>
    </div>
  );
}

function ThinkingRow({ startedAt, now }: { startedAt: number; now: number }) {
  return (
    <div className="flex justify-start">
      <div className="inline-flex items-center gap-2 rounded-full border border-slate-200 bg-white px-3 py-2 text-xs text-slate-500 shadow-sm">
        <span className="flex items-center gap-1" aria-label="回复中">
          <span className="h-1.5 w-1.5 rounded-full bg-blue-500 animate-bounce [animation-delay:-0.2s]" />
          <span className="h-1.5 w-1.5 rounded-full bg-blue-500 animate-bounce [animation-delay:-0.1s]" />
          <span className="h-1.5 w-1.5 rounded-full bg-blue-500 animate-bounce" />
        </span>
        <span>回复中 · {elapsedSince(startedAt, now)}</span>
      </div>
    </div>
  );
}

function QueuedTurnsNotice({ count }: { count: number }) {
  return (
    <div className="flex justify-end">
      <div className="rounded-full border border-blue-100 bg-blue-50 px-3 py-1.5 text-xs font-medium text-blue-700">
        已排队 {count} 条，当前回复结束后自动发送
      </div>
    </div>
  );
}

function ToolResult({ tool, result, onOpenImage }: {
  tool: string;
  result: any;
  onOpenImage: OpenImage;
}) {
  const standardMedia = standardToolMedia(result);
  if (standardMedia) {
    return <StandardToolMediaResult media={standardMedia} onOpenImage={onOpenImage} />;
  }

  if (Array.isArray(result?.photos) &&
      isPhotoListTool(tool)) {
    return <ThumbGrid items={result.photos} onOpenImage={onOpenImage} />;
  }

  if (tool === 'photos.semantic_search') {
    return <SemanticPhotoResult result={result} onOpenImage={onOpenImage} />;
  }

  if (tool === 'photos.get_full' && hasPhotoImage(result)) {
    const { big, small } = photoImageSources(result);
    const w = result.vision_width ?? result.source_width;
    const h = result.vision_height ?? result.source_height;
    return (
      <div className="max-w-md">
        <AuthImage src={small} alt={result.name}
                   title={result.name}
                   onOpen={() => big && onOpenImage(big)}
                   className="media-thumb max-h-96 w-full object-contain" />
        <div className="mt-1 text-xs text-slate-500">
          {result.name}{w && h ? ` · ${w}×${h}` : ''}
        </div>
      </div>
    );
  }

  if (tool === 'photos.list_albums' && Array.isArray(result?.albums)) {
    return (
      <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
        {result.albums.map((a: any) => {
          const { big, small: src } = photoImageSources(a);
          return (
            <div key={a.bucket_id} className="overflow-hidden rounded-md border border-slate-200 bg-white">
              {src ? (
                <AuthImage src={src} alt={a.name}
                           onOpen={() => onOpenImage(big ?? src)}
                           className="media-thumb h-32 w-full" />
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
    if (result.videos.length === 0) {
      return (
        <div className="rounded-md border border-dashed border-slate-200 bg-slate-50 px-3 py-4 text-sm text-slate-500">
          没查到视频。可能是手机里最近没有视频，或还没授予“读取视频”权限。
        </div>
      );
    }
    return (
      <div className="grid grid-cols-3 gap-2 sm:grid-cols-5">
        {result.videos.map((v: any) => {
          const src = firstImageUrl(
            v.thumb_url,
            v.thumbnail_url,
            v.cover_image_url,
            v.cover_url,
            v.thumbUrl,
            v.thumbnailUrl,
            v.coverImageUrl,
            v.coverUrl
          ) ?? imageDataUrl(v.thumb_b64);
          const sec = v.duration_ms ? Math.round(v.duration_ms / 1000) : 0;
          const m = Math.floor(sec / 60);
          const s = sec % 60;
          const dur = `${m}:${String(s).padStart(2, '0')}`;
          return (
            <div key={v.id} className="relative">
              {src ? (
                <AuthImage src={src} alt={v.name}
                           title={v.name}
                           onOpen={() => onOpenImage(src)}
                           className="media-thumb h-32 w-full" />
              ) : (
                <div className="h-32 w-full rounded-md bg-slate-200" />
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
      <summary className="cursor-pointer">工具 {tool} 返回结果</summary>
      <pre className="mt-1 overflow-x-auto rounded-md bg-slate-100 p-2">{JSON.stringify(result, null, 2)}</pre>
    </details>
  );
}

function StandardToolMediaResult({
  media,
  onOpenImage
}: {
  media: StandardToolMedia;
  onOpenImage: OpenImage;
}) {
  if (media.mode === 'details') {
    return (
      <details className="text-xs text-slate-600">
        <summary className="cursor-pointer">{media.summary}</summary>
        <pre className="mt-1 overflow-x-auto rounded-md bg-slate-100 p-2">{JSON.stringify(media.result, null, 2)}</pre>
      </details>
    );
  }

  if (media.mode === 'primary') {
    return <PrimaryPhotoCard photo={media.primary} onOpenImage={onOpenImage} />;
  }

  if (media.mode === 'collapsed') {
    return (
      <details className="text-xs text-slate-500">
        <summary className="cursor-pointer">{media.summary}</summary>
        <div className="mt-2">
          {hasAnyPhotoImage(media.items) ? (
            <ThumbGrid items={media.items} onOpenImage={onOpenImage} />
          ) : (
            <SelectedPhotoSummary photos={media.items} />
          )}
        </div>
      </details>
    );
  }

  return <ThumbGrid items={media.items} onOpenImage={onOpenImage} />;
}

function PrimaryPhotoCard({ photo, onOpenImage }: { photo: any; onOpenImage: OpenImage }) {
  const { big, small } = photoImageSources(photo);
  const w = photo?.vision_width ?? photo?.source_width ?? photo?.width;
  const h = photo?.vision_height ?? photo?.source_height ?? photo?.height;
  const preview = big ?? small;
  return (
    <div className="max-w-md">
      <AuthImage src={small} alt={photo?.name}
                 title={photo?.name}
                 onOpen={() => preview && onOpenImage(preview)}
                 className="media-thumb max-h-96 w-full object-contain" />
      <div className="mt-1 text-xs text-slate-500">
        {photo?.name ?? '图片结果'}{w && h ? ` · ${w}×${h}` : ''}
      </div>
    </div>
  );
}

function SemanticPhotoResult({ result, onOpenImage }: { result: any; onOpenImage: OpenImage }) {
  const primary = semanticPrimaryPhoto(result);
  if (primary) {
    const { big, small } = photoImageSources(primary);
    const w = primary.vision_width ?? primary.source_width;
    const h = primary.vision_height ?? primary.source_height;
    return (
      <div className="max-w-md">
        <AuthImage src={small} alt={primary.name}
                   title={primary.name}
                   onOpen={() => big && onOpenImage(big)}
                   className="media-thumb max-h-96 w-full object-contain" />
        <div className="mt-1 text-xs text-slate-500">
          {primary.name}{w && h ? ` · ${w}×${h}` : ''}
        </div>
      </div>
    );
  }

  const photos = Array.isArray(result?.photos) ? result.photos : [];
  const displayPolicy = result?.display_policy ?? result?.display?.policy;
  const requestedLimitValue = Number(result?.requested_limit ?? result?.limit ?? result?.display?.requested_limit ?? 1);
  const requestedLimit = Number.isFinite(requestedLimitValue) && requestedLimitValue > 0
    ? Math.floor(requestedLimitValue)
    : 1;
  const hiddenCandidates = displayPolicy === 'hidden_candidates' || result?.display === 'confirmed_only';

  if (hiddenCandidates) {
    const shown = photos.slice(0, Math.min(photos.length, requestedLimit));

    if (shown.length === 0) {
      return (
        <div className="text-xs text-slate-500 italic">
          已检索 0 张候选图，未找到可展示结果
        </div>
      );
    }

    if (!hasAnyPhotoImage(shown)) {
      return <SelectedPhotoSummary photos={shown} />;
    }

    return (
      <div className="space-y-1">
        <ThumbGrid items={shown} onOpenImage={onOpenImage} />
        {photos.length > shown.length && (
          <div className="text-xs text-slate-500 italic">
            已隐藏 {photos.length - shown.length} 张候选图
          </div>
        )}
      </div>
    );
  }

  if (result?.candidate_only) {
    return (
      <details className="text-xs text-slate-500">
        <summary className="cursor-pointer">
          已检索 {photos.length} 张候选图，等待模型确认
        </summary>
        <div className="mt-2">
          <ThumbGrid items={photos} onOpenImage={onOpenImage} />
        </div>
      </details>
    );
  }

  const shown = photos.slice(0, Math.min(photos.length, 3));
  const hidden = Math.max(0, photos.length - shown.length);

  return (
    <div className="space-y-2">
      <ThumbGrid items={shown} onOpenImage={onOpenImage} />
      {hidden > 0 && (
        <details className="text-xs text-slate-500">
          <summary className="cursor-pointer">还有 {hidden} 张候选图</summary>
          <div className="mt-2">
            <ThumbGrid items={photos.slice(shown.length)} onOpenImage={onOpenImage} />
          </div>
        </details>
      )}
    </div>
  );
}

function ThumbGrid({ items, onOpenImage }: { items: any[]; onOpenImage: OpenImage }) {
  return (
    <div className="grid grid-cols-2 gap-2 sm:grid-cols-4 xl:grid-cols-5">
      {items.map((p: any) => {
        const { big, small: src } = photoImageSources(p);
        if (!src) {
          return <PhotoPlaceholder key={p.id} photo={p} />;
        }
        return (
          <AuthImage key={p.id} src={src} alt={p.name}
                     title={p.name}
                     onOpen={() => onOpenImage(big ?? src)}
                     className="media-thumb h-32 w-full" />
        );
      })}
    </div>
  );
}

function SelectedPhotoSummary({ photos }: { photos: any[] }) {
  return (
    <div className="space-y-1">
      {photos.map(photo => (
        <div key={photo.id ?? photo.asset_id ?? photo.name}
             className="rounded-md border border-slate-200 bg-slate-50 px-3 py-2 text-xs text-slate-600">
          <div className="font-medium text-slate-700">
            已命中图片{photo.name ? `：${photo.name}` : ''}
          </div>
          <div className="mt-0.5">
            id: {photo.id ?? photo.asset_id ?? 'unknown'}
            {photo.date_taken_ms ? ` · ${formatPhotoDate(photo.date_taken_ms)}` : ''}
          </div>
        </div>
      ))}
    </div>
  );
}

function PhotoPlaceholder({ photo }: { photo: any }) {
  return (
    <div className="flex h-32 w-full items-center justify-center rounded-md border border-slate-200 bg-slate-100 px-2 text-center text-xs text-slate-500">
      {photo?.name ?? photo?.id ?? '图片已命中'}
    </div>
  );
}

function AuthImage({
  src,
  alt,
  title,
  className,
  onOpen,
  draggable
}: {
  src: string | null;
  alt?: string;
  title?: string;
  className?: string;
  onOpen?: () => void;
  draggable?: boolean;
}) {
  const [objectUrl, setObjectUrl] = useState<string | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    if (!src || !needsAuthenticatedFetch(src)) {
      setObjectUrl(null);
      setFailed(false);
      return;
    }

    let cancelled = false;
    const ctrl = new AbortController();
    const token = getToken();
    setObjectUrl(null);
    setFailed(false);

    fetch(src, {
      headers: token ? { Authorization: `Bearer ${token}` } : {},
      signal: ctrl.signal
    })
      .then(resp => {
        if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
        return resp.blob();
      })
      .then(blob => {
        if (cancelled) return;
        setObjectUrl(URL.createObjectURL(blob));
      })
      .catch(() => {
        if (!cancelled) setFailed(true);
      });

    return () => {
      cancelled = true;
      ctrl.abort();
    };
  }, [src]);

  useEffect(() => {
    return () => {
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [objectUrl]);

  const displaySrc = objectUrl ?? (src && !needsAuthenticatedFetch(src) ? src : null);
  if (!displaySrc || failed) {
    return <div className="h-32 w-full rounded-md border border-slate-200 bg-slate-100" />;
  }

  return (
    <img src={displaySrc}
         alt={alt}
         title={title}
         draggable={draggable}
         onClick={onOpen}
         className={className} />
  );
}

function needsAuthenticatedFetch(src: string) {
  return src.startsWith('/api/uploads/');
}

function semanticPrimaryPhoto(result: any) {
  const media = displayMediaItems(result);
  const firstMedia = media.find(item => hasPhotoImage(item));
  if (firstMedia) return firstMedia;
  const primary = result?.primary_image ?? result?.primaryImage ?? result?.primary;
  const candidate = primary?.result ?? primary;
  return hasPhotoImage(candidate) ? candidate : null;
}

function standardToolMedia(result: unknown): StandardToolMedia | null {
  if (isStandardHiddenToolResult(result)) return null;
  const policy = toolDisplayPolicy(result);
  const candidateOnly = Boolean(asRecord(result)?.candidate_only);
  const primary = primaryToolImage(result);
  const items = toolResultItems(result).filter(item => asRecord(item));
  const imageItems = items.filter(item => hasPhotoImage(item));

  if ((policy === 'show_grid' || (policy === 'show_primary' && imageItems.length > 1)) && imageItems.length > 0) {
    return { mode: 'grid', items: imageItems };
  }

  if (policy === 'show_primary' && primary) {
    return { mode: 'primary', primary };
  }

  if (policy === 'collapsed_candidates' || candidateOnly) {
    if (items.length > 0) {
      return {
        mode: 'collapsed',
        items,
        summary: toolResultSummary(result) ?? `已检索 ${items.length} 个候选结果`
      };
    }
    if (toolResultSummary(result)) {
      return { mode: 'details', result, summary: toolResultSummary(result) ?? '工具结果' };
    }
    return null;
  }

  if (primary && isStandardDisplayableToolResult(result)) {
    return { mode: 'primary', primary };
  }

  if (hasAnyPhotoImage(items) && isStandardDisplayableToolResult(result)) {
    return { mode: 'grid', items };
  }

  return null;
}

function primaryToolImage(result: unknown): any | null {
  const r = asRecord(result);
  const primary = r?.primary_image ?? r?.primaryImage ?? r?.primary;
  const candidate = asRecord(primary)?.result ?? primary;
  if (hasPhotoImage(candidate)) return candidate;
  const items = toolResultItems(result);
  return items.find(item => hasPhotoImage(item)) ?? null;
}

function toolResultItems(result: unknown): any[] {
  const r = asRecord(result);
  const values = [
    r?.display_media,
    r?.displayMedia,
    r?.items,
    r?.photos,
    r?.results,
    r?.candidates
  ];
  for (const value of values) {
    if (Array.isArray(value)) return value;
  }
  return [];
}

function displayMediaItems(result: unknown): any[] {
  const r = asRecord(result);
  const value = r?.display_media ?? r?.displayMedia;
  return Array.isArray(value) ? value : [];
}

function toolResultSummary(result: unknown): string | null {
  const value = asRecord(result)?.summary;
  return typeof value === 'string' && value.trim() ? value.trim() : null;
}

function hasAnyPhotoImage(items: unknown) {
  return Array.isArray(items) && items.some(item => hasPhotoImage(item));
}

function hasPhotoImage(photo: unknown) {
  const sources = photoImageSources(photo);
  return Boolean(sources.big || sources.small);
}

function photoImageSources(photo: unknown) {
  const p = asRecord(photo);
  if (!p) return { big: null, small: null };
  const uploadUrl = isPhotoIndexAsset(p) ? null : uploadAssetUrl(p.asset_id ?? p.assetId);
  const thumbB64 = imageDataUrl(p.preview_b64 ?? p.thumb_b64 ?? p.thumbnail_b64 ?? p.cover_thumb_b64);
  const bigB64 = imageDataUrl(
    p.full_b64 ??
    p.image_b64 ??
    p.vision_b64 ??
    p.image_base64 ??
    p.cover_image_b64 ??
    p.cover_b64
  );
  const bigUrl = firstImageUrl(
    p.full_url,
    p.image_url,
    p.asset_url,
    p.url,
    p.cover_image_url,
    p.cover_url,
    p.imageUrl,
    p.assetUrl,
    uploadUrl,
    p.coverImageUrl,
    p.coverUrl
  );
  const smallUrl = firstImageUrl(
    p.preview_url,
    p.thumb_url,
    p.thumbnail_url,
    p.cover_thumb_url,
    p.cover_thumbnail_url,
    p.thumbUrl,
    p.thumbnailUrl,
    p.coverThumbUrl,
    p.coverThumbnailUrl
  );
  const big = bigUrl ?? bigB64 ?? thumbB64;
  const small = smallUrl ?? thumbB64 ?? big;
  return { big, small };
}

function isPhotoIndexAsset(photo: Record<string, any>) {
  return photo.source === 'photo_index' || photo.match_reason === 'photo_index_embedding';
}

function uploadAssetUrl(value: unknown): string | null {
  if (typeof value !== 'string') return null;
  const clean = value.trim();
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(clean)) {
    return null;
  }
  return `/api/uploads/photos/${clean}`;
}

function formatPhotoDate(value: unknown) {
  const millis = typeof value === 'number' ? value : Number(value);
  if (!Number.isFinite(millis) || millis <= 0) return '';
  const date = new Date(millis);
  if (Number.isNaN(date.getTime())) return '';
  return date.toLocaleString(undefined, {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit'
  });
}

function imageDataUrl(value: unknown) {
  if (typeof value !== 'string') return null;
  const clean = value.trim();
  if (clean.length < 16 || clean.startsWith('<')) return null;
  if (clean.startsWith('data:image/')) return clean;
  return `data:image/jpeg;base64,${clean}`;
}

function firstImageUrl(...values: unknown[]) {
  for (const value of values) {
    const url = imageUrl(value);
    if (url) return url;
  }
  return null;
}

function imageUrl(value: unknown): string | null {
  if (asRecord(value)) return imageUrl(asRecord(value)?.url);
  if (typeof value !== 'string') return null;
  const clean = value.trim();
  if (!clean || clean.startsWith('<')) return null;
  if (clean.startsWith('data:image/')) return clean;
  if (/^(https?:)?\/\//i.test(clean)) return clean;
  if (/^(blob:|\/|\.\/|\.\.\/)/i.test(clean)) return clean;
  return null;
}
