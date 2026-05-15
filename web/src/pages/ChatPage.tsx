import { useEffect, useMemo, useRef, useState } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { api, MessageDto, SessionDto } from '../api/client';
import { streamChat } from '../api/sse';
import { ChatEvent, useChatStore } from '../lib/chatStore';
import { getToken } from '../lib/auth';

export default function ChatPage() {
  const {
    events, setEvents,
    busy, setBusy,
    sessionId, setSessionId,
    input, setInput,
    abortRef, lastSentRef, sentEventsIdxRef, turnStartedAtRef, eventCacheRef,
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
  const [pendingImages, setPendingImages] = useState<PendingImage[]>([]);
  const [composerError, setComposerError] = useState<string | null>(null);
  const [now, setNow] = useState(() => Date.now());
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const selectAllRef = useRef<HTMLInputElement | null>(null);
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
    if (busy || (!force && id === sessionId)) return;
    setMessagesLoading(true);
    setSessionId(id);
    try {
      const rows = await api.listMessages(id);
      const loaded = rows.map(messageToEvent).filter((ev): ev is ChatEvent => ev !== null);
      const cached = eventCacheRef.current[id];
      setEvents(cached && hasRicherToolHistory(cached, loaded) ? cached : loaded);
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
    clearPendingImages();
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
    setSelectedSessionIds(new Set(sessions.map(s => s.id)));
  }

  function invertSelectedSessions() {
    if (busy || bulkDeleting) return;
    setSelectedSessionIds(prev => {
      const next = new Set<string>();
      sessions.forEach(s => {
        if (!prev.has(s.id)) next.add(s.id);
      });
      return next;
    });
  }

  function clearSelectedSessions() {
    if (busy || bulkDeleting) return;
    setSelectedSessionIds(new Set());
  }

  function exitSelectionMode() {
    if (busy || bulkDeleting) return;
    setSelectedSessionIds(new Set());
    setSelectionMode(false);
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

  async function exportSession(id: string) {
    try {
      await api.downloadSessionExport(id);
    } catch (e) {
      setSessionsError(e instanceof Error ? e.message : String(e));
    }
  }

  function clearPendingImages() {
    setPendingImages(prev => {
      prev.forEach(img => URL.revokeObjectURL(img.previewUrl));
      return [];
    });
    setComposerError(null);
  }

  function removePendingImage(id: string) {
    setPendingImages(prev => {
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
    const accepted: PendingImage[] = [];
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
      setPendingImages(prev => [...prev, ...accepted]);
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
    appendCancelEvent(setEvents, sentEventsIdxRef.current);
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
    if (sessionId && !messagesLoading && events.length > 0) {
      eventCacheRef.current[sessionId] = events;
    }
  }, [eventCacheRef, events, messagesLoading, sessionId]);

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
    if (!busy) return;
    setNow(Date.now());
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [busy]);

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
    if ((!message && attachmentsToSend.length === 0) || busy) return;
    const sentAt = new Date().toISOString();
    setInput('');
    setPendingImages([]);
    setComposerError(null);
    lastSentRef.current = message;
    turnStartedAtRef.current = Date.now();
    sentEventsIdxRef.current = -1;
    setBusy(true);
    const ctrl = new AbortController();
    abortRef.current = ctrl;

    let assistantBuf = '';
    let streamSessionId = sessionId;
    let uploadedAttachments: UploadedChatImage[] = [];
    let turnStarted = false;
    try {
      uploadedAttachments = await uploadPendingImages(attachmentsToSend);
      attachmentsToSend.forEach(img => URL.revokeObjectURL(img.previewUrl));
      setEvents(prev => {
        sentEventsIdxRef.current = prev.length;
        return [...prev, {
          type: 'user_message',
          data: { content: message, attachments: uploadedAttachments, createdAt: sentAt }
        }];
      });
      turnStarted = true;
      await streamChat({
        message,
        sessionId: sessionId ?? undefined,
        attachments: uploadedAttachments.map(toChatAttachment),
        signal: ctrl.signal,
        onEvent(name, data) {
          if (name === 'session') {
            if (data?.sessionId) {
              streamSessionId = data.sessionId;
              setSessionId(data.sessionId);
            }
            return;
          }
          if (name === 'user_message') {
            return;
          }
          if (name === 'assistant_message') {
            assistantBuf += data?.content ?? '';
            setEvents(prev => {
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
            setEvents(prev => [...prev, { type: name, data: { ...data, createdAt: new Date().toISOString() } }]);
          }
        },
        onError() { /* stop fetch-event-source retry; UI state resets below */ },
        onClose() {
          markLatestAssistantDuration(setEvents, Date.now() - turnStartedAtRef.current);
          setBusy(false);
        }
      });
      await refreshSessions();
    } catch {
      attachmentsToSend.forEach(img => URL.revokeObjectURL(img.previewUrl));
      if (ctrl.signal.aborted) {
        appendCancelEvent(setEvents, sentEventsIdxRef.current);
        if (!turnStarted) {
          if (lastSentRef.current && !input) setInput(lastSentRef.current);
          if (attachmentsToSend.length > 0) restorePendingImages(attachmentsToSend);
        }
        markLatestAssistantDuration(setEvents, Date.now() - turnStartedAtRef.current);
      } else {
        if (attachmentsToSend.length > 0) restorePendingImages(attachmentsToSend);
        setComposerError('图片上传或发送失败，请重试');
      }
      setBusy(false);
    } finally {
      abortRef.current = null;
      if (streamSessionId) setSessionId(streamSessionId);
    }
  }

  function restorePendingImages(items: PendingImage[]) {
    setPendingImages(items.map(img => ({
      ...img,
      previewUrl: URL.createObjectURL(img.file)
    })));
  }

  const activeSession = sessionId ? sessions.find(s => s.id === sessionId) : null;
  const timelineItems = useMemo(() => buildTimelineItems(events), [events]);

  return (
    <div className="grid grid-cols-1 lg:grid-cols-[320px_minmax(0,1fr)] gap-4 h-[calc(100vh-100px)] min-h-[560px]">
      <aside className="bg-white border rounded flex flex-col min-h-0">
        <div className="p-3 border-b flex items-center justify-between gap-2">
          <div>
            <h1 className="font-semibold leading-tight">会话</h1>
            <div className="text-xs text-slate-500 mt-0.5">
              {selectionMode ? `已选 ${selectedCount} / 共 ${sessionCount}` : `${sessionCount} 条历史`}
            </div>
          </div>
          <div className="flex items-center gap-1">
            {sessionCount > 0 && (
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
                {selectionMode ? '退出' : '多选'}
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
          <div className="px-3 py-2 border-b bg-slate-50 space-y-2">
            <div className="flex items-center justify-between gap-2">
              <label className="inline-flex min-w-0 items-center gap-2 text-xs font-medium text-slate-700">
                <input
                  ref={selectAllRef}
                  type="checkbox"
                  checked={allSessionsSelected}
                  onChange={e => e.currentTarget.checked ? selectAllSessions() : clearSelectedSessions()}
                  disabled={busy || bulkDeleting || sessionCount === 0}
                  className="h-4 w-4 rounded border-slate-300 text-blue-600 focus:ring-blue-500 disabled:opacity-50"
                  aria-label="选择全部会话"
                />
                <span className="truncate">已选 {selectedCount} / {sessionCount}</span>
              </label>
              <button
                type="button"
                onClick={exitSelectionMode}
                disabled={busy || bulkDeleting}
                className="text-xs text-slate-500 hover:text-slate-900 disabled:opacity-50"
              >
                退出
              </button>
            </div>
            <div className="grid grid-cols-3 gap-1.5">
              <button
                type="button"
                onClick={selectAllSessions}
                disabled={busy || bulkDeleting || sessionCount === 0 || allSessionsSelected}
                className="px-2 py-1 rounded border border-slate-200 bg-white text-xs text-slate-700 hover:bg-slate-100 disabled:opacity-50"
              >
                全选
              </button>
              <button
                type="button"
                onClick={invertSelectedSessions}
                disabled={busy || bulkDeleting || sessionCount === 0}
                className="px-2 py-1 rounded border border-slate-200 bg-white text-xs text-slate-700 hover:bg-slate-100 disabled:opacity-50"
              >
                反选
              </button>
              <button
                type="button"
                onClick={clearSelectedSessions}
                disabled={busy || bulkDeleting || selectedCount === 0}
                className="px-2 py-1 rounded border border-slate-200 bg-white text-xs text-slate-700 hover:bg-slate-100 disabled:opacity-50"
              >
                清空
              </button>
            </div>
            <button
              type="button"
              onClick={() => void deleteSelectedSessions()}
              disabled={busy || bulkDeleting || selectedCount === 0}
              className="w-full px-2.5 py-1.5 rounded bg-red-600 hover:bg-red-700 disabled:opacity-50 text-white text-xs"
            >
              {bulkDeleting ? '删除中...' : `删除已选 ${selectedCount}`}
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
                  onClick={() => void exportSession(s.id)}
                  disabled={busy || selectionMode || bulkDeleting}
                  className={[
                    'px-2 text-xs opacity-0 group-hover:opacity-100 focus:opacity-100 disabled:opacity-40',
                    active ? 'text-slate-300 hover:text-white' : 'text-slate-400 hover:text-slate-700'
                  ].join(' ')}
                  title="导出 JSONL"
                >
                  导出
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
          {timelineItems.map(item => {
            if (item.kind === 'event') {
              return (
                <Bubble
                  key={`${item.event.type}-${item.index}`}
                  ev={item.event}
                  nextToolResult={matchingToolResult(events, item.index)}
                  onOpenImage={setLightboxSrc}
                />
              );
            }
            if (item.kind === 'media_result') {
              return (
                <VisibleToolResult
                  key={`media-${item.event.data?.tool}-${item.index}`}
                  ev={item.event}
                  onOpenImage={setLightboxSrc}
                />
              );
            }
            return (
              <ProcessPanel
                key={`process-${item.startIndex}`}
                item={item}
                busy={busy && item.endIndex >= events.length - 1}
                now={now}
                onOpenImage={setLightboxSrc}
              />
            );
          })}
          {busy && <ThinkingRow startedAt={turnStartedAtRef.current} now={now} />}
        </div>

        <form
          onSubmit={ev => { ev.preventDefault(); void send(); }}
          onDrop={handleDrop}
          onDragOver={handleDragOver}
          className="border-t p-3 space-y-2"
        >
          {(pendingImages.length > 0 || composerError) && (
            <div className="space-y-2">
              {pendingImages.length > 0 && (
                <PendingImageGrid
                  items={pendingImages}
                  disabled={busy || messagesLoading}
                  onRemove={removePendingImage}
                />
              )}
              {composerError && <div className="text-xs text-red-600">{composerError}</div>}
            </div>
          )}
          <div className="flex gap-2 items-end">
            <input
              ref={fileInputRef}
              type="file"
              accept="image/jpeg,image/png,image/webp"
              multiple
              className="hidden"
              onChange={handleFileChange}
              disabled={busy || messagesLoading}
            />
            <button
              type="button"
              onClick={() => fileInputRef.current?.click()}
              disabled={busy || messagesLoading || pendingImages.length >= MAX_ATTACHMENTS}
              className="h-[42px] w-[42px] shrink-0 rounded border border-slate-200 bg-white text-slate-600 hover:bg-slate-50 disabled:opacity-50"
              title="添加图片"
              aria-label="添加图片"
            >
              +
            </button>
            <textarea
              value={input}
              onChange={e => setInput(e.target.value)}
              onPaste={handlePaste}
              onKeyDown={e => {
                if (e.key === 'Enter' && !e.shiftKey) {
                  e.preventDefault();
                  void send();
                }
              }}
              rows={1}
              placeholder={busy ? '生成中... 按 Esc 或点"停止"中断' : '输入消息或粘贴图片...'}
              className="flex-1 min-h-[42px] max-h-32 resize-none border rounded px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500"
              disabled={busy || messagesLoading}
            />
          <button
            type="submit"
            disabled={(!input.trim() && pendingImages.length === 0) || busy || messagesLoading}
            className="bg-blue-600 hover:bg-blue-700 disabled:opacity-50 text-white px-4 py-2 rounded h-[42px]"
          >
            发送
          </button>
          </div>
        </form>
      </section>

      {lightboxSrc && (
        <div
          className="fixed inset-0 bg-black/85 z-50"
          onClick={() => setLightboxSrc(null)}
        >
          <div className="absolute right-3 top-3 flex items-center gap-2" onClick={e => e.stopPropagation()}>
            <button
              type="button"
              onClick={() => void downloadImage(lightboxSrc)}
              className="rounded bg-white/95 px-3 py-2 text-sm font-medium text-slate-800 shadow hover:bg-white"
            >
              保存图片
            </button>
            <button
              type="button"
              onClick={() => setLightboxSrc(null)}
              className="h-9 w-9 rounded bg-white/95 text-xl leading-9 text-slate-700 shadow hover:bg-white"
              aria-label="关闭预览"
              title="关闭"
            >
              x
            </button>
          </div>
          <div
            className="flex h-full w-full items-center justify-center p-4 pt-16"
            onClick={e => e.stopPropagation()}
            onContextMenu={e => e.stopPropagation()}
          >
            <AuthImage
              src={lightboxSrc}
              className="max-w-[95vw] max-h-[85vh] object-contain"
              alt="预览"
            />
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

interface PendingImage {
  id: string;
  file: File;
  previewUrl: string;
  width?: number;
  height?: number;
}

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
    items.push({
      kind: 'event',
      event: segment[finalAssistantOffset],
      index: segmentStart + finalAssistantOffset
    });
    pushVisibleToolResults(items, processEvents, segmentStart);

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

async function downloadImage(src: string) {
  const filename = imageDownloadName(src);
  try {
    const token = getToken();
    const resp = await fetch(src, {
      headers: needsAuthenticatedFetch(src) && token ? { Authorization: `Bearer ${token}` } : {}
    });
    if (!resp.ok) throw new Error(`HTTP ${resp.status}`);
    const blob = await resp.blob();
    const url = URL.createObjectURL(blob);
    triggerDownload(url, filename);
    window.setTimeout(() => URL.revokeObjectURL(url), 30_000);
  } catch {
    triggerDownload(src, filename);
  }
}

function triggerDownload(href: string, filename: string) {
  const link = document.createElement('a');
  link.href = href;
  link.download = filename;
  link.rel = 'noopener';
  document.body.appendChild(link);
  link.click();
  link.remove();
}

function imageDownloadName(src: string) {
  if (src.startsWith('data:image/png')) return `agent-image-${Date.now()}.png`;
  if (src.startsWith('data:image/webp')) return `agent-image-${Date.now()}.webp`;
  const clean = src.split('?')[0].split('#')[0];
  const last = clean.split('/').filter(Boolean).pop();
  if (last && /\.[a-z0-9]{2,5}$/i.test(last)) return last;
  return `agent-image-${Date.now()}.jpg`;
}

function markLatestAssistantDuration(
  setEvents: React.Dispatch<React.SetStateAction<ChatEvent[]>>,
  durationMs: number
) {
  if (!Number.isFinite(durationMs) || durationMs < 0) return;
  setEvents(prev => {
    const copy = [...prev];
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
    return prev;
  });
}

function appendCancelEvent(
  setEvents: React.Dispatch<React.SetStateAction<ChatEvent[]>>,
  sentEventsIndex: number
) {
  setEvents(prev => {
    if (sentEventsIndex < 0 || prev.length <= sentEventsIndex) return prev;
    const last = prev[prev.length - 1];
    if (last?.type === 'error' && last.data?.code === 'client_cancelled') return prev;
    return [
      ...prev,
      {
        type: 'error',
        data: {
          code: 'client_cancelled',
          message: '已取消本次执行',
          createdAt: new Date().toISOString()
        }
      }
    ];
  });
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
    <div className="text-xs text-slate-500 italic flex items-center gap-2">
      <span className={`inline-block w-2 h-2 rounded-full ${dotClass}`} />
      {label} <code className="bg-slate-100 px-1 rounded">{ev.data?.tool}</code>
      {ev.data?.args && <span className="truncate">({JSON.stringify(ev.data.args)})</span>}
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
        <div key={item.id} className="relative h-20 w-20 overflow-hidden rounded border border-slate-200 bg-slate-100">
          <img src={item.previewUrl} alt={item.file.name || '待发送图片'} className="h-full w-full object-cover" />
          <button
            type="button"
            onClick={() => onRemove(item.id)}
            disabled={disabled}
            className="absolute right-1 top-1 h-6 w-6 rounded-full bg-black/65 text-sm leading-6 text-white disabled:opacity-50"
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
    <div className="grid max-w-xs grid-cols-2 gap-2 justify-end">
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
            className="h-32 w-32 rounded border object-cover cursor-zoom-in hover:ring-2 hover:ring-blue-400 transition"
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
    <details className="group/process rounded-md border border-slate-200 bg-white open:bg-slate-50/60">
      <summary className="flex cursor-pointer list-none items-start gap-2 px-3 py-2 text-xs text-slate-500 marker:hidden">
        <span className={`mt-1 h-2 w-2 shrink-0 rounded-full ${dotClass}`} />
        <span className="grid min-w-0 flex-1 gap-1">
          <span className="flex min-w-0 flex-wrap items-center gap-x-2 gap-y-1">
            <span className="font-medium text-slate-600">{statusText}</span>
            {duration && <span>{duration}</span>}
            <span>{progress ? `${progress.index}/${progress.max}` : toolCalls.length} 次调用</span>
            {toolResults.length > 0 && <span>{toolResults.length} 个结果</span>}
            {visibleNames.map(name => (
              <code key={name} className="rounded bg-slate-100 px-1 py-0.5 text-[11px] text-slate-600">
                {name}
              </code>
            ))}
            {overflowNames > 0 && <span>+{overflowNames}</span>}
          </span>
          {summary && <span className="min-w-0 truncate text-slate-500">{summary}</span>}
        </span>
        <span className="mt-0.5 shrink-0 text-slate-400 transition group-open/process:rotate-90">›</span>
      </summary>
      <div className="border-t border-slate-100 px-3 py-2">
        <div className="space-y-2">
          {item.events.map((ev, index) => ev.type === 'tool_call_started' ? (
            <ToolCallDetail
              key={`call-${item.startIndex}-${index}`}
              ev={ev}
              resultEvent={findResultForToolCall(item.events, index)}
            />
          ) : ev.type === 'tool_call_result' ? (
            <div key={`result-${item.startIndex}-${index}`} className="rounded-md border border-slate-200 bg-white p-2">
              <div className="mb-2 flex items-center gap-2 text-xs font-medium text-slate-600">
                <span className="text-slate-400">工具</span>
                <code className="rounded bg-slate-100 px-1.5 py-0.5">{ev.data?.tool}</code>
                <span>返回结果</span>
              </div>
              <ToolResult tool={ev.data?.tool} result={ev.data?.result} onOpenImage={onOpenImage} />
            </div>
          ) : ev.type === 'assistant_message' ? (
            <div key={`note-${item.startIndex}-${index}`} className="rounded-md border border-slate-200 bg-white px-3 py-2 text-sm text-slate-700">
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
        <code className="rounded bg-slate-100 px-1.5 py-0.5 text-slate-700">{ev.data?.tool}</code>
        {args && <span className="min-w-0 truncate text-slate-400">({JSON.stringify(args)})</span>}
      </div>
    </div>
  );
}

function VisibleToolResult({ ev, onOpenImage }: { ev: ChatEvent; onOpenImage: OpenImage }) {
  const tool = String(ev.data?.tool ?? 'tool');
  return (
    <div className="flex justify-start">
      <div className="w-full max-w-3xl rounded-lg border border-slate-200 bg-white p-3 shadow-sm">
        <div className="mb-2 flex items-center gap-2 text-xs text-slate-500">
          <span className="h-2 w-2 rounded-full bg-blue-500" />
          <span className="font-medium text-slate-600">{toolResultTitle(tool)}</span>
          <code className="rounded bg-slate-100 px-1.5 py-0.5 text-[11px] text-slate-600">{tool}</code>
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
          <div className="max-w-prose">
            <div className="space-y-2">
              {attachments.length > 0 && (
                <MessageAttachmentGrid
                  items={attachments}
                  onOpenImage={onOpenImage}
                />
              )}
              {ev.data?.content && (
                <div className="bg-blue-600 text-white px-3 py-2 rounded-lg whitespace-pre-wrap">
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
          <div className="max-w-prose">
            <div className="bg-slate-100 px-3 py-2 rounded-lg">
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
        <div className="text-sm text-red-600 bg-red-50 border border-red-200 rounded px-3 py-2">
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
      <div className="inline-flex items-center gap-2 rounded-full bg-slate-100 px-3 py-2 text-xs text-slate-500">
        <span className="flex items-center gap-1" aria-label="回复中">
          <span className="h-1.5 w-1.5 rounded-full bg-slate-400 animate-bounce [animation-delay:-0.2s]" />
          <span className="h-1.5 w-1.5 rounded-full bg-slate-400 animate-bounce [animation-delay:-0.1s]" />
          <span className="h-1.5 w-1.5 rounded-full bg-slate-400 animate-bounce" />
        </span>
        <span>回复中 · {elapsedSince(startedAt, now)}</span>
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
          const { big, small: src } = photoImageSources(a);
          return (
            <div key={a.bucket_id} className="border rounded overflow-hidden">
              {src ? (
                <AuthImage src={src} alt={a.name}
                           onOpen={() => onOpenImage(big ?? src)}
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
    if (result.videos.length === 0) {
      return (
        <div className="rounded-md border border-dashed border-slate-200 bg-slate-50 px-3 py-4 text-sm text-slate-500">
          没查到视频。可能是手机里最近没有视频，或还没授予“读取视频”权限。
        </div>
      );
    }
    return (
      <div className="grid grid-cols-3 sm:grid-cols-5 gap-2">
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
        <pre className="mt-1 bg-slate-100 p-2 rounded overflow-x-auto">{JSON.stringify(media.result, null, 2)}</pre>
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
                 className="w-full max-h-96 object-contain rounded border cursor-zoom-in hover:ring-2 hover:ring-blue-400 transition" />
      <div className="text-xs text-slate-500 mt-1">
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
                   className="w-full max-h-96 object-contain rounded border cursor-zoom-in hover:ring-2 hover:ring-blue-400 transition" />
        <div className="text-xs text-slate-500 mt-1">
          {primary.name}{w && h ? ` 路 ${w}脳${h}` : ''}
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
    <div className="grid grid-cols-3 sm:grid-cols-5 gap-2">
      {items.map((p: any) => {
        const { big, small: src } = photoImageSources(p);
        if (!src) {
          return <PhotoPlaceholder key={p.id} photo={p} />;
        }
        return (
          <AuthImage key={p.id} src={src} alt={p.name}
                     title={p.name}
                     onOpen={() => onOpenImage(big ?? src)}
                     className="w-full h-32 object-cover rounded border cursor-zoom-in hover:ring-2 hover:ring-blue-400 transition" />
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
             className="rounded border border-slate-200 bg-slate-50 px-3 py-2 text-xs text-slate-600">
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
    <div className="w-full h-32 bg-slate-100 border border-slate-200 rounded text-xs text-slate-500 flex items-center justify-center px-2 text-center">
      {photo?.name ?? photo?.id ?? '图片已命中'}
    </div>
  );
}

function AuthImage({
  src,
  alt,
  title,
  className,
  onOpen
}: {
  src: string | null;
  alt?: string;
  title?: string;
  className?: string;
  onOpen?: () => void;
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
    return <div className="w-full h-32 rounded border border-slate-200 bg-slate-100" />;
  }

  return (
    <img src={displaySrc}
         alt={alt}
         title={title}
         onClick={onOpen}
         className={className} />
  );
}

function needsAuthenticatedFetch(src: string) {
  return src.startsWith('/api/uploads/');
}

function semanticPrimaryPhoto(result: any) {
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

  if (policy === 'show_primary' && primary) {
    return { mode: 'primary', primary };
  }

  if (policy === 'show_grid' && hasAnyPhotoImage(items)) {
    return { mode: 'grid', items };
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
  const uploadUrl = uploadAssetUrl(p.asset_id ?? p.assetId);
  const bigUrl = firstImageUrl(
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
    p.thumb_url,
    p.thumbnail_url,
    p.cover_thumb_url,
    p.cover_thumbnail_url,
    p.thumbUrl,
    p.thumbnailUrl,
    p.coverThumbUrl,
    p.coverThumbnailUrl
  );
  const bigB64 = imageDataUrl(
    p.image_b64 ??
    p.vision_b64 ??
    p.image_base64 ??
    p.cover_image_b64 ??
    p.cover_b64 ??
    p.full_b64
  );
  const big = bigUrl ?? bigB64;
  const small = smallUrl ?? big ?? imageDataUrl(p.thumb_b64 ?? p.thumbnail_b64 ?? p.cover_thumb_b64);
  return { big, small };
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
