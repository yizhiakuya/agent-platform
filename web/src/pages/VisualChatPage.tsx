import { useEffect, useMemo, useRef, useState } from 'react';
import type { ClipboardEvent, DragEvent, FormEvent, KeyboardEvent, WheelEvent } from 'react';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { QRCodeSVG } from 'qrcode.react';
import {
  ArrowRight,
  Battery,
  CheckCircle2,
  ChevronRight,
  Clock,
  Download,
  History,
  Image as ImageIcon,
  Loader2,
  LogIn,
  Maximize2,
  MessageSquarePlus,
  Paperclip,
  Plus,
  RefreshCw,
  Save,
  Shield,
  SlidersHorizontal,
  Sparkles,
  Square,
  Smartphone,
  Terminal,
  Trash2,
  Wifi,
  X
} from 'lucide-react';
import { api, ApiError, type DeviceDto, type DeviceOnlineStatusDto, type EnrollmentResponse, type MemoryFactDto, type MessageDto, type SessionDto } from '../api/client';
import { streamChat } from '../api/sse';
import { getToken } from '../lib/auth';
import { ChatEvent, ChatRunState, NEW_SESSION_KEY, PendingDraftImage, QueuedChatTurn, useChatStore } from '../lib/chatStore';

type Overlay = 'sessions' | 'devices' | 'settings' | null;
type OpenImage = (src: string) => void;

type DeviceRow = DeviceDto & {
  online: boolean;
  connectedAt?: string;
  lastSeenAt?: string;
  toolCount: number;
};

type ToolStepStatus = 'success' | 'running' | 'pending' | 'failed';

type ToolStep = {
  time: string;
  text: string;
  status: ToolStepStatus;
  isCode?: boolean;
};

type TimelineItem =
  | { kind: 'event'; event: ChatEvent; index: number }
  | { kind: 'process'; events: ChatEvent[]; startIndex: number; endIndex: number }
  | { kind: 'media_result'; event: ChatEvent; index: number };

type SessionPreviewState =
  | { status: 'loading' }
  | { status: 'ready'; events: ChatEvent[] }
  | { status: 'error'; message: string };

type SessionPreviewEntry = {
  key: string;
  label: string;
  content: string;
  meta: string;
  tone: 'user' | 'assistant' | 'tool' | 'error';
};

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

type StandardToolMedia =
  | { mode: 'primary'; primary: any }
  | { mode: 'grid'; items: any[]; page?: ToolResultPageInfo | null }
  | { mode: 'collapsed'; items: any[]; summary: string }
  | { mode: 'details'; result: any; summary: string };

interface ToolResultPageInfo {
  start?: number;
  end?: number;
  count: number;
  limit?: number;
  offset?: number;
  hasMore: boolean;
  nextOffset?: number;
}

const MAX_ATTACHMENTS = 4;
const MAX_IMAGE_BYTES = 10 * 1024 * 1024;
const SUPPORTED_IMAGE_TYPES = new Set(['image/jpeg', 'image/png', 'image/webp']);

const MOCK_MESSAGES: ChatEvent[] = [
  {
    type: 'user_message',
    data: { content: '帮我找一下昨天在小红书保存的装修图片。', createdAt: new Date().toISOString() }
  },
  {
    type: 'tool_call_started',
    data: {
      tool: 'photos.semantic_search',
      args: { query: '昨天 小红书 装修 室内设计', limit: 6 },
      createdAt: new Date().toISOString()
    }
  },
  {
    type: 'assistant_message',
    data: { content: '正在连接设备图库，按时间和语义一起筛选。', createdAt: new Date().toISOString() }
  },
  {
    type: 'tool_call_result',
    data: {
      tool: 'photos.semantic_search',
      createdAt: new Date().toISOString(),
      result: {
        result_type: 'results',
        display_policy: 'show_grid',
        photos: [
          { id: 'demo-1', name: 'wood-living-room.jpg', image_url: 'https://images.unsplash.com/photo-1600596542815-ffad4c1539a9?w=900&q=80' },
          { id: 'demo-2', name: 'soft-apartment.jpg', image_url: 'https://images.unsplash.com/photo-1600607687939-ce8a6c25118c?w=900&q=80' },
          { id: 'demo-3', name: 'calm-kitchen.jpg', image_url: 'https://images.unsplash.com/photo-1600210492493-0946911123ea?w=900&q=80' },
          { id: 'demo-4', name: 'sunlit-home.jpg', image_url: 'https://images.unsplash.com/photo-1600585154340-be6161a56a0c?w=900&q=80' },
          { id: 'demo-5', name: 'interior-detail.jpg', image_url: 'https://images.unsplash.com/photo-1600566753190-17f0baa2a6c3?w=900&q=80' },
          { id: 'demo-6', name: 'neutral-room.jpg', image_url: 'https://images.unsplash.com/photo-1618221195710-dd6b41faaea6?w=900&q=80' }
        ]
      }
    }
  },
  {
    type: 'assistant_message',
    data: {
      content: '找到了。我从设备结果里整理出 6 张高度相关的装修图片，已经放在右侧画布里。',
      createdAt: new Date().toISOString(),
      durationMs: 1260
    }
  }
];

export default function VisualChatPage() {
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
  const [isAuthenticated] = useState(() => Boolean(getToken()));
  const input = draftByKey[activeDraftKey] ?? '';
  const pendingImages = pendingImagesByKey[activeDraftKey] ?? [];
  const queuedTurns = queuedTurnsByKey[activeDraftKey] ?? [];
  const activeRun = runsByKey[activeDraftKey];
  const busy = Boolean(activeRun);
  const anyBusy = Object.keys(runsByKey).length > 0;
  const [sessions, setSessions] = useState<SessionDto[]>([]);
  const [sessionsLoading, setSessionsLoading] = useState(isAuthenticated);
  const [sessionsError, setSessionsError] = useState<string | null>(null);
  const [messagesLoading, setMessagesLoading] = useState(false);
  const [deleteBusyId, setDeleteBusyId] = useState<string | null>(null);
  const [selectionMode, setSelectionMode] = useState(false);
  const [selectedSessionIds, setSelectedSessionIds] = useState<Set<string>>(() => new Set());
  const [bulkDeleting, setBulkDeleting] = useState(false);
  const [deviceRows, setDeviceRows] = useState<DeviceRow[]>([]);
  const [devicesLoading, setDevicesLoading] = useState(isAuthenticated);
  const [devicesError, setDevicesError] = useState<string | null>(null);
  const [overlay, setOverlay] = useState<Overlay>(null);
  const [showCanvas, setShowCanvas] = useState(false);
  const [lightboxSrc, setLightboxSrc] = useState<string | null>(null);
  const [lightboxScale, setLightboxScale] = useState(1);
  const [composerError, setComposerError] = useState<string | null>(null);
  const [now, setNow] = useState(() => Date.now());
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const fileInputRef = useRef<HTMLInputElement | null>(null);
  const activeDraftKeyRef = useRef(activeDraftKey);
  const queuedTurnsRef = useRef(queuedTurnsByKey);

  const renderedEvents = isAuthenticated ? events : MOCK_MESSAGES;
  const timelineItems = useMemo(() => buildTimelineItems(renderedEvents, busy), [renderedEvents, busy]);
  const latestMediaEvent = useMemo(() => {
    for (let i = renderedEvents.length - 1; i >= 0; i -= 1) {
      const ev = renderedEvents[i];
      if (ev.type === 'tool_call_result' && isRenderableToolResult(ev)) return ev;
    }
    return null;
  }, [renderedEvents]);
  const activeSession = sessionId ? sessions.find(s => s.id === sessionId) ?? null : null;
  const activeDevice = deviceRows.find(d => d.online) ?? deviceRows[0] ?? null;
  const toolCallCount = renderedEvents.filter(ev => ev.type === 'tool_call_started').length;
  const toolResultCount = renderedEvents.filter(ev => ev.type === 'tool_call_result').length;
  const messageCount = renderedEvents.filter(ev => ev.type === 'user_message' || ev.type === 'assistant_message').length;
  const sessionCount = sessions.length;

  async function refreshSessions() {
    if (!isAuthenticated) {
      setSessions([]);
      setSessionsLoading(false);
      return;
    }
    setSessionsError(null);
    setSessionsLoading(true);
    try {
      const data = await api.listSessions();
      setSessions(sortSessions(data));
    } catch (e) {
      setSessionsError(e instanceof Error ? e.message : String(e));
    } finally {
      setSessionsLoading(false);
    }
  }

  async function refreshDevices() {
    if (!isAuthenticated) {
      setDeviceRows([]);
      setDevicesLoading(false);
      return;
    }
    setDevicesLoading(true);
    setDevicesError(null);
    try {
      const [devices, online] = await Promise.all([api.listDevices(), api.listDeviceOnlineStatus()]);
      setDeviceRows(mergeDeviceRows(devices, online));
    } catch (e) {
      setDevicesError(e instanceof Error ? e.message : String(e));
    } finally {
      setDevicesLoading(false);
    }
  }

  async function loadSession(id: string, force = false) {
    if (!isAuthenticated) return goLogin();
    if (!force && id === sessionId) return;
    setMessagesLoading(true);
    setEvents(eventsByKey[id] ?? eventCacheRef.current[id] ?? []);
    setSessionId(id);
    setOverlay(null);
    try {
      const rows = await api.listMessages(id);
      const loaded = rows.map(messageToEvent).filter((ev): ev is ChatEvent => ev !== null);
      const cached = eventsByKey[id] ?? eventCacheRef.current[id];
      const nextEvents = preferredSessionEvents(cached, loaded);
      eventCacheRef.current[id] = nextEvents;
      setEventsForKey(id, nextEvents);
      setEvents(nextEvents);
    } catch (e) {
      setEvents([{ type: 'error', data: { message: e instanceof Error ? e.message : String(e), createdAt: new Date().toISOString() } }]);
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
    setOverlay(null);
  }

  async function deleteSession(id: string) {
    if (!isAuthenticated || runsByKey[id] || deleteBusyId) return;
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
    if (!isAuthenticated || bulkDeleting || selectedSessionIds.size === 0) return;
    const ids = Array.from(selectedSessionIds);
    if (ids.some(id => runsByKey[id])) {
      setSessionsError('有会话仍在处理中，请先停止后再删除。');
      return;
    }
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

  function toggleSessionSelected(id: string) {
    if (bulkDeleting) return;
    setSelectedSessionIds(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  }

  function toggleSelectionMode() {
    if (bulkDeleting) return;
    setSelectionMode(prev => {
      const next = !prev;
      if (!next) setSelectedSessionIds(new Set());
      return next;
    });
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

  function handlePaste(e: ClipboardEvent<HTMLTextAreaElement>) {
    const files = Array.from(e.clipboardData.files).filter(file => file.type.startsWith('image/'));
    if (files.length === 0) return;
    e.preventDefault();
    void addImageFiles(files);
  }

  function handleDrop(e: DragEvent<HTMLFormElement>) {
    const files = Array.from(e.dataTransfer.files).filter(file => file.type.startsWith('image/'));
    if (files.length === 0) return;
    e.preventDefault();
    void addImageFiles(files);
  }

  function handleDragOver(e: DragEvent<HTMLFormElement>) {
    if (Array.from(e.dataTransfer.items).some(item => item.type.startsWith('image/'))) {
      e.preventDefault();
    }
  }

  function handleStop() {
    if (!activeRun) return;
    void api.cancelChatRun(activeRun.runId).catch(() => undefined);
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

  async function syncSessionMessages(id: string, key: string) {
    const rows = await api.listMessages(id);
    const loaded = rows.map(messageToEvent).filter((ev): ev is ChatEvent => ev !== null);
    const cached = eventCacheRef.current[key] ?? eventsByKey[key];
    const nextEvents = preferredSessionEvents(cached, loaded);
    setEventsForDraftKey(key, nextEvents);
    return loaded;
  }

  async function recoverSessionMessages(id: string, key: string) {
    const expectedUsers = countEventsOfType(eventCacheRef.current[key] ?? eventsByKey[key] ?? [], 'user_message');
    let loaded: ChatEvent[] = [];
    for (let attempt = 0; attempt < 7; attempt += 1) {
      if (attempt > 0) await delay(1200);
      loaded = await syncSessionMessages(id, key);
      if (countEventsOfType(loaded, 'user_message') >= expectedUsers && latestTurnIsComplete(loaded)) {
        return true;
      }
    }
    return false;
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

  async function send() {
    if (!isAuthenticated) return goLogin();
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
          if (name === 'user_message') return;
          if (name === 'assistant_message') {
            const chunk = data?.content ?? '';
            if (!chunk) return;
            setEventsForDraftKey(targetKey, prev => {
              const copy = [...prev];
              const last = copy[copy.length - 1];
              const createdAt = last?.type === 'assistant_message'
                ? last.data?.createdAt
                : new Date().toISOString();
              if (last?.type === 'assistant_message') {
                copy[copy.length - 1] = {
                  type: 'assistant_message',
                  data: { ...last.data, content: `${last.data?.content ?? ''}${chunk}`, createdAt }
                };
              } else {
                copy.push({ type: 'assistant_message', data: { content: chunk, createdAt } });
              }
              return copy;
            });
          } else {
            setEventsForDraftKey(targetKey, prev => [
              ...prev,
              { type: name, data: { ...data, createdAt: new Date().toISOString() } }
            ]);
          }
        },
        onError() { /* fetch-event-source retry is disabled in the SSE client. */ },
        onClose() {
          setEventsForDraftKey(targetKey, prev => markLatestAssistantDuration(prev, Date.now() - startedAt));
          setRunsByKey(prev => {
            const next = { ...prev };
            if (next[targetKey]?.runId === runId) delete next[targetKey];
            return next;
          });
        }
      });
      if (targetSessionId) {
        await syncSessionMessages(targetSessionId, targetKey).catch(() => undefined);
      }
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
        const recovered = turnStarted && targetSessionId
          ? await recoverSessionMessages(targetSessionId, targetKey).catch(() => false)
          : false;
        if (!recovered) {
          if (attachmentsToSend.length > 0) restorePendingImages(originalKey, attachmentsToSend);
          setComposerError('图片上传或发送失败，或连接中断后暂未补全，请稍后刷新重试。');
        }
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

  function handleLightboxWheel(e: WheelEvent<HTMLDivElement>) {
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
    void refreshDevices();
    if (isAuthenticated && sessionId) void loadSession(sessionId, true);
    if (!isAuthenticated) {
      const timer = window.setTimeout(() => setShowCanvas(true), 1100);
      return () => window.clearTimeout(timer);
    }
    return undefined;
    // Load stored app state once; later switches are explicit user actions.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    const cached = eventsByKey[activeDraftKey] ?? eventCacheRef.current[activeDraftKey];
    if (cached && cached !== events) setEvents(cached);
    // Keep visible events aligned when a background stream updates the active session.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeDraftKey, eventsByKey]);

  useEffect(() => {
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: 'smooth' });
  }, [timelineItems, activeRun]);

  useEffect(() => {
    if (sessionId && !messagesLoading && events.length > 0) {
      eventCacheRef.current[sessionId] = events;
      setEventsForKey(sessionId, events);
    }
  }, [eventCacheRef, events, messagesLoading, sessionId, setEventsForKey]);

  useEffect(() => {
    if (!anyBusy) return;
    setNow(Date.now());
    const timer = window.setInterval(() => setNow(Date.now()), 1000);
    return () => window.clearInterval(timer);
  }, [anyBusy]);

  useEffect(() => {
    if (latestMediaEvent) setShowCanvas(true);
  }, [latestMediaEvent]);

  useEffect(() => {
    function onKey(e: globalThis.KeyboardEvent) {
      if (e.key === 'Escape' && busy) {
        e.preventDefault();
        handleStop();
      }
    }
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [busy, input]);

  return (
    <div className="spatial-canvas-ui relative h-screen w-full overflow-hidden font-sans">
      <div className="ambient-bg" />

      <TopHUD
        activeDevice={activeDevice}
        activeSession={activeSession}
        authenticated={isAuthenticated}
        busy={busy}
        messageCount={messageCount}
        sessionsCount={sessionCount}
        setOverlay={setOverlay}
        startNewSession={startNewSession}
      />

      <div
        ref={scrollRef}
        className={`relative z-10 h-full w-full overflow-y-auto px-10 pb-40 pt-32 transition-all duration-700 ease-[cubic-bezier(0.2,0.8,0.2,1)] max-sm:px-5 ${
          showCanvas ? 'max-w-[100%] pr-[50%] max-xl:pr-10 max-sm:pr-5' : 'mx-auto max-w-4xl'
        }`}
      >
        <div className="space-y-6">
          <SessionHeader
            activeDevice={activeDevice}
            activeSession={activeSession}
            authenticated={isAuthenticated}
            eventsCount={renderedEvents.length}
            messagesLoading={messagesLoading}
            toolCallCount={toolCallCount}
            toolResultCount={toolResultCount}
          />

          {isAuthenticated && renderedEvents.length === 0 && !messagesLoading && (
            <EmptySession onAttach={() => fileInputRef.current?.click()} onOpenDevices={() => setOverlay('devices')} />
          )}

          {timelineItems.map((item, index) => {
            if (item.kind === 'event') {
              return (
                <VisualBubble
                  key={`${item.event.type}-${item.index}`}
                  event={item.event}
                  index={index}
                  nextToolResult={matchingToolResult(renderedEvents, item.index)}
                  onOpenImage={openLightbox}
                />
              );
            }
            if (item.kind === 'media_result') {
              return (
                <InlineMediaResult
                  key={`media-${item.event.data?.tool}-${item.index}`}
                  event={item.event}
                  onOpenCanvas={() => setShowCanvas(true)}
                />
              );
            }
            return (
              <VisualToolProcessAccordion
                key={`process-${item.startIndex}`}
                item={item}
                busy={busy && item.endIndex >= renderedEvents.length - 1}
                now={now}
              />
            );
          })}

          {activeRun && <ThinkingRow startedAt={activeRun.startedAt} now={now} />}
          {queuedTurns.length > 0 && <QueuedTurnsNotice count={queuedTurns.length} />}
        </div>

        <div className="hidden max-xl:block">
          <VisualCanvas
            event={latestMediaEvent}
            isVisible={showCanvas}
            onClose={() => setShowCanvas(false)}
            onOpenImage={openLightbox}
          />
        </div>
      </div>

      <div className="max-xl:hidden">
        <VisualCanvas
          event={latestMediaEvent}
          isVisible={showCanvas}
          onClose={() => setShowCanvas(false)}
          onOpenImage={openLightbox}
        />
      </div>

      <OmniBar
        authenticated={isAuthenticated}
        busy={busy}
        composerError={composerError}
        fileInputRef={fileInputRef}
        input={input}
        messagesLoading={messagesLoading}
        onDragOver={handleDragOver}
        onDrop={handleDrop}
        onFileChange={handleFileChange}
        onOpenSessions={() => setOverlay('sessions')}
        onPaste={handlePaste}
        onRemoveImage={removePendingImage}
        onSend={() => void send()}
        onStop={handleStop}
        pendingImages={pendingImages}
        setInput={value => setDraftForKey(activeDraftKey, value)}
      />

      <OverlayManager
        authenticated={isAuthenticated}
        bulkDeleting={bulkDeleting}
        deleteBusyId={deleteBusyId}
        deleteSelectedSessions={() => void deleteSelectedSessions()}
        deleteSession={id => void deleteSession(id)}
        deviceRows={deviceRows}
        devicesError={devicesError}
        devicesLoading={devicesLoading}
        exportSession={id => void api.downloadSessionExport(id).catch(e => setSessionsError(e instanceof Error ? e.message : String(e)))}
        loadSession={id => void loadSession(id)}
        overlay={overlay}
        refreshDevices={() => void refreshDevices()}
        refreshSessions={() => void refreshSessions()}
        selectedSessionIds={selectedSessionIds}
        selectionMode={selectionMode}
        sessionId={sessionId}
        sessions={sessions}
        sessionsError={sessionsError}
        sessionsLoading={sessionsLoading}
        setOverlay={setOverlay}
        startNewSession={startNewSession}
        toggleSelectionMode={toggleSelectionMode}
        toggleSessionSelected={toggleSessionSelected}
      />

      {lightboxSrc && (
        <div
          className="fixed inset-0 z-[60] bg-gray-950/90"
          onClick={closeLightbox}
          onWheel={handleLightboxWheel}
        >
          <div className="flex h-full w-full cursor-zoom-out items-center justify-center overflow-auto p-4">
            <div
              className="cursor-default rounded-2xl bg-white/5 p-2 shadow-2xl"
              style={{ transform: `scale(${lightboxScale})`, transformOrigin: 'center center' }}
              onClick={e => e.stopPropagation()}
            >
              <AuthImage src={lightboxSrc} className="max-h-[82vh] max-w-[92vw] select-none object-contain" alt="预览" draggable={false} />
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function TopHUD({
  activeDevice,
  activeSession,
  authenticated,
  busy,
  messageCount,
  sessionsCount,
  setOverlay,
  startNewSession
}: {
  activeDevice: DeviceRow | null;
  activeSession: SessionDto | null;
  authenticated: boolean;
  busy: boolean;
  messageCount: number;
  sessionsCount: number;
  setOverlay: (overlay: Overlay) => void;
  startNewSession: () => void;
}) {
  const deviceLabel = activeDevice
    ? activeDevice.name
    : authenticated
      ? 'No device'
      : 'Preview';
  const deviceSub = activeDevice
    ? `${activeDevice.online ? 'Active link' : 'Last seen'} · ${activeDevice.toolCount} tools`
    : authenticated
      ? 'Open device pool'
      : 'Login to sync';

  return (
    <div className="pointer-events-none fixed left-0 top-8 z-40 flex w-full justify-center px-4">
      <div className="glass-panel pointer-events-auto flex max-w-[calc(100vw-2rem)] items-center gap-2 rounded-full px-3 py-2 shadow-[0_8px_32px_rgba(0,0,0,0.04)]">
        <button
          type="button"
          className="flex min-w-0 items-center gap-3 rounded-full px-2 py-1.5 text-left transition hover:bg-white/50"
          onClick={() => setOverlay('devices')}
          title="设备"
        >
          <span className="relative flex h-3 w-3 shrink-0">
            <span className={`absolute inline-flex h-full w-full rounded-full ${activeDevice?.online ? 'animate-ping bg-emerald-400 opacity-75' : 'bg-gray-300'}`} />
            <span className={`relative inline-flex h-3 w-3 rounded-full ${activeDevice?.online ? 'bg-emerald-500' : 'bg-gray-300'}`} />
          </span>
          <span className="min-w-0">
            <span className="block truncate text-[13px] font-semibold tracking-wide text-gray-800">{deviceLabel}</span>
            <span className="block truncate text-[10px] font-medium uppercase tracking-widest text-gray-400">{deviceSub}</span>
          </span>
        </button>

        <span className="hidden h-6 w-px bg-gray-200/70 sm:block" />

        <button
          type="button"
          className="hidden min-w-0 items-center gap-2 rounded-full px-3 py-2 text-left transition hover:bg-white/50 sm:flex"
          onClick={() => setOverlay('sessions')}
          title="会话"
        >
          <History size={16} className="shrink-0 text-gray-400" />
          <span className="min-w-0">
            <span className="block max-w-48 truncate text-[13px] font-semibold text-gray-800">
              {activeSession ? sessionTitle(activeSession) : 'New session'}
            </span>
            <span className="block text-[10px] font-medium uppercase tracking-widest text-gray-400">
              {sessionsCount} sessions · {messageCount} events
            </span>
          </span>
        </button>

        <span className="hidden h-6 w-px bg-gray-200/70 sm:block" />

        <div className="flex items-center gap-1 text-gray-400">
          <button
            type="button"
            className="rounded-full p-2 transition hover:bg-white/60 hover:text-gray-900"
            onClick={startNewSession}
            title="新会话"
            aria-label="新会话"
          >
            <MessageSquarePlus size={16} />
          </button>
          <button
            type="button"
            className="rounded-full p-2 transition hover:bg-white/60 hover:text-gray-900"
            onClick={() => setOverlay('sessions')}
            title="会话历史"
            aria-label="会话历史"
          >
            <History size={16} />
          </button>
          <button
            type="button"
            className="rounded-full p-2 transition hover:bg-white/60 hover:text-gray-900"
            onClick={() => setOverlay('settings')}
            title="设置"
            aria-label="设置"
          >
            <SlidersHorizontal size={16} />
          </button>
          {!authenticated && (
            <button
              type="button"
              className="rounded-full p-2 text-gray-500 transition hover:bg-white/60 hover:text-gray-900"
              onClick={goLogin}
              title="登录"
              aria-label="登录"
            >
              <LogIn size={16} />
            </button>
          )}
        </div>

        {busy && (
          <span className="ml-1 hidden items-center gap-1 rounded-full bg-amber-50 px-2.5 py-1 text-[11px] font-semibold text-amber-700 sm:inline-flex">
            <Loader2 size={12} className="animate-spin" />
            Running
          </span>
        )}
      </div>
    </div>
  );
}

function SessionHeader({
  activeDevice,
  activeSession,
  authenticated,
  eventsCount,
  messagesLoading,
  toolCallCount,
  toolResultCount
}: {
  activeDevice: DeviceRow | null;
  activeSession: SessionDto | null;
  authenticated: boolean;
  eventsCount: number;
  messagesLoading: boolean;
  toolCallCount: number;
  toolResultCount: number;
}) {
  const title = activeSession ? sessionTitle(activeSession) : authenticated ? 'Agent Session' : 'Agent Session Preview';
  const device = activeDevice ? activeDevice.name : authenticated ? 'No device online' : 'Preview mode';
  return (
    <div className="animate-float-up mb-14 text-center opacity-70">
      <h1 className="mb-2 text-sm font-semibold uppercase tracking-[0.2em] text-gray-400">{title}</h1>
      <div className="flex flex-wrap justify-center gap-x-4 gap-y-1 text-xs font-medium text-gray-400">
        <span>{messagesLoading ? 'Loading history' : `${eventsCount} events`}</span>
        <span>{device}</span>
        <span>{toolCallCount} calls · {toolResultCount} results</span>
      </div>
    </div>
  );
}

function EmptySession({ onAttach, onOpenDevices }: { onAttach: () => void; onOpenDevices: () => void }) {
  return (
    <div className="animate-float-up mx-auto flex min-h-[46vh] max-w-2xl flex-col items-center justify-center text-center">
      <div className="glass-panel grid h-16 w-16 place-items-center rounded-3xl text-blue-600 shadow-sm">
        <Sparkles size={26} />
      </div>
      <h2 className="mt-5 text-2xl font-semibold tracking-tight text-gray-900">新会话已准备</h2>
      <div className="mt-6 grid w-full gap-3 sm:grid-cols-3">
        <button type="button" onClick={onAttach} className="rounded-3xl border border-white/80 bg-white/50 p-4 text-left shadow-sm backdrop-blur transition hover:bg-white/80">
          <ImageIcon size={18} className="text-blue-500" />
          <div className="mt-3 text-sm font-semibold text-gray-900">图片理解</div>
          <p className="mt-1 text-xs leading-5 text-gray-500">上传图片并交给 agent 分析。</p>
        </button>
        <button type="button" onClick={onOpenDevices} className="rounded-3xl border border-white/80 bg-white/50 p-4 text-left shadow-sm backdrop-blur transition hover:bg-white/80">
          <Smartphone size={18} className="text-emerald-500" />
          <div className="mt-3 text-sm font-semibold text-gray-900">设备工具</div>
          <p className="mt-1 text-xs leading-5 text-gray-500">连接手机后执行真实工具。</p>
        </button>
        <div className="rounded-3xl border border-white/80 bg-white/50 p-4 text-left shadow-sm backdrop-blur">
          <Terminal size={18} className="text-gray-500" />
          <div className="mt-3 text-sm font-semibold text-gray-900">过程轨迹</div>
          <p className="mt-1 text-xs leading-5 text-gray-500">工具调用会折叠成运行面板。</p>
        </div>
      </div>
    </div>
  );
}

function OmniBar({
  authenticated,
  busy,
  composerError,
  fileInputRef,
  input,
  messagesLoading,
  onDragOver,
  onDrop,
  onFileChange,
  onOpenSessions,
  onPaste,
  onRemoveImage,
  onSend,
  onStop,
  pendingImages,
  setInput
}: {
  authenticated: boolean;
  busy: boolean;
  composerError: string | null;
  fileInputRef: React.RefObject<HTMLInputElement>;
  input: string;
  messagesLoading: boolean;
  onDragOver: (e: DragEvent<HTMLFormElement>) => void;
  onDrop: (e: DragEvent<HTMLFormElement>) => void;
  onFileChange: (e: React.ChangeEvent<HTMLInputElement>) => void;
  onOpenSessions: () => void;
  onPaste: (e: ClipboardEvent<HTMLTextAreaElement>) => void;
  onRemoveImage: (id: string) => void;
  onSend: () => void;
  onStop: () => void;
  pendingImages: PendingImage[];
  setInput: (value: string) => void;
}) {
  return (
    <div className="fixed bottom-10 left-0 z-40 flex w-full justify-center px-6 max-sm:bottom-4 max-sm:px-3">
      <form
        className="glass-panel group relative w-full max-w-3xl rounded-[2rem] p-2 shadow-[0_20px_40px_rgba(0,0,0,0.06)] transition-all duration-500 focus-within:shadow-[0_20px_60px_rgba(59,130,246,0.1)]"
        onSubmit={(event: FormEvent) => {
          event.preventDefault();
          onSend();
        }}
        onDrop={onDrop}
        onDragOver={onDragOver}
      >
        <div className="pointer-events-none absolute inset-0 rounded-[2rem] bg-gradient-to-r from-blue-500/10 via-purple-500/10 to-blue-500/10 opacity-0 blur-xl transition-opacity duration-700 group-focus-within:opacity-100" />

        {(pendingImages.length > 0 || composerError) && (
          <div className="relative z-10 border-b border-white/60 px-3 pb-3 pt-2">
            {pendingImages.length > 0 && (
              <PendingImageGrid items={pendingImages} disabled={messagesLoading} onRemove={onRemoveImage} />
            )}
            {composerError && <div className="mt-2 rounded-2xl border border-red-100 bg-red-50/80 px-3 py-2 text-xs font-medium text-red-700">{composerError}</div>}
          </div>
        )}

        <div className="relative z-10 flex items-end gap-2">
          <input
            ref={fileInputRef}
            type="file"
            accept="image/jpeg,image/png,image/webp"
            multiple
            className="hidden"
            onChange={onFileChange}
            disabled={messagesLoading}
          />
          <button
            type="button"
            className="shrink-0 rounded-2xl p-4 text-gray-400 transition-colors hover:bg-gray-50/50 hover:text-gray-900 disabled:opacity-40"
            onClick={() => fileInputRef.current?.click()}
            disabled={messagesLoading || pendingImages.length >= MAX_ATTACHMENTS}
            aria-label="添加图片"
            title="添加图片"
          >
            <Paperclip size={20} />
          </button>

          <button
            type="button"
            className="hidden shrink-0 rounded-2xl p-4 text-gray-400 transition-colors hover:bg-gray-50/50 hover:text-gray-900 sm:block"
            onClick={onOpenSessions}
            aria-label="会话历史"
            title="会话历史"
          >
            <History size={20} />
          </button>

          <label className="sr-only" htmlFor="visual-omnibar">
            输入消息
          </label>
          <textarea
            id="visual-omnibar"
            value={input}
            onChange={event => setInput(event.target.value)}
            onPaste={onPaste}
            onKeyDown={(event: KeyboardEvent<HTMLTextAreaElement>) => {
              if (event.key === 'Enter' && !event.shiftKey) {
                event.preventDefault();
                onSend();
              }
            }}
            placeholder={authenticated ? busy ? '当前会话生成中，可以继续输入下一条...' : '输入指令，或粘贴图片...' : '登录后接入真实会话...'}
            className="max-h-32 min-h-[56px] flex-1 resize-none bg-transparent py-4 text-[16px] leading-relaxed text-gray-800 outline-none placeholder:text-gray-400"
            rows={1}
            disabled={messagesLoading}
          />

          {busy ? (
            <button
              type="button"
              className="flex shrink-0 items-center justify-center rounded-[1.5rem] bg-amber-500 p-4 text-white shadow-md transition-all hover:-translate-y-0.5 hover:bg-amber-600 hover:shadow-xl active:translate-y-0"
              onClick={onStop}
              aria-label="停止"
              title="停止"
            >
              <Square size={20} />
            </button>
          ) : (
            <button
              type="submit"
              className="flex shrink-0 items-center justify-center rounded-[1.5rem] bg-gray-900 p-4 text-white shadow-md transition-all hover:-translate-y-0.5 hover:bg-black hover:shadow-xl active:translate-y-0 disabled:translate-y-0 disabled:bg-gray-300 disabled:shadow-none"
              disabled={messagesLoading || (authenticated && !input.trim() && pendingImages.length === 0)}
              aria-label={authenticated ? '发送' : '登录'}
            >
              {authenticated ? <ArrowRight size={20} /> : <LogIn size={20} />}
            </button>
          )}
        </div>
      </form>
    </div>
  );
}

function VisualBubble({
  event,
  index,
  nextToolResult,
  onOpenImage
}: {
  event: ChatEvent;
  index: number;
  nextToolResult?: ChatEvent | null;
  onOpenImage: OpenImage;
}) {
  const delayStyle = { animationDelay: `${Math.min(index, 8) * 0.08}s` };

  if (event.type === 'user_message') {
    const attachments = normalizeMessageAttachments(event.data?.attachments);
    return (
      <div className="animate-float-up mb-4 flex justify-end" style={delayStyle}>
        <div className="max-w-[min(42rem,86%)]">
          {attachments.length > 0 && (
            <div className="mb-2 flex justify-end">
              <MessageAttachmentGrid items={attachments} onOpenImage={onOpenImage} />
            </div>
          )}
          {event.data?.content && (
            <div className="rounded-[1.5rem] rounded-tr-sm bg-gray-900 px-6 py-4 text-[16px] leading-relaxed text-white shadow-md max-sm:max-w-[88vw]">
              {event.data.content}
            </div>
          )}
          <BubbleMeta align="right" createdAt={event.data?.createdAt} />
        </div>
      </div>
    );
  }

  if (event.type === 'assistant_message') {
    return (
      <div className="animate-float-up mt-4 flex max-w-[85%] items-start gap-5 max-sm:max-w-full" style={delayStyle}>
        <AgentAvatar />
        <div className="min-w-0 pt-1.5">
          <MarkdownMessage content={event.data?.content ?? ''} />
          <BubbleMeta createdAt={event.data?.createdAt} durationMs={event.data?.durationMs} />
        </div>
      </div>
    );
  }

  if (event.type === 'tool_call_started') {
    const status = nextToolResult
      ? hasToolError(nextToolResult.data?.result) ? 'failed' : 'success'
      : 'running';
    return (
      <div className="animate-float-up" style={delayStyle}>
        <ToolStepPill
          step={{
            time: formatMessageTime(event.data?.createdAt) || 'now',
            text: `${nextToolResult ? '工具完成' : '调用工具'}: ${event.data?.tool}${event.data?.args ? ` ${compactJson(event.data.args)}` : ''}`,
            status,
            isCode: true
          }}
        />
      </div>
    );
  }

  if (event.type === 'error') {
    return (
      <div className="animate-float-up max-w-2xl rounded-3xl border border-red-100 bg-red-50/80 px-4 py-3 text-sm font-medium text-red-700 shadow-sm backdrop-blur" style={delayStyle}>
        {event.data?.message ?? '未知错误'}
      </div>
    );
  }

  return null;
}

function VisualToolProcessAccordion({
  item,
  busy,
  now
}: {
  item: Extract<TimelineItem, { kind: 'process' }>;
  busy: boolean;
  now: number;
}) {
  const [isExpanded, setIsExpanded] = useState(false);
  const status = processStatus(item);
  const active = busy && status === 'running';
  const duration = processDuration(item, now, active);
  const summary = processSummary(item) || '工具运行过程';
  const steps = toolProcessSteps(item, active);

  return (
    <div className="group mb-6 mt-2 max-w-[80%] pl-[4.5rem] max-sm:max-w-full max-sm:pl-0">
      <button
        type="button"
        onClick={() => setIsExpanded(value => !value)}
        className="inline-flex max-w-full items-center gap-2 rounded-full px-3 py-1.5 text-left transition-colors hover:bg-gray-100/50"
        aria-expanded={isExpanded}
      >
        <span className={`shrink-0 text-gray-400 transition-transform duration-300 ${isExpanded ? 'rotate-90' : 'rotate-0'}`}>
          <ChevronRight size={14} strokeWidth={2.5} />
        </span>
        <span className="shrink-0 text-blue-500">
          <Terminal size={14} />
        </span>
        <span className="min-w-0 truncate text-[13px] font-medium tracking-wide text-gray-500">{summary}</span>
        {duration && <span className="shrink-0 text-[11px] font-medium text-gray-400">{duration}</span>}
        {!isExpanded && (
          <span className="relative ml-1 flex h-2 w-2 shrink-0">
            <span className={`absolute inline-flex h-full w-full rounded-full ${active ? 'animate-ping bg-blue-400 opacity-75' : 'bg-emerald-400'}`} />
            <span className={`relative inline-flex h-2 w-2 rounded-full ${status === 'failed' ? 'bg-red-500' : active ? 'bg-blue-500' : 'bg-emerald-500'}`} />
          </span>
        )}
      </button>

      <div className={`grid-expand ${isExpanded ? 'expanded' : ''}`}>
        <div className="grid-expand-inner">
          <div className="ml-3 mt-3 flex flex-col gap-3 rounded-2xl border border-white/80 bg-white/40 p-4 font-mono text-[12px] shadow-sm backdrop-blur-sm max-sm:ml-0">
            {steps.map((step, index) => (
              <ToolStepPill key={`${step.time}-${index}-${step.text}`} step={step} />
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}

function ToolStepPill({ step }: { step: ToolStep }) {
  return (
    <div className="flex min-w-0 items-start gap-3">
      <div className="mt-0.5 shrink-0">
        {step.status === 'success' && <CheckCircle2 size={14} className="text-emerald-500" />}
        {step.status === 'running' && <Loader2 size={14} className="animate-spin text-blue-500" />}
        {step.status === 'failed' && <X size={14} className="text-red-500" />}
        {step.status === 'pending' && <div className="ml-1 mt-1 h-1.5 w-1.5 rounded-full bg-gray-300" />}
      </div>
      <div className="flex min-w-0 flex-col gap-0.5">
        <span className={`min-w-0 break-words leading-relaxed ${step.isCode ? 'rounded bg-blue-50/50 px-1.5 text-blue-600' : 'text-gray-600'}`}>
          {step.text}
        </span>
        <span className="text-[10px] text-gray-400">{step.time}</span>
      </div>
    </div>
  );
}

function InlineMediaResult({ event, onOpenCanvas }: { event: ChatEvent; onOpenCanvas: () => void }) {
  const count = visibleToolResultCount(event);
  return (
    <div className="animate-float-up pl-[4.5rem] max-sm:pl-0">
      <button
        type="button"
        onClick={onOpenCanvas}
        className="glass-panel inline-flex max-w-full items-center gap-3 rounded-full px-4 py-2 text-left shadow-sm transition hover:bg-white/80"
      >
        <span className="grid h-8 w-8 shrink-0 place-items-center rounded-full bg-blue-50 text-blue-600">
          <ImageIcon size={16} />
        </span>
        <span className="min-w-0">
          <span className="block truncate text-sm font-semibold text-gray-900">{toolResultTitle(String(event.data?.tool ?? 'tool'))}</span>
          <span className="block truncate text-xs text-gray-500">{count ? `${count} 项结果已放入右侧画布` : '结果已放入右侧画布'}</span>
        </span>
      </button>
    </div>
  );
}

function VisualCanvas({
  event,
  isVisible,
  onClose,
  onOpenImage
}: {
  event: ChatEvent | null;
  isVisible: boolean;
  onClose: () => void;
  onOpenImage: OpenImage;
}) {
  if (!isVisible || !event) return null;
  const tool = String(event.data?.tool ?? 'tool');
  const count = visibleToolResultCount(event);

  return (
    <div className="glass-panel animate-expand-panel fixed right-8 top-[10vh] z-30 flex h-[80vh] w-[45%] flex-col overflow-hidden rounded-[2.5rem] border border-white/60 shadow-[-20px_0_60px_rgba(0,0,0,0.03)] max-xl:relative max-xl:right-auto max-xl:top-auto max-xl:z-10 max-xl:mt-8 max-xl:h-auto max-xl:w-full max-xl:rounded-[2rem]">
      <div className="flex h-20 items-center justify-between border-b border-gray-100/50 bg-white/40 px-8 max-sm:px-5">
        <div className="flex min-w-0 items-center gap-3">
          <div className="rounded-xl bg-blue-50 p-2.5 text-blue-600">
            <ImageIcon size={18} />
          </div>
          <div className="min-w-0">
            <h2 className="truncate text-sm font-bold tracking-wide text-gray-900">{toolResultTitle(tool)}</h2>
            <p className="truncate text-[11px] font-medium text-gray-500">{count ? `${count} 项 · ${tool}` : tool}</p>
          </div>
        </div>
        <button type="button" onClick={onClose} className="rounded-full p-2 text-gray-400 shadow-sm transition-colors hover:bg-white" aria-label="关闭画布">
          <X size={18} />
        </button>
      </div>

      <div className="flex-1 overflow-y-auto p-6 scroll-smooth max-sm:p-4">
        <CanvasToolResult tool={tool} result={event.data?.result} onOpenImage={onOpenImage} />
      </div>
    </div>
  );
}

function CanvasToolResult({ tool, result, onOpenImage }: { tool: string; result: any; onOpenImage: OpenImage }) {
  const photos = canvasPhotoItems(tool, result);
  if (photos.length > 0) {
    return (
      <div className="grid grid-cols-2 gap-4 max-sm:grid-cols-1">
        {photos.map((photo, index) => {
          const { big, small } = photoImageSources(photo);
          return (
            <button
              type="button"
              key={photo.id ?? photo.asset_id ?? photo.name ?? index}
              className={`group relative cursor-pointer overflow-hidden rounded-3xl bg-gray-100 text-left ${
                index % 3 === 0 ? 'row-span-2 aspect-[3/4]' : 'aspect-square'
              }`}
              onClick={() => (big || small) && onOpenImage(big ?? small ?? '')}
            >
              {small ? (
                <AuthImage src={small} className="h-full w-full object-cover transition-transform duration-700 group-hover:scale-105" alt={photo.name ?? `结果 ${index + 1}`} />
              ) : (
                <div className="grid h-full w-full place-items-center px-4 text-center text-xs text-gray-400">{photo.name ?? photo.id ?? '结果'}</div>
              )}
              <span className="absolute inset-0 bg-black/0 transition-all duration-300 group-hover:bg-black/20" />
              <span className="absolute right-4 top-4 translate-y-2 opacity-0 transition-all duration-300 group-hover:translate-y-0 group-hover:opacity-100">
                <span className="grid h-8 w-8 place-items-center rounded-full bg-white/90 text-gray-900 shadow-lg backdrop-blur">
                  <Maximize2 size={16} />
                </span>
              </span>
              {photo.name && (
                <span className="absolute bottom-3 left-3 right-3 truncate rounded-full bg-white/85 px-3 py-1.5 text-xs font-medium text-gray-700 opacity-0 shadow-sm backdrop-blur transition group-hover:opacity-100">
                  {photo.name}
                </span>
              )}
            </button>
          );
        })}
      </div>
    );
  }
  return <ToolResult tool={tool} result={result} onOpenImage={onOpenImage} />;
}

function OverlayManager({
  authenticated,
  bulkDeleting,
  deleteBusyId,
  deleteSelectedSessions,
  deleteSession,
  deviceRows,
  devicesError,
  devicesLoading,
  exportSession,
  loadSession,
  overlay,
  refreshDevices,
  refreshSessions,
  selectedSessionIds,
  selectionMode,
  sessionId,
  sessions,
  sessionsError,
  sessionsLoading,
  setOverlay,
  startNewSession,
  toggleSelectionMode,
  toggleSessionSelected
}: {
  authenticated: boolean;
  bulkDeleting: boolean;
  deleteBusyId: string | null;
  deleteSelectedSessions: () => void;
  deleteSession: (id: string) => void;
  deviceRows: DeviceRow[];
  devicesError: string | null;
  devicesLoading: boolean;
  exportSession: (id: string) => void;
  loadSession: (id: string) => void;
  overlay: Overlay;
  refreshDevices: () => void;
  refreshSessions: () => void;
  selectedSessionIds: Set<string>;
  selectionMode: boolean;
  sessionId: string | null;
  sessions: SessionDto[];
  sessionsError: string | null;
  sessionsLoading: boolean;
  setOverlay: (overlay: Overlay) => void;
  startNewSession: () => void;
  toggleSelectionMode: () => void;
  toggleSessionSelected: (id: string) => void;
}) {
  if (!overlay) return null;

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-8 max-sm:p-3">
      <button type="button" className="absolute inset-0 bg-gray-900/10 backdrop-blur-md transition-opacity" onClick={() => setOverlay(null)} aria-label="关闭弹层" />

      <div className="animate-float-up relative z-10 flex h-[80vh] w-full max-w-5xl flex-col overflow-hidden rounded-[3rem] border border-white bg-white/80 shadow-[0_0_100px_rgba(0,0,0,0.1)] backdrop-blur-3xl max-sm:h-[88vh] max-sm:rounded-[2rem]">
        <div className="flex h-24 items-center justify-between border-b border-gray-100/50 px-12 max-sm:h-20 max-sm:px-6">
          <h2 className="text-2xl font-bold tracking-tight text-gray-900 max-sm:text-xl">
            {overlay === 'sessions' ? '会话星图' : overlay === 'devices' ? '终端与连接池' : 'Agent 配置中心'}
          </h2>
          <button type="button" onClick={() => setOverlay(null)} className="rounded-full bg-gray-50 p-3 text-gray-500 transition-colors hover:bg-gray-100" aria-label="关闭">
            <X size={20} />
          </button>
        </div>

        {overlay === 'sessions' && (
          <SessionsOverlay
            authenticated={authenticated}
            bulkDeleting={bulkDeleting}
            deleteBusyId={deleteBusyId}
            deleteSelectedSessions={deleteSelectedSessions}
            deleteSession={deleteSession}
            exportSession={exportSession}
            loadSession={loadSession}
            refreshSessions={refreshSessions}
            selectedSessionIds={selectedSessionIds}
            selectionMode={selectionMode}
            sessionId={sessionId}
            sessions={sessions}
            sessionsError={sessionsError}
            sessionsLoading={sessionsLoading}
            startNewSession={startNewSession}
            toggleSelectionMode={toggleSelectionMode}
            toggleSessionSelected={toggleSessionSelected}
          />
        )}
        {overlay === 'devices' && (
          <DevicesOverlay
            authenticated={authenticated}
            deviceRows={deviceRows}
            devicesError={devicesError}
            devicesLoading={devicesLoading}
            refreshDevices={refreshDevices}
          />
        )}
        {overlay === 'settings' && <SettingsOverlay authenticated={authenticated} />}
      </div>
    </div>
  );
}

function SessionsOverlay({
  authenticated,
  bulkDeleting,
  deleteBusyId,
  deleteSelectedSessions,
  deleteSession,
  exportSession,
  loadSession,
  refreshSessions,
  selectedSessionIds,
  selectionMode,
  sessionId,
  sessions,
  sessionsError,
  sessionsLoading,
  startNewSession,
  toggleSelectionMode,
  toggleSessionSelected
}: {
  authenticated: boolean;
  bulkDeleting: boolean;
  deleteBusyId: string | null;
  deleteSelectedSessions: () => void;
  deleteSession: (id: string) => void;
  exportSession: (id: string) => void;
  loadSession: (id: string) => void;
  refreshSessions: () => void;
  selectedSessionIds: Set<string>;
  selectionMode: boolean;
  sessionId: string | null;
  sessions: SessionDto[];
  sessionsError: string | null;
  sessionsLoading: boolean;
  startNewSession: () => void;
  toggleSelectionMode: () => void;
  toggleSessionSelected: (id: string) => void;
}) {
  const [previewBySessionId, setPreviewBySessionId] = useState<Record<string, SessionPreviewState>>({});
  const [openPreviewSessionId, setOpenPreviewSessionId] = useState<string | null>(null);

  function ensureSessionPreview(id: string) {
    const current = previewBySessionId[id];
    if (current?.status === 'loading' || current?.status === 'ready') return;
    setPreviewBySessionId(prev => ({ ...prev, [id]: { status: 'loading' } }));
    api.listMessages(id)
      .then(rows => {
        const loaded = rows.map(messageToEvent).filter((ev): ev is ChatEvent => ev !== null);
        setPreviewBySessionId(prev => ({ ...prev, [id]: { status: 'ready', events: loaded } }));
      })
      .catch((e: unknown) => {
        setPreviewBySessionId(prev => ({
          ...prev,
          [id]: { status: 'error', message: e instanceof Error ? e.message : String(e) }
        }));
      });
  }

  if (!authenticated) return <AuthGate />;
  return (
    <div className="flex-1 overflow-y-auto bg-[#FAFAFC]/50 p-10 max-sm:p-5">
      <div className="mb-6 flex flex-wrap items-center justify-between gap-3">
        <div className="flex flex-wrap items-center gap-2">
          <button type="button" onClick={startNewSession} className="rounded-full bg-gray-900 px-4 py-2 text-sm font-semibold text-white shadow-sm transition hover:bg-black">
            <Plus size={16} className="mr-1 inline" />
            新会话
          </button>
          <button type="button" onClick={refreshSessions} className="rounded-full bg-white px-4 py-2 text-sm font-semibold text-gray-700 shadow-sm transition hover:bg-gray-50">
            <RefreshCw size={16} className="mr-1 inline" />
            刷新
          </button>
          {sessions.length > 0 && (
            <button type="button" onClick={toggleSelectionMode} className="rounded-full bg-white px-4 py-2 text-sm font-semibold text-gray-700 shadow-sm transition hover:bg-gray-50">
              {selectionMode ? '完成多选' : '多选'}
            </button>
          )}
        </div>
        {selectionMode && (
          <button
            type="button"
            onClick={deleteSelectedSessions}
            disabled={bulkDeleting || selectedSessionIds.size === 0}
            className="rounded-full bg-red-50 px-4 py-2 text-sm font-semibold text-red-600 transition hover:bg-red-100 disabled:opacity-40"
          >
            删除选中 {selectedSessionIds.size}
          </button>
        )}
      </div>

      {sessionsLoading && <SoftStatus>加载会话中...</SoftStatus>}
      {sessionsError && <ErrorStatus>{sessionsError}</ErrorStatus>}
      {!sessionsLoading && !sessionsError && sessions.length === 0 && (
        <div className="rounded-[2rem] border border-dashed border-gray-200 bg-white/50 p-10 text-center text-sm text-gray-500">
          暂无历史会话。
        </div>
      )}
      <div className="grid gap-3">
        {sessions.map(session => {
          const active = session.id === sessionId;
          const selected = selectedSessionIds.has(session.id);
          const preview = previewBySessionId[session.id];
          return (
            <div
              key={session.id}
              className={[
                'group/session relative rounded-[1.75rem] border p-4 shadow-sm transition',
                active ? 'border-gray-900 bg-gray-900 text-white' : selected ? 'border-blue-200 bg-blue-50/70 text-gray-900' : 'border-white bg-white/60 text-gray-900 hover:bg-white'
              ].join(' ')}
            >
              <div className="flex items-center gap-3">
                {selectionMode && (
                  <input
                    type="checkbox"
                    checked={selected}
                    onChange={() => toggleSessionSelected(session.id)}
                    className="h-4 w-4 rounded border-gray-300 text-blue-600"
                    aria-label={`选择 ${sessionTitle(session)}`}
                  />
                )}
                <button
                  type="button"
                  onClick={() => selectionMode ? toggleSessionSelected(session.id) : loadSession(session.id)}
                  className="min-w-0 flex-1 text-left"
                  aria-describedby={`session-preview-${session.id}`}
                  onFocus={() => {
                    setOpenPreviewSessionId(session.id);
                    ensureSessionPreview(session.id);
                  }}
                  onBlur={() => setOpenPreviewSessionId(current => current === session.id ? null : current)}
                >
                  <div
                    className="truncate text-sm font-bold"
                    onMouseEnter={() => {
                      setOpenPreviewSessionId(session.id);
                      ensureSessionPreview(session.id);
                    }}
                    onMouseLeave={() => setOpenPreviewSessionId(current => current === session.id ? null : current)}
                  >
                    {sessionTitle(session)}
                  </div>
                  <div className={`mt-1 flex items-center gap-2 text-xs ${active ? 'text-gray-300' : 'text-gray-500'}`}>
                    <Clock size={12} />
                    <span>{formatSessionTime(session)}</span>
                    <span className="font-mono">{session.id.slice(0, 8)}</span>
                  </div>
                </button>
                <div className="flex shrink-0 items-center gap-1 opacity-0 transition group-hover:opacity-100 focus-within:opacity-100">
                  <button type="button" onClick={() => exportSession(session.id)} className={`rounded-full p-2 ${active ? 'text-gray-300 hover:bg-white/10 hover:text-white' : 'text-gray-400 hover:bg-gray-100 hover:text-gray-900'}`} title="导出" aria-label="导出">
                    <Download size={16} />
                  </button>
                  <button
                    type="button"
                    onClick={() => deleteSession(session.id)}
                    disabled={deleteBusyId === session.id}
                    className={`rounded-full p-2 ${active ? 'text-gray-300 hover:bg-white/10 hover:text-white' : 'text-gray-400 hover:bg-red-50 hover:text-red-600'} disabled:opacity-40`}
                    title="删除"
                    aria-label="删除"
                  >
                    <Trash2 size={16} />
                  </button>
                </div>
              </div>
              <SessionPreviewPopover
                active={active}
                open={openPreviewSessionId === session.id}
                preview={preview}
                session={session}
              />
            </div>
          );
        })}
      </div>
    </div>
  );
}

function SessionPreviewPopover({
  active,
  open,
  preview,
  session
}: {
  active: boolean;
  open: boolean;
  preview?: SessionPreviewState;
  session: SessionDto;
}) {
  const entries = preview?.status === 'ready' ? sessionPreviewEntries(preview.events) : [];
  return (
    <div
      id={`session-preview-${session.id}`}
      className={[
        'pointer-events-none absolute left-8 right-8 top-[calc(100%-0.35rem)] z-20 transition duration-200 max-md:left-4 max-md:right-4',
        open ? 'visible translate-y-0 opacity-100' : 'invisible translate-y-2 opacity-0'
      ].join(' ')}
      role="status"
      aria-hidden={!open}
    >
      <div className="rounded-[1.6rem] border border-white/80 bg-white/90 p-4 text-gray-900 shadow-[0_24px_70px_rgba(15,23,42,0.18)] backdrop-blur-2xl">
        <div className="mb-3 flex items-start justify-between gap-4">
          <div className="min-w-0">
            <div className="truncate text-sm font-black">{sessionTitle(session)}</div>
            <div className="mt-1 flex items-center gap-2 text-[11px] font-medium text-gray-400">
              <MessageSquarePlus size={12} />
              <span>预览记录</span>
              <span>{formatSessionTime(session)}</span>
            </div>
          </div>
          <span className={`mt-1 h-2 w-2 shrink-0 rounded-full ${active ? 'bg-emerald-400' : 'bg-gray-300'}`} />
        </div>

        {preview?.status === 'loading' && (
          <div className="flex items-center gap-2 rounded-2xl bg-gray-50 px-3 py-3 text-xs font-medium text-gray-500">
            <Loader2 size={14} className="animate-spin text-blue-500" />
            正在加载最近记录...
          </div>
        )}

        {preview?.status === 'error' && (
          <div className="rounded-2xl border border-red-100 bg-red-50/80 px-3 py-3 text-xs font-medium text-red-700">
            预览加载失败: {preview.message}
          </div>
        )}

        {preview?.status === 'ready' && entries.length === 0 && (
          <div className="rounded-2xl border border-dashed border-gray-200 bg-gray-50/80 px-3 py-3 text-xs text-gray-500">
            这个会话还没有可预览的消息。
          </div>
        )}

        {entries.length > 0 && (
          <div className="space-y-2">
            {entries.map(entry => (
              <div key={entry.key} className="grid grid-cols-[4.5rem_minmax(0,1fr)] gap-3 rounded-2xl bg-gray-50/80 px-3 py-2">
                <div className="flex items-center gap-2">
                  <span className={[
                    'h-1.5 w-1.5 rounded-full',
                    entry.tone === 'user' ? 'bg-gray-900' :
                      entry.tone === 'assistant' ? 'bg-blue-500' :
                        entry.tone === 'error' ? 'bg-red-500' : 'bg-emerald-500'
                  ].join(' ')} />
                  <span className="text-[11px] font-bold uppercase tracking-wide text-gray-500">{entry.label}</span>
                </div>
                <div className="min-w-0">
                  <div className="truncate text-xs font-semibold text-gray-800">{entry.content}</div>
                  {entry.meta && <div className="mt-0.5 truncate text-[10px] text-gray-400">{entry.meta}</div>}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

function DevicesOverlay({
  authenticated,
  deviceRows,
  devicesError,
  devicesLoading,
  refreshDevices
}: {
  authenticated: boolean;
  deviceRows: DeviceRow[];
  devicesError: string | null;
  devicesLoading: boolean;
  refreshDevices: () => void;
}) {
  const [enrollment, setEnrollment] = useState<EnrollmentResponse | null>(null);
  const [creating, setCreating] = useState(false);
  const [confirmingDeviceId, setConfirmingDeviceId] = useState<string | null>(null);
  const [revokingId, setRevokingId] = useState<string | null>(null);
  const onlineCount = deviceRows.filter(d => d.online).length;

  async function createEnrollment() {
    setCreating(true);
    try {
      const data = await api.createEnrollment();
      setEnrollment(data);
      refreshDevices();
    } finally {
      setCreating(false);
    }
  }

  async function revokeDevice(id: string) {
    setRevokingId(id);
    try {
      await api.revokeDevice(id);
      setConfirmingDeviceId(null);
      refreshDevices();
    } finally {
      setRevokingId(null);
    }
  }

  if (!authenticated) return <AuthGate />;

  return (
    <div className="flex-1 overflow-y-auto bg-[#FAFAFC]/50 p-10 max-sm:p-5">
      <div className="mb-6 grid gap-3 sm:grid-cols-3">
        <GlassMetric label="已绑定" value={String(deviceRows.length)} />
        <GlassMetric label="当前在线" value={String(onlineCount)} tone="emerald" />
        <GlassMetric label="待连接" value={String(deviceRows.filter(d => !d.lastSeenAt).length)} />
      </div>

      <div className="grid grid-cols-[minmax(0,1fr)_22rem] gap-6 max-lg:grid-cols-1">
        <div className="space-y-4">
          <div className="flex items-center justify-between gap-3">
            <button type="button" onClick={refreshDevices} className="rounded-full bg-white px-4 py-2 text-sm font-semibold text-gray-700 shadow-sm transition hover:bg-gray-50">
              <RefreshCw size={16} className="mr-1 inline" />
              刷新状态
            </button>
            <button type="button" onClick={createEnrollment} disabled={creating} className="rounded-full bg-gray-900 px-4 py-2 text-sm font-semibold text-white shadow-sm transition hover:bg-black disabled:opacity-50">
              <Plus size={16} className="mr-1 inline" />
              {creating ? '生成中...' : '新增设备'}
            </button>
          </div>
          {devicesLoading && <SoftStatus>加载设备中...</SoftStatus>}
          {devicesError && <ErrorStatus>{devicesError}</ErrorStatus>}
          {!devicesLoading && !devicesError && deviceRows.length === 0 && (
            <div className="rounded-[2rem] border border-dashed border-gray-200 bg-white/50 p-10 text-center text-sm text-gray-500">
              还没有设备。
            </div>
          )}
          {deviceRows.map(device => {
            const isConfirming = confirmingDeviceId === device.id;
            const isRevoking = revokingId === device.id;
            return (
              <div key={device.id} className="group relative overflow-hidden rounded-[2.5rem] border border-gray-100 bg-white/80 p-6 shadow-sm">
                <div className={`absolute left-0 top-0 h-1 w-full ${device.online ? 'bg-gradient-to-r from-emerald-400 to-teal-400' : 'bg-gray-200'}`} />
                <div className="flex flex-col gap-5 sm:flex-row sm:items-start sm:justify-between">
                  <div className="min-w-0">
                    <div className="mb-5 flex items-center gap-3">
                      <div className="flex h-14 w-14 items-center justify-center rounded-2xl bg-gray-50 text-gray-800">
                        <Smartphone size={28} strokeWidth={1.5} />
                      </div>
                      <div className={`flex items-center gap-2 rounded-full px-3 py-1 text-xs font-bold ${device.online ? 'bg-emerald-50 text-emerald-600' : 'bg-gray-100 text-gray-500'}`}>
                        <span className={`h-1.5 w-1.5 rounded-full ${device.online ? 'animate-pulse bg-emerald-500' : 'bg-gray-400'}`} />
                        {device.online ? 'ONLINE' : 'OFFLINE'}
                      </div>
                    </div>
                    <h3 className="truncate text-2xl font-bold text-gray-900">{device.name}</h3>
                    <p className="mt-1 text-sm font-medium text-gray-400">{device.model || '未知型号'} · {device.osVersion ? `Android ${device.osVersion}` : '未知系统'}</p>
                    <p className="mt-2 truncate font-mono text-[11px] text-gray-400">{device.id}</p>
                  </div>
                  <div className="grid min-w-48 grid-cols-2 gap-3">
                    <DeviceStat icon={<Wifi size={14} />} label="工具" value={String(device.toolCount)} />
                    <DeviceStat icon={<Battery size={14} />} label="连接" value={device.online ? 'live' : 'idle'} />
                  </div>
                </div>
                <div className="mt-5 flex flex-wrap items-center justify-between gap-3 border-t border-gray-100 pt-4 text-xs text-gray-500">
                  <span>{device.connectedAt ? `当前连接 ${new Date(device.connectedAt).toLocaleString()}` : device.lastSeenAt ? `最后连接 ${new Date(device.lastSeenAt).toLocaleString()}` : '从未连接'}</span>
                  {isConfirming ? (
                    <span className="flex items-center gap-2">
                      <button type="button" onClick={() => void revokeDevice(device.id)} disabled={isRevoking} className="rounded-full bg-red-50 px-3 py-1.5 font-semibold text-red-600 hover:bg-red-100 disabled:opacity-50">
                        {isRevoking ? '删除中...' : '确认删除'}
                      </button>
                      <button type="button" onClick={() => setConfirmingDeviceId(null)} className="rounded-full bg-gray-100 px-3 py-1.5 font-semibold text-gray-600 hover:bg-gray-200">
                        取消
                      </button>
                    </span>
                  ) : (
                    <button type="button" onClick={() => setConfirmingDeviceId(device.id)} className="rounded-full px-3 py-1.5 font-semibold text-red-500 transition hover:bg-red-50">
                      删除记录
                    </button>
                  )}
                </div>
              </div>
            );
          })}
        </div>

        <aside className="rounded-[2.5rem] border border-white bg-white/70 p-5 shadow-sm">
          {enrollment ? (
            <div className="space-y-4">
              <div className="rounded-[2rem] border border-gray-100 bg-white p-4">
                <QRCodeSVG value={enrollment.qrPayload} size={220} className="mx-auto" />
              </div>
              <div>
                <div className="text-sm font-bold text-gray-900">Token</div>
                <code className="mt-2 block break-all rounded-2xl border border-gray-100 bg-gray-50 px-3 py-2 text-xs text-gray-700">
                  {enrollment.token}
                </code>
              </div>
              <div className="rounded-2xl border border-blue-100 bg-blue-50 px-3 py-2 text-sm text-blue-800">
                过期时间 {new Date(enrollment.expiresAt).toLocaleString()}
              </div>
            </div>
          ) : (
            <div className="flex min-h-80 flex-col items-center justify-center rounded-[2rem] border border-dashed border-gray-200 bg-white/40 px-4 text-center">
              <Sparkles size={24} className="text-gray-400" />
              <div className="mt-4 text-sm font-bold text-gray-900">尚未生成绑定码</div>
              <p className="mt-2 text-sm leading-6 text-gray-500">新增设备后，这里会展示二维码和 token。</p>
            </div>
          )}
        </aside>
      </div>
    </div>
  );
}

function SettingsOverlay({ authenticated }: { authenticated: boolean }) {
  const [content, setContent] = useState('');
  const [autoMemoryEnabled, setAutoMemoryEnabled] = useState(true);
  const [updatedAt, setUpdatedAt] = useState<string | null>(null);
  const [loading, setLoading] = useState(authenticated);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [saveState, setSaveState] = useState<'idle' | 'saving' | 'saved' | 'error'>('idle');
  const [saveError, setSaveError] = useState<string | null>(null);
  const [memories, setMemories] = useState<MemoryFactDto[]>([]);
  const [memoriesLoading, setMemoriesLoading] = useState(authenticated);
  const [memoriesError, setMemoriesError] = useState<string | null>(null);
  const [deletingMemoryId, setDeletingMemoryId] = useState<string | null>(null);

  async function loadSettings() {
    if (!authenticated) return;
    setLoading(true);
    setMemoriesLoading(true);
    setLoadError(null);
    setMemoriesError(null);
    try {
      const [prefs, rows] = await Promise.all([api.getPreferences(), api.listMemories({ limit: 100, includeRaw: true })]);
      setContent(prefs.content ?? '');
      setAutoMemoryEnabled(prefs.autoMemoryEnabled ?? true);
      setUpdatedAt(prefs.updatedAt);
      setMemories(rows);
    } catch (e) {
      const message = e instanceof ApiError ? e.message : String(e);
      setLoadError(message);
      setMemoriesError(message);
    } finally {
      setLoading(false);
      setMemoriesLoading(false);
    }
  }

  async function save() {
    setSaveState('saving');
    setSaveError(null);
    try {
      const updated = await api.updatePreferences({ content, autoMemoryEnabled });
      setUpdatedAt(updated.updatedAt);
      setAutoMemoryEnabled(updated.autoMemoryEnabled ?? true);
      setSaveState('saved');
      window.setTimeout(() => setSaveState(s => (s === 'saved' ? 'idle' : s)), 2000);
    } catch (e) {
      setSaveState('error');
      setSaveError(e instanceof ApiError ? e.message : String(e));
    }
  }

  async function deleteMemory(memory: MemoryFactDto) {
    if (!window.confirm('删除这条长期记忆？删除后不会再被召回。')) return;
    setDeletingMemoryId(memory.id);
    setMemoriesError(null);
    try {
      await api.deleteMemory(memory.id);
      setMemories(rows => rows.filter(row => row.id !== memory.id));
    } catch (e) {
      setMemoriesError(e instanceof ApiError ? e.message : String(e));
    } finally {
      setDeletingMemoryId(null);
    }
  }

  useEffect(() => {
    void loadSettings();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  if (!authenticated) return <AuthGate />;

  return (
    <div className="flex-1 overflow-y-auto bg-[#FAFAFC]/50 p-10 max-sm:p-5">
      <div className="grid grid-cols-[minmax(0,1fr)_20rem] gap-6 max-lg:grid-cols-1">
        <section className="space-y-5">
          <div className="rounded-[2.5rem] border border-white bg-white/70 p-6 shadow-sm">
            <div className="mb-5 flex flex-wrap items-center justify-between gap-3">
              <div className="flex items-center gap-4">
                <div className="rounded-xl bg-purple-50 p-3 text-purple-600">
                  <Shield size={20} />
                </div>
                <div>
                  <h3 className="text-lg font-bold text-gray-900">记忆与规则</h3>
                  {updatedAt && <p className="mt-1 text-xs text-gray-400">最近保存 {new Date(updatedAt).toLocaleString()}</p>}
                </div>
              </div>
              <div className="flex items-center gap-2">
                <button type="button" onClick={() => void loadSettings()} disabled={loading} className="rounded-full bg-white px-4 py-2 text-sm font-semibold text-gray-700 shadow-sm transition hover:bg-gray-50 disabled:opacity-50">
                  <RefreshCw size={16} className="mr-1 inline" />
                  刷新
                </button>
                <button type="button" onClick={() => void save()} disabled={loading || saveState === 'saving'} className="rounded-full bg-gray-900 px-4 py-2 text-sm font-semibold text-white shadow-sm transition hover:bg-black disabled:opacity-50">
                  <Save size={16} className="mr-1 inline" />
                  {saveState === 'saving' ? '保存中...' : '保存'}
                </button>
              </div>
            </div>

            {loadError && <ErrorStatus>加载失败: {loadError}</ErrorStatus>}
            {saveState === 'saved' && <SoftStatus>已保存</SoftStatus>}
            {saveState === 'error' && <ErrorStatus>保存失败{saveError ? `: ${saveError}` : ''}</ErrorStatus>}

            <div className="mb-5 flex items-center justify-between gap-6 rounded-[2rem] border border-gray-100 bg-white/70 p-4">
              <div>
                <div className="font-semibold text-gray-900">自动长期记忆</div>
                <div className="mt-1 text-sm text-gray-400">控制自动召回和自动保存。</div>
              </div>
              <button
                type="button"
                className={`relative h-7 w-12 shrink-0 rounded-full transition ${autoMemoryEnabled ? 'bg-blue-600' : 'bg-gray-300'}`}
                onClick={() => setAutoMemoryEnabled(value => !value)}
                aria-label="切换自动长期记忆"
              >
                <span className={`absolute top-1 h-5 w-5 rounded-full bg-white shadow-sm transition ${autoMemoryEnabled ? 'left-6' : 'left-1'}`} />
              </button>
            </div>

            <textarea
              value={content}
              onChange={e => setContent(e.target.value)}
              rows={14}
              spellCheck={false}
              placeholder={'# 关于我\n\n# 偏好\n\n- 用中文回答。\n- 答案尽量简短。\n'}
              className="min-h-[22rem] w-full resize-y rounded-[2rem] border border-gray-100 bg-white/80 px-5 py-4 font-mono text-sm leading-relaxed text-gray-800 outline-none transition focus:border-blue-200 focus:ring-4 focus:ring-blue-100"
            />
          </div>

          <div className="rounded-[2.5rem] border border-white bg-white/70 p-6 shadow-sm">
            <div className="mb-4 flex items-center justify-between gap-3">
              <div>
                <h3 className="text-lg font-bold text-gray-900">长期记忆</h3>
                <p className="mt-1 text-sm text-gray-400">事实、偏好、规则和经验。</p>
              </div>
            </div>
            {memoriesLoading && <SoftStatus>加载长期记忆中...</SoftStatus>}
            {memoriesError && <ErrorStatus>长期记忆加载失败: {memoriesError}</ErrorStatus>}
            {!memoriesLoading && !memoriesError && memories.length === 0 && (
              <div className="rounded-[2rem] border border-dashed border-gray-200 bg-white/50 p-6 text-sm text-gray-500">还没有长期记忆。</div>
            )}
            <div className="space-y-3">
              {memories.map(memory => (
                <MemoryRow
                  key={memory.id}
                  memory={memory}
                  deleting={deletingMemoryId === memory.id}
                  onDelete={() => void deleteMemory(memory)}
                />
              ))}
            </div>
          </div>
        </section>

        <aside className="space-y-3">
          <InfoBlock title="作用范围" body="只影响你的会话，不修改项目级 prompt 或 packaged skills。" />
          <InfoBlock title="自动记忆" body="关闭后已有记忆保留，仍可查看和删除。" />
          <InfoBlock title="格式" body="支持 Markdown，分标题写更容易被正确召回。" />
        </aside>
      </div>
    </div>
  );
}

function PendingImageGrid({ items, disabled, onRemove }: { items: PendingImage[]; disabled: boolean; onRemove: (id: string) => void }) {
  return (
    <div className="flex flex-wrap gap-2">
      {items.map(item => (
        <div key={item.id} className="relative h-20 w-20 overflow-hidden rounded-2xl border border-white/80 bg-gray-100 shadow-sm">
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

function MessageAttachmentGrid({ items, onOpenImage }: { items: UploadedChatImage[]; onOpenImage: OpenImage }) {
  return (
    <div className="grid max-w-xs grid-cols-2 justify-end gap-2">
      {items.map(item => {
        const src = imageUrl(item.imageUrl);
        if (!src) return null;
        return (
          <button type="button" key={item.imageUrl} onClick={() => onOpenImage(src)} className="overflow-hidden rounded-2xl border border-white/80 bg-white/50 shadow-sm">
            <AuthImage src={src} alt={item.name ?? '图片附件'} title={item.name} className="h-32 w-32 object-cover" />
          </button>
        );
      })}
    </div>
  );
}

function MarkdownMessage({ content }: { content: string }) {
  return (
    <div className="markdown-body text-[16px] font-medium leading-relaxed text-gray-800">
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

function AgentAvatar() {
  return (
    <div className="flex h-10 w-10 shrink-0 items-center justify-center rounded-2xl border border-white bg-gradient-to-tr from-blue-50 to-purple-50 shadow-sm">
      <Sparkles size={18} className="text-blue-600" />
    </div>
  );
}

function BubbleMeta({ align = 'left', createdAt, durationMs }: { align?: 'left' | 'right'; createdAt?: unknown; durationMs?: unknown }) {
  const time = formatMessageTime(createdAt);
  const duration = formatDuration(durationMs);
  if (!time && !duration) return null;
  return (
    <div className={`mt-1 text-[11px] text-gray-400 ${align === 'right' ? 'text-right' : ''}`}>
      {time}{duration ? ` · ${duration}` : ''}
    </div>
  );
}

function ThinkingRow({ startedAt, now }: { startedAt: number; now: number }) {
  return (
    <div className="flex justify-start pl-[4.5rem] max-sm:pl-0">
      <div className="glass-panel inline-flex items-center gap-2 rounded-full px-3 py-2 text-xs text-gray-500 shadow-sm">
        <span className="flex items-center gap-1" aria-label="回复中">
          <span className="h-1.5 w-1.5 animate-bounce rounded-full bg-blue-500 [animation-delay:-0.2s]" />
          <span className="h-1.5 w-1.5 animate-bounce rounded-full bg-blue-500 [animation-delay:-0.1s]" />
          <span className="h-1.5 w-1.5 animate-bounce rounded-full bg-blue-500" />
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

function AuthGate() {
  return (
    <div className="flex flex-1 items-center justify-center bg-[#FAFAFC]/50 p-8">
      <div className="max-w-sm rounded-[2.5rem] border border-white bg-white/70 p-8 text-center shadow-sm">
        <LogIn size={26} className="mx-auto text-gray-400" />
        <h3 className="mt-4 text-lg font-bold text-gray-900">需要登录</h3>
        <p className="mt-2 text-sm leading-6 text-gray-500">登录后会接入真实会话、设备、记忆和设置。</p>
        <button type="button" onClick={goLogin} className="mt-6 rounded-full bg-gray-900 px-5 py-2 text-sm font-semibold text-white shadow-sm transition hover:bg-black">
          去登录
        </button>
      </div>
    </div>
  );
}

function GlassMetric({ label, value, tone = 'gray' }: { label: string; value: string; tone?: 'gray' | 'emerald' }) {
  return (
    <div className="rounded-[2rem] border border-white bg-white/70 p-4 shadow-sm">
      <div className="text-xs font-semibold uppercase text-gray-400">{label}</div>
      <div className={`mt-2 text-2xl font-black ${tone === 'emerald' ? 'text-emerald-600' : 'text-gray-900'}`}>{value}</div>
    </div>
  );
}

function DeviceStat({ icon, label, value }: { icon: React.ReactNode; label: string; value: string }) {
  return (
    <div className="rounded-2xl bg-gray-50 p-4">
      <div className="mb-2 flex items-center gap-2 text-gray-400">
        {icon}
        <span className="text-xs font-bold uppercase">{label}</span>
      </div>
      <div className="truncate text-xl font-black text-gray-900">{value}</div>
    </div>
  );
}

function MemoryRow({ memory, deleting, onDelete }: { memory: MemoryFactDto; deleting: boolean; onDelete: () => void }) {
  const curated = Boolean(memory.isCurated ?? memory.curated);
  return (
    <div className="rounded-[2rem] border border-gray-100 bg-white/80 p-4">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div className="min-w-0 space-y-2">
          <div className="flex flex-wrap items-center gap-2 text-xs">
            <span className="rounded-full bg-gray-100 px-2 py-1 font-semibold uppercase text-gray-600">{kindLabel(memory.kind)}</span>
            <span className={`rounded-full px-2 py-1 font-semibold ${curated ? 'bg-emerald-50 text-emerald-700' : 'bg-amber-50 text-amber-700'}`}>
              {curated ? '已确认' : '自动提取'}
            </span>
            <span className="text-gray-400">{formatDate(memory.createdAt)}</span>
            <span className="text-gray-400">召回 {memory.accessCount ?? 0} 次</span>
          </div>
          <p className="whitespace-pre-wrap break-words text-sm leading-6 text-gray-800">{memory.content}</p>
        </div>
        <button type="button" onClick={onDelete} disabled={deleting} className="shrink-0 rounded-full bg-red-50 px-3 py-1.5 text-xs font-semibold text-red-600 transition hover:bg-red-100 disabled:opacity-50">
          {deleting ? '删除中...' : '删除'}
        </button>
      </div>
    </div>
  );
}

function InfoBlock({ title, body }: { title: string; body: string }) {
  return (
    <div className="rounded-[2rem] border border-white bg-white/70 p-4 shadow-sm">
      <div className="text-sm font-semibold text-gray-900">{title}</div>
      <p className="mt-1 text-sm leading-6 text-gray-500">{body}</p>
    </div>
  );
}

function SoftStatus({ children }: { children: React.ReactNode }) {
  return <div className="rounded-2xl border border-gray-100 bg-white/60 px-4 py-3 text-sm text-gray-500">{children}</div>;
}

function ErrorStatus({ children }: { children: React.ReactNode }) {
  return <div className="rounded-2xl border border-red-100 bg-red-50/80 px-4 py-3 text-sm font-medium text-red-700">{children}</div>;
}

function ToolResult({ tool, result, onOpenImage }: { tool: string; result: any; onOpenImage: OpenImage }) {
  const standardMedia = standardToolMedia(result);
  if (standardMedia) return <StandardToolMediaResult media={standardMedia} onOpenImage={onOpenImage} />;

  if (Array.isArray(result?.photos) && isPhotoListTool(tool)) {
    return (
      <div className="space-y-2">
        <ThumbGrid items={result.photos} onOpenImage={onOpenImage} />
        <ToolResultPageNote page={toolResultPageInfo(result, result.photos.length)} />
      </div>
    );
  }

  if (tool === 'photos.semantic_search') return <SemanticPhotoResult result={result} onOpenImage={onOpenImage} />;

  if (tool === 'photos.get_full' && hasPhotoImage(result)) {
    const { big, small } = photoImageSources(result);
    const w = result.vision_width ?? result.source_width;
    const h = result.vision_height ?? result.source_height;
    return (
      <div className="max-w-md">
        <AuthImage src={small} alt={result.name} title={result.name} onOpen={() => big && onOpenImage(big)} className="max-h-96 w-full cursor-zoom-in rounded-2xl object-contain" />
        <div className="mt-1 text-xs text-gray-500">{result.name}{w && h ? ` · ${w}×${h}` : ''}</div>
      </div>
    );
  }

  if (tool === 'photos.list_albums' && Array.isArray(result?.albums)) {
    return (
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        {result.albums.map((a: any) => {
          const { big, small: src } = photoImageSources(a);
          return (
            <div key={a.bucket_id} className="overflow-hidden rounded-2xl border border-gray-100 bg-white">
              {src ? (
                <AuthImage src={src} alt={a.name} onOpen={() => onOpenImage(big ?? src)} className="h-32 w-full cursor-zoom-in object-cover" />
              ) : (
                <div className="h-32 w-full bg-gray-100" />
              )}
              <div className="px-2 py-1 text-xs">
                <div className="truncate font-medium" title={a.name}>{a.name}</div>
                <div className="text-gray-500">{a.photo_count} 张</div>
              </div>
            </div>
          );
        })}
      </div>
    );
  }

  if (tool === 'videos.list_recent' && Array.isArray(result?.videos)) {
    if (result.videos.length === 0) {
      return <div className="rounded-2xl border border-dashed border-gray-200 bg-gray-50 px-3 py-4 text-sm text-gray-500">没查到视频。</div>;
    }
    return (
      <div className="grid grid-cols-3 gap-3 sm:grid-cols-5">
        {result.videos.map((v: any) => {
          const src = firstImageUrl(v.thumb_url, v.thumbnail_url, v.cover_image_url, v.cover_url, v.thumbUrl, v.thumbnailUrl, v.coverImageUrl, v.coverUrl) ?? imageDataUrl(v.thumb_b64);
          const sec = v.duration_ms ? Math.round(v.duration_ms / 1000) : 0;
          const m = Math.floor(sec / 60);
          const s = sec % 60;
          return (
            <div key={v.id} className="relative">
              {src ? (
                <AuthImage src={src} alt={v.name} title={v.name} onOpen={() => onOpenImage(src)} className="h-32 w-full cursor-zoom-in rounded-2xl object-cover" />
              ) : (
                <div className="h-32 w-full rounded-2xl bg-gray-100" />
              )}
              <span className="absolute bottom-1 right-1 rounded bg-black/70 px-1 text-[10px] text-white">▶ {m}:{String(s).padStart(2, '0')}</span>
            </div>
          );
        })}
      </div>
    );
  }

  return (
    <details className="text-xs text-gray-600">
      <summary className="cursor-pointer">工具 {tool} 返回结果</summary>
      <pre className="mt-1 overflow-x-auto rounded-2xl bg-gray-100 p-3">{JSON.stringify(result, null, 2)}</pre>
    </details>
  );
}

function StandardToolMediaResult({ media, onOpenImage }: { media: StandardToolMedia; onOpenImage: OpenImage }) {
  if (media.mode === 'details') {
    return (
      <details className="text-xs text-gray-600">
        <summary className="cursor-pointer">{media.summary}</summary>
        <pre className="mt-1 overflow-x-auto rounded-2xl bg-gray-100 p-3">{JSON.stringify(media.result, null, 2)}</pre>
      </details>
    );
  }
  if (media.mode === 'primary') return <PrimaryPhotoCard photo={media.primary} onOpenImage={onOpenImage} />;
  if (media.mode === 'collapsed') {
    return (
      <details className="text-xs text-gray-500">
        <summary className="cursor-pointer">{media.summary}</summary>
        <div className="mt-2">
          {hasAnyPhotoImage(media.items) ? <ThumbGrid items={media.items} onOpenImage={onOpenImage} /> : <SelectedPhotoSummary photos={media.items} />}
        </div>
      </details>
    );
  }
  return (
    <div className="space-y-2">
      <ThumbGrid items={media.items} onOpenImage={onOpenImage} />
      <ToolResultPageNote page={media.page} />
    </div>
  );
}

function ToolResultPageNote({ page }: { page?: ToolResultPageInfo | null }) {
  if (!page) return null;
  const range = page.count > 0 && page.start && page.end ? `第 ${page.start}-${page.end} 张` : `本页 ${page.count} 张`;
  const limitPart = page.limit ? `，每页 ${page.limit} 张` : '';
  const nextPart = page.hasMore ? `，还有更多，可继续请求 next_offset=${page.nextOffset ?? page.end ?? page.count}` : '，没有检测到下一页';
  return <div className="rounded-2xl border border-gray-100 bg-gray-50 px-3 py-2 text-xs text-gray-600">{range}{limitPart}{nextPart}</div>;
}

function PrimaryPhotoCard({ photo, onOpenImage }: { photo: any; onOpenImage: OpenImage }) {
  const { big, small } = photoImageSources(photo);
  const w = photo?.vision_width ?? photo?.source_width ?? photo?.width;
  const h = photo?.vision_height ?? photo?.source_height ?? photo?.height;
  const preview = big ?? small;
  return (
    <div className="max-w-md">
      <AuthImage src={small} alt={photo?.name} title={photo?.name} onOpen={() => preview && onOpenImage(preview)} className="max-h-96 w-full cursor-zoom-in rounded-2xl object-contain" />
      <div className="mt-1 text-xs text-gray-500">{photo?.name ?? '图片结果'}{w && h ? ` · ${w}×${h}` : ''}</div>
    </div>
  );
}

function SemanticPhotoResult({ result, onOpenImage }: { result: any; onOpenImage: OpenImage }) {
  const primary = semanticPrimaryPhoto(result);
  if (primary) return <PrimaryPhotoCard photo={primary} onOpenImage={onOpenImage} />;
  const photos = Array.isArray(result?.photos) ? result.photos : [];
  const page = toolResultPageInfo(result, photos.length);
  const displayPolicy = result?.display_policy ?? result?.display?.policy;
  const requestedLimitValue = Number(result?.requested_limit ?? result?.limit ?? result?.display?.requested_limit ?? 1);
  const requestedLimit = Number.isFinite(requestedLimitValue) && requestedLimitValue > 0 ? Math.floor(requestedLimitValue) : 1;
  const hiddenCandidates = displayPolicy === 'hidden_candidates' || result?.display === 'confirmed_only';

  if (hiddenCandidates) {
    const shown = photos.slice(0, Math.min(photos.length, requestedLimit));
    if (shown.length === 0) return <div className="text-xs italic text-gray-500">已检索 0 张候选图，未找到可展示结果</div>;
    if (!hasAnyPhotoImage(shown)) return <SelectedPhotoSummary photos={shown} />;
    return (
      <div className="space-y-1">
        <ThumbGrid items={shown} onOpenImage={onOpenImage} />
        {photos.length > shown.length && <div className="text-xs italic text-gray-500">已隐藏 {photos.length - shown.length} 张候选图</div>}
      </div>
    );
  }

  const shown = photos.slice(0, Math.min(photos.length, 3));
  const hidden = Math.max(0, photos.length - shown.length);
  return (
    <div className="space-y-2">
      <ThumbGrid items={shown} onOpenImage={onOpenImage} />
      <ToolResultPageNote page={page} />
      {hidden > 0 && (
        <details className="text-xs text-gray-500">
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
    <div className="grid grid-cols-2 gap-3 sm:grid-cols-4 xl:grid-cols-5">
      {items.map((p: any) => {
        const { big, small: src } = photoImageSources(p);
        if (!src) return <PhotoPlaceholder key={p.id ?? p.name} photo={p} />;
        return (
          <AuthImage key={p.id ?? p.name ?? src} src={src} alt={p.name} title={p.name} onOpen={() => onOpenImage(big ?? src)} className="h-32 w-full cursor-zoom-in rounded-2xl object-cover transition hover:ring-2 hover:ring-blue-300/50" />
        );
      })}
    </div>
  );
}

function SelectedPhotoSummary({ photos }: { photos: any[] }) {
  return (
    <div className="space-y-1">
      {photos.map(photo => (
        <div key={photo.id ?? photo.asset_id ?? photo.name} className="rounded-2xl border border-gray-100 bg-gray-50 px-3 py-2 text-xs text-gray-600">
          <div className="font-medium text-gray-700">已命中图片{photo.name ? `：${photo.name}` : ''}</div>
          <div className="mt-0.5">id: {photo.id ?? photo.asset_id ?? 'unknown'}{photo.date_taken_ms ? ` · ${formatPhotoDate(photo.date_taken_ms)}` : ''}</div>
        </div>
      ))}
    </div>
  );
}

function PhotoPlaceholder({ photo }: { photo: any }) {
  return <div className="flex h-32 w-full items-center justify-center rounded-2xl border border-gray-100 bg-gray-100 px-2 text-center text-xs text-gray-500">{photo?.name ?? photo?.id ?? '图片已命中'}</div>;
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
        if (!cancelled) setObjectUrl(URL.createObjectURL(blob));
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
  if (!displaySrc || failed) return <div className="h-32 w-full rounded-2xl border border-gray-100 bg-gray-100" />;
  return <img src={displaySrc} alt={alt} title={title} draggable={draggable} onClick={onOpen} className={className} />;
}

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

function buildTimelineItems(events: ChatEvent[], runActive = false): TimelineItem[] {
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
      segment.forEach((event, offset) => items.push({ kind: 'event', event, index: segmentStart + offset }));
      continue;
    }
    const finalAssistantOffset = finalAssistantIndex(segment, runActive);
    if (finalAssistantOffset < 0) {
      items.push({ kind: 'process', events: segment, startIndex: segmentStart, endIndex: segmentStart + segment.length - 1 });
      pushVisibleToolResults(items, segment, segmentStart);
      continue;
    }
    const processEvents = segment.slice(0, finalAssistantOffset);
    if (processEvents.length > 0) {
      items.push({ kind: 'process', events: processEvents, startIndex: segmentStart, endIndex: segmentStart + finalAssistantOffset - 1 });
    }
    pushVisibleToolResults(items, processEvents, segmentStart);
    items.push({
      kind: 'event',
      event: finalAssistantDisplayEvent(segment[finalAssistantOffset], processEvents),
      index: segmentStart + finalAssistantOffset
    });
    const trailing = segment.slice(finalAssistantOffset + 1);
    if (trailing.length > 0) {
      if (trailing.some(isToolActivityEvent)) {
        items.push({ kind: 'process', events: trailing, startIndex: segmentStart + finalAssistantOffset + 1, endIndex: segmentStart + segment.length - 1 });
        pushVisibleToolResults(items, trailing, segmentStart + finalAssistantOffset + 1);
      } else {
        trailing.forEach((event, offset) => items.push({ kind: 'event', event, index: segmentStart + finalAssistantOffset + 1 + offset }));
      }
    }
  }
  return items;
}

function pushVisibleToolResults(items: TimelineItem[], events: ChatEvent[], startIndex: number) {
  events.forEach((event, offset) => {
    if (shouldShowToolResultOutsideProcess(event)) items.push({ kind: 'media_result', event, index: startIndex + offset });
  });
}

function isToolActivityEvent(ev: ChatEvent | undefined) {
  return ev?.type === 'tool_call_started' || ev?.type === 'tool_call_result';
}

function finalAssistantDisplayEvent(event: ChatEvent, processEvents: ChatEvent[]) {
  if (event.type !== 'assistant_message' || !processEvents.some(isToolActivityEvent)) return event;
  const content = String(event.data?.content ?? '');
  const cleaned = stripProcessLeadIn(content, processEvents);
  if (cleaned === content) return event;
  return { ...event, data: { ...event.data, content: cleaned } };
}

function stripProcessLeadIn(content: string, processEvents: ChatEvent[]) {
  let cleaned = content.trim();
  const notes = processEvents
    .filter(ev => ev.type === 'assistant_message')
    .map(ev => String(ev.data?.content ?? '').trim())
    .filter(Boolean);
  notes.forEach(note => {
    if (cleaned.startsWith(note)) cleaned = cleaned.slice(note.length).trimStart();
  });
  if (cleaned !== content.trim()) return cleaned;
  return stripLikelyProgressSentences(cleaned);
}

function stripLikelyProgressSentences(content: string) {
  const sentences = content.match(/[^。！？!?]+[。！？!?]?/g)?.map(s => s.trim()).filter(Boolean) ?? [];
  if (sentences.length < 2) return content;
  let finalIndex = -1;
  for (let i = sentences.length - 1; i >= 0; i -= 1) {
    if (/^(已|已经|完成|好了|Done\b|Finished\b|Success\b|I've\b|I have\b)/i.test(sentences[i])) {
      finalIndex = i;
      break;
    }
  }
  if (finalIndex <= 0) return content;
  const lead = sentences.slice(0, finalIndex);
  if (!lead.some(sentence => /(我先|我会|先确认|确认|检查|调用|最近任务|划掉|准备|正在|接下来|I'll|I will|Let me|I'm going)/i.test(sentence))) {
    return content;
  }
  return sentences.slice(finalIndex).join('').trim();
}

function finalAssistantIndex(events: ChatEvent[], runActive = false) {
  let lastToolOffset = -1;
  let lastAssistantOffset = -1;
  let lastVisibleToolResultOffset = -1;
  events.forEach((ev, index) => {
    if (isToolActivityEvent(ev)) lastToolOffset = index;
    if (shouldShowToolResultOutsideProcess(ev)) lastVisibleToolResultOffset = index;
    if (ev.type === 'assistant_message') lastAssistantOffset = index;
  });
  if (lastAssistantOffset <= lastToolOffset) return -1;
  if (!runActive) return lastAssistantOffset;
  return lastVisibleToolResultOffset >= 0 && lastAssistantOffset > lastVisibleToolResultOffset ? lastAssistantOffset : -1;
}

function messageToEvent(m: MessageDto): ChatEvent | null {
  const metadata = asRecord(m.metadata);
  const base = {
    content: m.content,
    attachments: normalizeMessageAttachments(metadata?.attachments),
    createdAt: m.createdAt,
    durationMs: numberOrNull(metadata?.durationMs)
  };
  if (m.role === 'USER') return { type: 'user_message', data: base };
  if (m.role === 'ASSISTANT') return { type: 'assistant_message', data: base };
  if (m.role === 'TOOL_CALL') {
    return {
      type: 'tool_call_started',
      data: { deviceId: metadata?.deviceId, tool: metadata?.tool ?? 'tool', args: metadata?.args ?? {}, createdAt: m.createdAt }
    };
  }
  if (m.role === 'TOOL_RESULT') {
    return {
      type: 'tool_call_result',
      data: { tool: metadata?.tool ?? 'tool', result: metadata?.result ?? m.content, createdAt: m.createdAt }
    };
  }
  return null;
}

function normalizeMessageAttachments(value: unknown): UploadedChatImage[] {
  if (!Array.isArray(value)) return [];
  const out: UploadedChatImage[] = [];
  value.forEach(item => {
    const r = asRecord(item);
    if (!r) return;
    const imageUrlValue = typeof r.imageUrl === 'string' ? r.imageUrl : typeof r.image_url === 'string' ? r.image_url : null;
    if (!imageUrlValue) return;
    out.push({
      type: 'image',
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

function markLatestAssistantDuration(events: ChatEvent[], durationMs: number) {
  if (!Number.isFinite(durationMs) || durationMs < 0) return events;
  const copy = [...events];
  for (let i = copy.length - 1; i >= 0; i -= 1) {
    const ev = copy[i];
    if (ev.type === 'assistant_message') {
      copy[i] = { ...ev, data: { ...ev.data, durationMs } };
      return copy;
    }
  }
  return events;
}

function appendCancelEvent(events: ChatEvent[], sentEventsIndex: number) {
  if (sentEventsIndex < 0 || events.length <= sentEventsIndex) return events;
  const last = events[events.length - 1];
  if (last?.type === 'error' && last.data?.code === 'client_cancelled') return events;
  return [...events, { type: 'error', data: { code: 'client_cancelled', message: '已取消本次执行', createdAt: new Date().toISOString() } }];
}

function preferredSessionEvents(cached: ChatEvent[] | undefined, loaded: ChatEvent[]) {
  if (!cached || cached.length === 0) return loaded;
  if (isStrictlyRicherSessionHistory(loaded, cached)) return loaded;
  if (hasRicherToolHistory(cached, loaded)) return cached;
  return loaded.length >= cached.length ? loaded : cached;
}

function isStrictlyRicherSessionHistory(candidate: ChatEvent[], current: ChatEvent[]) {
  if (candidate.length < current.length) return false;
  if (countEventsOfType(candidate, 'user_message') < countEventsOfType(current, 'user_message')) return false;
  if (countEventsOfType(candidate, 'tool_call_result') > countEventsOfType(current, 'tool_call_result')) return true;
  if (countEventsOfType(candidate, 'assistant_message') > countEventsOfType(current, 'assistant_message')) return true;
  const candidateRenderable = candidate.some(ev => ev.type === 'tool_call_result' && isRenderableToolResult(ev));
  const currentRenderable = current.some(ev => ev.type === 'tool_call_result' && isRenderableToolResult(ev));
  return candidateRenderable && !currentRenderable;
}

function countEventsOfType(events: ChatEvent[], type: string) {
  return events.filter(ev => ev.type === type).length;
}

function latestTurnIsComplete(events: ChatEvent[]) {
  let lastUserIndex = -1;
  events.forEach((ev, index) => {
    if (ev.type === 'user_message') lastUserIndex = index;
  });
  if (lastUserIndex < 0) return true;
  return events.slice(lastUserIndex + 1).some(ev => ev.type === 'assistant_message');
}

function delay(ms: number) {
  return new Promise(resolve => window.setTimeout(resolve, ms));
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
  return isRenderableToolResult(ev);
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

function toolProcessSteps(item: Extract<TimelineItem, { kind: 'process' }>, active: boolean): ToolStep[] {
  return item.events.map((ev, index) => {
    if (ev.type === 'tool_call_started') {
      const result = findResultForToolCall(item.events, index);
      return {
        time: formatMessageTime(ev.data?.createdAt) || `${index}`,
        text: `${result ? '完成' : '执行'} ${ev.data?.tool ?? 'tool'}${ev.data?.args ? ` ${compactJson(ev.data.args)}` : ''}`,
        status: result ? hasToolError(result.data?.result) ? 'failed' : 'success' : active ? 'running' : 'pending',
        isCode: true
      };
    }
    if (ev.type === 'tool_call_result') {
      return {
        time: formatMessageTime(ev.data?.createdAt) || `${index}`,
        text: hasToolError(ev.data?.result) ? `结果异常 ${compactJson(ev.data?.result)}` : `返回 ${visibleToolResultCount(ev) ?? '工具'} 结果`,
        status: hasToolError(ev.data?.result) ? 'failed' : 'success'
      };
    }
    if (ev.type === 'assistant_message') {
      return {
        time: formatMessageTime(ev.data?.createdAt) || `${index}`,
        text: String(ev.data?.content ?? '').replace(/\s+/g, ' ').slice(0, 180),
        status: 'success'
      };
    }
    return { time: `${index}`, text: ev.type, status: 'pending' };
  });
}

function processStatus(item: Extract<TimelineItem, { kind: 'process' }>) {
  const hasPendingCall = item.events.some((ev, index) => ev.type === 'tool_call_started' && !findResultForToolCall(item.events, index));
  const hasError = item.events.some(ev => ev.type === 'tool_call_result' && hasToolError(ev.data?.result));
  if (hasError) return 'failed';
  if (hasPendingCall) return 'running';
  return 'succeeded';
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
    if (Number.isFinite(end) && end >= start) return formatDuration(end - start);
  }
  return '';
}

function processSummary(item: Extract<TimelineItem, { kind: 'process' }>) {
  const notes = item.events.filter(ev => ev.type === 'assistant_message').map(ev => String(ev.data?.content ?? '').trim()).filter(Boolean);
  const lastNote = notes[notes.length - 1];
  if (lastNote) return lastNote.replace(/\s+/g, ' ');
  const names = processToolNames(item);
  if (names.length === 0) return '';
  return names.length === 1 ? names[0] : `${names[0]} 等 ${names.length} 种工具`;
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
        data: { tool, result: { error: { message: ev.data?.message ?? 'tool failed' } }, createdAt: ev.data?.createdAt }
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

function mergeDeviceRows(devices: DeviceDto[], onlineStatus: DeviceOnlineStatusDto[]): DeviceRow[] {
  const statusByDeviceId = new Map(onlineStatus.map(s => [s.deviceId, s]));
  return devices.map(device => {
    const live = statusByDeviceId.get(device.id);
    const lastSeenAt = live?.connectedAt ?? device.lastSeenAt;
    return {
      ...device,
      online: live?.online === true,
      connectedAt: live?.connectedAt,
      lastSeenAt,
      toolCount: live?.toolCount ?? 0
    };
  });
}

function sessionPreviewEntries(events: ChatEvent[]): SessionPreviewEntry[] {
  return events
    .slice(-8)
    .map((event, index) => sessionPreviewEntry(event, index))
    .filter((entry): entry is SessionPreviewEntry => entry !== null)
    .slice(-5);
}

function sessionPreviewEntry(event: ChatEvent, index: number): SessionPreviewEntry | null {
  const createdAt = formatMessageTime(event.data?.createdAt);
  if (event.type === 'user_message') {
    const content = previewText(event.data?.content) || attachmentPreviewText(event.data?.attachments) || '用户消息';
    return {
      key: `user-${index}`,
      label: '用户',
      content,
      meta: createdAt,
      tone: 'user'
    };
  }
  if (event.type === 'assistant_message') {
    return {
      key: `assistant-${index}`,
      label: '助手',
      content: previewText(event.data?.content) || '助手回复',
      meta: [createdAt, formatDuration(event.data?.durationMs)].filter(Boolean).join(' · '),
      tone: 'assistant'
    };
  }
  if (event.type === 'tool_call_started') {
    return {
      key: `tool-start-${index}`,
      label: '调用',
      content: String(event.data?.tool ?? 'tool'),
      meta: event.data?.args ? compactJson(event.data.args) : createdAt,
      tone: 'tool'
    };
  }
  if (event.type === 'tool_call_result') {
    const count = visibleToolResultCount(event);
    return {
      key: `tool-result-${index}`,
      label: '结果',
      content: `${event.data?.tool ?? 'tool'}${count ? ` · ${count} 项` : ''}`,
      meta: hasToolError(event.data?.result) ? '返回异常' : createdAt,
      tone: hasToolError(event.data?.result) ? 'error' : 'tool'
    };
  }
  if (event.type === 'error') {
    return {
      key: `error-${index}`,
      label: '错误',
      content: previewText(event.data?.message) || '未知错误',
      meta: createdAt,
      tone: 'error'
    };
  }
  return null;
}

function previewText(value: unknown) {
  if (typeof value !== 'string') return '';
  return value.replace(/\s+/g, ' ').trim();
}

function attachmentPreviewText(value: unknown) {
  const attachments = normalizeMessageAttachments(value);
  if (attachments.length === 0) return '';
  return `${attachments.length} 张图片`;
}

function canvasPhotoItems(tool: string, result: unknown): any[] {
  const standard = standardToolMedia(result);
  if (standard?.mode === 'primary') return [standard.primary];
  if (standard?.mode === 'grid' || standard?.mode === 'collapsed') return standard.items.filter(hasPhotoImage);
  if (tool === 'photos.semantic_search' && semanticPrimaryPhoto(result)) return [semanticPrimaryPhoto(result)];
  if (tool === 'photos.get_full' && hasPhotoImage(result)) return [result];
  const r = asRecord(result);
  if (Array.isArray(r?.photos)) return r.photos.filter(hasPhotoImage);
  if (Array.isArray(r?.items)) return r.items.filter(hasPhotoImage);
  return [];
}

function visibleToolResultCount(ev: ChatEvent): number | null {
  const result = ev.data?.result;
  const media = standardToolMedia(result);
  if (media?.mode === 'primary') return 1;
  if (media?.mode === 'grid' || media?.mode === 'collapsed') return media.items.length;
  const tool = ev.data?.tool;
  if (tool === 'photos.get_full' && hasPhotoImage(result)) return 1;
  if ((isPhotoListTool(tool) || tool === 'photos.semantic_search') && Array.isArray(result?.photos)) return result.photos.length;
  if (tool === 'videos.list_recent' && Array.isArray(result?.videos)) return result.videos.length;
  const items = toolResultItems(result);
  return items.length > 0 ? items.length : null;
}

function isPhotoListTool(tool: unknown) {
  return tool === 'photos.list_recent' || tool === 'photos.list_by_album' || tool === 'photos.recent_screenshots';
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
  if (policy === 'show_primary' || policy === 'show_grid' || policy === 'collapsed_candidates') return hasDisplayableToolMedia(result);
  if (isStandardHiddenToolResult(result)) return false;
  const type = toolResultType(result);
  return (type === 'confirmed' || type === 'results') && !asRecord(result)?.candidate_only && hasDisplayableToolMedia(result);
}

function hasDisplayableToolMedia(result: unknown) {
  return Boolean(primaryToolImage(result)) || hasAnyPhotoImage(toolResultItems(result)) || hasAnyPhotoImage(asRecord(result)?.photos);
}

function standardToolMedia(result: unknown): StandardToolMedia | null {
  if (isStandardHiddenToolResult(result)) return null;
  const policy = toolDisplayPolicy(result);
  const candidateOnly = Boolean(asRecord(result)?.candidate_only);
  const primary = primaryToolImage(result);
  const items = toolResultItems(result).filter(item => asRecord(item));
  const imageItems = items.filter(item => hasPhotoImage(item));
  if ((policy === 'show_grid' || (policy === 'show_primary' && imageItems.length > 1)) && imageItems.length > 0) {
    return { mode: 'grid', items: imageItems, page: toolResultPageInfo(result, imageItems.length) };
  }
  if (policy === 'show_primary' && primary) return { mode: 'primary', primary };
  if (policy === 'collapsed_candidates' || candidateOnly) {
    if (items.length > 0) return { mode: 'collapsed', items, summary: toolResultSummary(result) ?? `已检索 ${items.length} 个候选结果` };
    if (toolResultSummary(result)) return { mode: 'details', result, summary: toolResultSummary(result) ?? '工具结果' };
    return null;
  }
  if (primary && isStandardDisplayableToolResult(result)) return { mode: 'primary', primary };
  if (hasAnyPhotoImage(items) && isStandardDisplayableToolResult(result)) return { mode: 'grid', items, page: toolResultPageInfo(result, items.length) };
  return null;
}

function toolResultPageInfo(result: unknown, fallbackCount: number): ToolResultPageInfo | null {
  const r = asRecord(result);
  if (!r) return null;
  const pagination = asRecord(r.pagination);
  const display = asRecord(r.display);
  const offset = numberOrUndefined(pagination?.offset ?? r.offset);
  const limit = numberOrUndefined(pagination?.limit ?? r.limit ?? display?.limit);
  const returnedCount = numberOrUndefined(pagination?.returned_count ?? pagination?.returnedCount ?? r.count) ?? fallbackCount;
  const hasMoreValue = pagination?.has_more ?? pagination?.hasMore ?? r.has_more ?? r.hasMore ?? display?.has_more ?? display?.hasMore;
  const nextOffset = numberOrUndefined(pagination?.next_offset ?? pagination?.nextOffset ?? r.next_offset ?? r.nextOffset ?? display?.next_offset ?? display?.nextOffset);
  const start = numberOrUndefined(pagination?.start_index ?? pagination?.startIndex) ?? (typeof offset === 'number' && returnedCount > 0 ? offset + 1 : undefined);
  const end = numberOrUndefined(pagination?.end_index ?? pagination?.endIndex) ?? (typeof offset === 'number' && returnedCount > 0 ? offset + returnedCount : undefined);
  const hasMore = hasMoreValue === true || hasMoreValue === 'true' || typeof nextOffset === 'number';
  const hasPageFields = typeof offset === 'number' || typeof limit === 'number' || hasMore || typeof start === 'number' || typeof end === 'number';
  if (!hasPageFields) return null;
  return { start, end, count: returnedCount, limit, offset, hasMore, nextOffset };
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
  const values = [r?.display_media, r?.displayMedia, r?.items, r?.photos, r?.results, r?.candidates];
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

function semanticPrimaryPhoto(result: any) {
  const media = displayMediaItems(result);
  const firstMedia = media.find(item => hasPhotoImage(item));
  if (firstMedia) return firstMedia;
  const primary = result?.primary_image ?? result?.primaryImage ?? result?.primary;
  const candidate = primary?.result ?? primary;
  return hasPhotoImage(candidate) ? candidate : null;
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
  const bigB64 = imageDataUrl(p.full_b64 ?? p.image_b64 ?? p.vision_b64 ?? p.image_base64 ?? p.cover_image_b64 ?? p.cover_b64);
  const bigUrl = firstImageUrl(p.full_url, p.image_url, p.asset_url, p.url, p.cover_image_url, p.cover_url, p.imageUrl, p.assetUrl, uploadUrl, p.coverImageUrl, p.coverUrl);
  const smallUrl = firstImageUrl(p.preview_url, p.thumb_url, p.thumbnail_url, p.cover_thumb_url, p.cover_thumbnail_url, p.thumbUrl, p.thumbnailUrl, p.coverThumbUrl, p.coverThumbnailUrl);
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
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(clean)) return null;
  return `/api/uploads/photos/${clean}`;
}

function needsAuthenticatedFetch(src: string) {
  return src.startsWith('/api/uploads/');
}

function asRecord(value: unknown): Record<string, any> | null {
  return value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, any> : null;
}

function numberOrNull(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

function numberOrUndefined(value: unknown): number | undefined {
  const n = typeof value === 'number' ? value : Number(value);
  return Number.isFinite(n) ? n : undefined;
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
  return date.toLocaleString(undefined, { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
}

function formatMessageTime(value: unknown) {
  const date = typeof value === 'string' ? new Date(value) : null;
  if (!date || Number.isNaN(date.getTime())) return '';
  return date.toLocaleTimeString(undefined, { hour: '2-digit', minute: '2-digit' });
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

function clampZoom(value: number) {
  return Math.min(5, Math.max(0.25, Number(value.toFixed(2))));
}

function formatPhotoDate(value: unknown) {
  const millis = typeof value === 'number' ? value : Number(value);
  if (!Number.isFinite(millis) || millis <= 0) return '';
  const date = new Date(millis);
  if (Number.isNaN(date.getTime())) return '';
  return date.toLocaleString(undefined, { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' });
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

function compactJson(value: unknown) {
  try {
    const text = JSON.stringify(value);
    return text.length > 180 ? `${text.slice(0, 177)}...` : text;
  } catch {
    return String(value);
  }
}

function toolResultTitle(tool: string) {
  if (isPhotoListTool(tool) || tool === 'photos.semantic_search' || tool === 'photos.get_full') return '图片结果';
  if (tool === 'videos.list_recent') return '视频结果';
  if (tool === 'photos.list_albums') return '相册结果';
  return '工具结果';
}

function kindLabel(kind: string) {
  switch (kind) {
    case 'preference':
      return '偏好';
    case 'rule':
      return '规则';
    case 'lesson':
      return '经验';
    case 'fact':
      return '事实';
    default:
      return kind || '记忆';
  }
}

function formatDate(value?: string | null) {
  if (!value) return '未知时间';
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '未知时间';
  return date.toLocaleString();
}

function goLogin() {
  window.location.assign('/login');
}
