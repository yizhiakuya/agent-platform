import { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import type {
  DragEvent,
  KeyboardEvent,
  MouseEvent as ReactMouseEvent,
  PointerEvent as ReactPointerEvent,
  ReactNode
} from 'react';
import {
  Check,
  CheckSquare,
  ChevronRight,
  Eye,
  FileText,
  Folder,
  Image as ImageIcon,
  Images,
  Link2,
  Loader2,
  Maximize2,
  RotateCcw,
  Square,
  Trash2,
  Undo2,
  Video
} from 'lucide-react';
import { getToken } from '../lib/auth';

type OpenImageOptions = {
  replaceFrom?: string | null;
  width?: number | null;
  height?: number | null;
  aspectRatio?: number | null;
  mediaType?: string | null;
};
type OpenImage = (src: string, options?: OpenImageOptions) => void;
type ReferenceMedia = (items: any[]) => void;
type BrowseGallery = (args: any) => Promise<any>;
type OpenOriginalMediaResult = string | ({ src?: string | null } & OpenImageOptions) | null;
type OpenOriginalMedia = (item: any, fallbackSrc?: string | null) => Promise<OpenOriginalMediaResult>;
type TrashMedia = (items: any[]) => Promise<any>;
type RestoreMedia = (items: any[]) => Promise<any>;

const FAST_BROWSE_LIMIT = 20;
const FAST_BROWSE_MAX_DIM = 256;
const VIDEO_OPEN_THUMB_MAX_DIM = 640;
const SELECTION_EDGE_GUTTER_PX = 32;
const SELECTION_HIT_SLOP_PX = 8;
const SELECTION_TILE_EDGE_PX = 24;
const MASONRY_TILE_WIDTH_PX = 132;
const MASONRY_ROW_HEIGHT_PX = 8;
const MASONRY_GAP_PX = 12;
const MEDIA_GALLERY_DRAG_TYPE = 'application/x-agent-platform-media-gallery';

export function MediaGalleryResult({
  result,
  onOpenImage,
  onReferenceMedia,
  onBrowseGallery,
  onOpenOriginalMedia,
  onTrashMedia,
  onRestoreMedia,
  variant = 'default'
}: {
  result: any;
  onOpenImage: OpenImage;
  onReferenceMedia?: ReferenceMedia;
  onBrowseGallery?: BrowseGallery;
  onOpenOriginalMedia?: OpenOriginalMedia;
  onTrashMedia?: TrashMedia;
  onRestoreMedia?: RestoreMedia;
  variant?: 'default' | 'soft';
}) {
  const [activeResult, setActiveResult] = useState<any>(result);
  const [history, setHistory] = useState<any[]>([]);
  const [loadingKey, setLoadingKey] = useState<string | null>(null);
  const loadingKeyRef = useRef<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const resultIdentity = mediaGalleryResultIdentity(result);

  useEffect(() => {
    setActiveResult(result);
    setHistory([]);
    setLoadingKey(null);
    setError(null);
    setNotice(null);
  }, [resultIdentity]);

  const sections = Array.isArray(activeResult?.sections) ? activeResult.sections : [];
  const items = Array.isArray(activeResult?.items) ? activeResult.items : [];

  async function browse(args: any, key: string, pushHistory = true) {
    if (!args || !onBrowseGallery || loadingKeyRef.current) return;
    loadingKeyRef.current = key;
    setLoadingKey(key);
    setError(null);
    try {
      const next = unwrapToolValue(await onBrowseGallery(fastBrowseArgs(args)));
      if (pushHistory) setHistory(prev => [...prev, activeResult]);
      setActiveResult(next);
      setNotice(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      loadingKeyRef.current = null;
      setLoadingKey(null);
    }
  }

  async function loadMore(args: any, key: string) {
    if (!args || !onBrowseGallery || loadingKeyRef.current) return;
    loadingKeyRef.current = key;
    setLoadingKey(key);
    setError(null);
    try {
      const next = unwrapToolValue(await onBrowseGallery(fastBrowseArgs(args)));
      setActiveResult((prev: any) => appendMediaResult(prev, next));
      setNotice(null);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      loadingKeyRef.current = null;
      setLoadingKey(null);
    }
  }

  function goBack() {
    setHistory(prev => {
      if (prev.length === 0) return prev;
      const next = [...prev];
      const previous = next.pop();
      setActiveResult(previous);
      setError(null);
      setLoadingKey(null);
      setNotice(null);
      return next;
    });
  }

  async function openMedia(item: any, index: number, fallbackSrc: string | null) {
    const key = `open:${mediaKey(item, index)}`;
    const mediaType = mediaTypeOf(item);
    const fallbackOptions = mediaLightboxOptions(item);
    const openSrc = mediaOpenFallbackSource(item, fallbackSrc);
    if (openSrc) onOpenImage(openSrc, fallbackOptions);
    if (mediaType === 'video' || !onOpenOriginalMedia) {
      return;
    }
    setLoadingKey(key);
    setError(null);
    try {
      const original = await onOpenOriginalMedia(item, fallbackSrc);
      const originalSrc = originalMediaSource(original);
      if (originalSrc) {
        onOpenImage(originalSrc, {
          ...(fallbackOptions ?? mediaLightboxOptions(original) ?? {}),
          replaceFrom: fallbackSrc
        });
      } else if (!fallbackSrc) {
        setError('没有可预览的图片。');
      }
    } catch (e) {
      const message = e instanceof Error ? e.message : String(e);
      setError(`原图加载失败${fallbackSrc ? '，已打开缩略图' : ''}：${message}`);
    } finally {
      setLoadingKey(null);
    }
  }

  async function trashMediaItems(itemsToTrash: any[]) {
    if (!onTrashMedia || itemsToTrash.length === 0) return;
    setLoadingKey(TRASH_SELECTED_KEY);
    setError(null);
    setNotice(null);
    try {
      const result = unwrapToolValue(await onTrashMedia(itemsToTrash));
      if (result?.ok === false || result?.error || result?.error_detail) {
        throw new Error(toolErrorMessage(result, '移到回收站失败'));
      }
      const affected = numberOrUndefined(result?.affected_count ?? result?.affectedCount ?? result?.summary?.affected_count);
      if (!affected || affected <= 0) {
        throw new Error('设备没有确认任何媒体被移到回收站。');
      }
      if (affected < itemsToTrash.length) {
        throw new Error(`设备只确认 ${affected} / ${itemsToTrash.length} 项被移到回收站，当前列表不会自动移除。`);
      }
      removeMediaItems(itemsToTrash);
      setNotice(`已移到回收站 ${affected} 项。`);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      throw e;
    } finally {
      setLoadingKey(null);
    }
  }

  async function restoreMediaItems(itemsToRestore: any[]) {
    if (!onRestoreMedia || itemsToRestore.length === 0) return;
    setLoadingKey(RESTORE_SELECTED_KEY);
    setError(null);
    setNotice(null);
    try {
      const result = unwrapToolValue(await onRestoreMedia(itemsToRestore));
      if (result?.ok === false || result?.error || result?.error_detail) {
        throw new Error(toolErrorMessage(result, '恢复失败'));
      }
      const affected = numberOrUndefined(result?.affected_count ?? result?.affectedCount ?? result?.summary?.affected_count);
      if (!affected || affected <= 0) {
        throw new Error('设备没有确认任何媒体被恢复。');
      }
      if (affected < itemsToRestore.length) {
        throw new Error(`设备只确认 ${affected} / ${itemsToRestore.length} 项被恢复，当前列表不会自动移除。`);
      }
      removeMediaItems(itemsToRestore);
      setNotice(`已恢复 ${affected} 项。`);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      throw e;
    } finally {
      setLoadingKey(null);
    }
  }

  function removeMediaItems(itemsToRemove: any[]) {
    const removed = new Set(itemsToRemove.map(mediaIdentity).filter(Boolean));
    if (removed.size === 0) return;
    setActiveResult((prev: any) => {
      const prevItems = Array.isArray(prev?.items) ? prev.items : [];
      const nextItems = prevItems.filter((item: any) => !removed.has(mediaIdentity(item)));
      if (nextItems.length === prevItems.length) return prev;
      const previousCount = numberOrUndefined(prev?.count) ?? prevItems.length;
      return {
        ...prev,
        items: nextItems,
        count: Math.max(0, previousCount - (prevItems.length - nextItems.length))
      };
    });
  }

  return (
    <div className={`media-gallery-result ${variant === 'soft' ? 'media-gallery-result-soft' : ''}`}>
      {history.length > 0 && (
        <div className="media-gallery-nav-panel">
          <button type="button" onClick={goBack} className="media-gallery-nav-button">
            <RotateCcw size={14} />
            返回
          </button>
          <span className="media-gallery-nav-title">
            {activeResult?.title ?? '相册'}
          </span>
        </div>
      )}

      {error && (
        <div className="media-gallery-alert media-gallery-alert-error">
          {error}
        </div>
      )}

      {notice && (
        <div className="media-gallery-alert media-gallery-alert-success">
          {notice}
        </div>
      )}

      {sections.length > 0 ? (
        <GallerySections
          sections={sections}
          onOpenImage={onOpenImage}
          onBrowseEntry={(entry, key) => browse(entry?.browse_args ?? entry?.browseArgs, key)}
          loadingKey={loadingKey}
        />
      ) : items.length > 0 ? (
        <GalleryMediaGrid
          result={activeResult}
          items={items}
          page={galleryPageInfo(activeResult, items.length)}
          onOpenImage={onOpenImage}
          onOpenMedia={openMedia}
          onReferenceMedia={onReferenceMedia}
          onTrashItems={onTrashMedia ? trashMediaItems : undefined}
          onRestoreItems={onRestoreMedia ? restoreMediaItems : undefined}
          onLoadMore={(args, key) => loadMore(args, key)}
          loadingKey={loadingKey}
        />
      ) : (
        <div className="media-gallery-empty">
          <ImageIcon size={18} />
          没查到可展示的媒体。
        </div>
      )}
    </div>
  );
}

function fastBrowseArgs(args: any) {
  if (!args || typeof args !== 'object' || Array.isArray(args)) return args;
  const view = typeof args.view === 'string' ? args.view : '';
  if (!['album', 'category', 'photos', 'timeline'].includes(view)) return args;
  return {
    ...args,
    limit: clampNumber(args.limit, FAST_BROWSE_LIMIT, 1, FAST_BROWSE_LIMIT),
    max_dim: clampNumber(args.max_dim, FAST_BROWSE_MAX_DIM, 128, FAST_BROWSE_MAX_DIM)
  };
}

function appendMediaResult(prev: any, next: any) {
  if (!next) return prev;
  if (!Array.isArray(prev?.items) || !Array.isArray(next?.items)) return next;

  const merged = [...prev.items];
  const seen = new Set<string>();
  prev.items.forEach((item: any, index: number) => {
    const key = mediaIdentity(item) || mediaKey(item, index);
    if (key) seen.add(key);
  });
  next.items.forEach((item: any, index: number) => {
    const key = mediaIdentity(item) || mediaKey(item, prev.items.length + index);
    if (key && seen.has(key)) return;
    if (key) seen.add(key);
    merged.push(item);
  });

  const count = merged.length;
  return {
    ...prev,
    ...next,
    items: merged,
    count,
    pagination: {
      ...(record(prev?.pagination) ?? {}),
      ...(record(next?.pagination) ?? {}),
      returned_count: count,
      start_index: count > 0 ? 1 : 0,
      end_index: count
    },
    display: {
      ...(record(prev?.display) ?? {}),
      ...(record(next?.display) ?? {})
    }
  };
}

function clampNumber(value: unknown, fallback: number, min: number, max: number) {
  const n = typeof value === 'number' ? value : Number(value);
  if (!Number.isFinite(n)) return fallback;
  return Math.min(max, Math.max(min, Math.floor(n)));
}

export function mediaGalleryItemCount(result: any): number | null {
  if (Array.isArray(result?.items)) return result.items.length;
  if (Array.isArray(result?.sections)) {
    return result.sections.reduce((sum: number, section: any) => {
      const entries = Array.isArray(section?.entries) ? section.entries : [];
      return sum + entries.length;
    }, 0);
  }
  return null;
}

export function buildMediaGalleryTrashArgs(items: any[]) {
  return {
    items: items.map(trashItem).filter(Boolean)
  };
}

export function buildMediaGalleryRestoreArgs(items: any[]) {
  return {
    items: items.map(trashItem).filter(Boolean)
  };
}

export function hasMediaGalleryDragItems(dataTransfer: DataTransfer | null | undefined) {
  if (!dataTransfer) return false;
  return Array.from(dataTransfer.types ?? []).includes(MEDIA_GALLERY_DRAG_TYPE);
}

export function readMediaGalleryDragItems(dataTransfer: DataTransfer | null | undefined) {
  if (!dataTransfer) return [];
  const raw = dataTransfer.getData(MEDIA_GALLERY_DRAG_TYPE);
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed?.items) ? parsed.items : [];
  } catch {
    return [];
  }
}

function GallerySections({
  sections,
  onOpenImage,
  onBrowseEntry,
  loadingKey
}: {
  sections: any[];
  onOpenImage: OpenImage;
  onBrowseEntry?: (entry: any, key: string) => void;
  loadingKey: string | null;
}) {
  return (
    <div className="media-gallery-sections">
      {sections.map((section, sectionIndex) => {
        const entries = Array.isArray(section?.entries) ? section.entries : [];
        if (entries.length === 0) return null;
        return (
          <section key={`${section?.title ?? 'section'}-${sectionIndex}`} className="media-gallery-section">
            <div className="media-gallery-section-head">
              <h3>
                {section?.title ?? '相册'}
              </h3>
              <span>
                {entries.length} 项
              </span>
            </div>
            <div className="media-gallery-entry-grid">
              {entries.map((entry: any, entryIndex: number) => {
                const key = entryKey(entry, sectionIndex, entryIndex);
                return (
                  <GalleryEntryCard
                    key={key}
                    entry={entry}
                    onOpenImage={onOpenImage}
                    onBrowse={() => onBrowseEntry?.(entry, key)}
                    loading={loadingKey === key}
                  />
                );
              })}
            </div>
          </section>
        );
      })}
    </div>
  );
}

function GalleryEntryCard({
  entry,
  onOpenImage,
  onBrowse,
  loading
}: {
  entry: any;
  onOpenImage: OpenImage;
  onBrowse?: () => void;
  loading: boolean;
}) {
  const { big, small } = mediaImageSources(entry);
  const count = numberOrUndefined(entry?.count ?? entry?.photo_count);
  const title = entry?.title ?? entry?.name ?? '相册入口';
  const browseArgs = entry?.browse_args ?? entry?.browseArgs;
  const canBrowse = Boolean(browseArgs && onBrowse);

  return (
    <button
      type="button"
      onClick={onBrowse}
      disabled={!canBrowse || loading}
      className="media-gallery-entry-card"
      aria-label={`打开${title}`}
    >
      <div className="media-gallery-entry-cover">
        {small ? (
          <AuthMediaImage
            src={small}
            alt={title}
            title={title}
            className="media-gallery-image"
          />
        ) : (
          <div className="media-gallery-entry-placeholder">
            {entryIcon(entry, 24)}
          </div>
        )}
        {big && (
          <span
            role="button"
            tabIndex={0}
            onClick={ev => {
              ev.stopPropagation();
              onOpenImage(big);
            }}
            onKeyDown={ev => {
              if (ev.key === 'Enter' || ev.key === ' ') {
                ev.preventDefault();
                ev.stopPropagation();
                onOpenImage(big);
              }
            }}
            className="media-gallery-entry-preview"
            aria-label="预览封面"
          >
            <Maximize2 size={15} />
          </span>
        )}
      </div>
      <div className="media-gallery-entry-body">
        <div className="media-gallery-entry-icon">{entryIcon(entry, 16)}</div>
        <div className="media-gallery-entry-text">
          <div className="media-gallery-entry-title" title={title}>
            {title}
          </div>
          <div className="media-gallery-entry-meta">
            {typeof count === 'number' ? `${count} 项` : entry?.category ?? entry?.entry_type ?? '入口'}
          </div>
        </div>
        {loading ? (
          <Loader2 size={16} className="media-gallery-entry-spin" />
        ) : (
          <ChevronRight size={16} className="media-gallery-entry-chevron" />
        )}
      </div>
    </button>
  );
}

function GalleryMediaGrid({
  result,
  items,
  page,
  onOpenImage,
  onOpenMedia,
  onReferenceMedia,
  onTrashItems,
  onRestoreItems,
  onLoadMore,
  loadingKey
}: {
  result: any;
  items: any[];
  page: GalleryPageInfo | null;
  onOpenImage: OpenImage;
  onOpenMedia?: (item: any, index: number, fallbackSrc: string | null) => Promise<void>;
  onReferenceMedia?: ReferenceMedia;
  onTrashItems?: (items: any[]) => Promise<void>;
  onRestoreItems?: (items: any[]) => Promise<void>;
  onLoadMore?: (args: any, key: string) => void;
  loadingKey: string | null;
}) {
  const loadMoreRef = useRef<HTMLDivElement | null>(null);
  const surfaceRef = useRef<HTMLDivElement | null>(null);
  const tileRefs = useRef<Map<string, HTMLDivElement>>(new Map());
  const selectionDragRef = useRef<SelectionDragState | null>(null);
  const selectionRefreshRafRef = useRef<number | null>(null);
  const [selected, setSelected] = useState<Set<string>>(() => new Set());
  const selectedRef = useRef<Set<string>>(selected);
  const [contextMenu, setContextMenu] = useState<GalleryContextMenu | null>(null);
  const [selectionBox, setSelectionBox] = useState<SelectionBox | null>(null);
  const canDragReference = Boolean(onReferenceMedia);
  const displayEntries = mediaDisplayEntries(items);
  const selectedItems = items.filter((item, index) => selected.has(mediaKey(item, index)));
  const restoreMode = isRecentDeletedResult(result);
  const canMutate = restoreMode ? Boolean(onRestoreItems) : Boolean(onTrashItems);
  const mutationLabel = restoreMode ? '恢复' : '移到回收站';

  const nextKey = nextPageKey(result);

  useEffect(() => {
    selectedRef.current = selected;
  }, [selected]);

  useEffect(() => {
    setSelected(prev => {
      const valid = new Set(items.map(mediaKey));
      const next = new Set([...prev].filter(key => valid.has(key)));
      return next.size === prev.size ? prev : next;
    });
    if (selectionDragRef.current) scheduleSelectionRefresh();
  }, [items]);

  useEffect(() => {
    if (!page?.hasMore || !page.nextArgs || !onLoadMore || loadingKey === nextKey) return;
    const node = loadMoreRef.current;
    if (!node) return;
    const observer = new IntersectionObserver(entries => {
      if (entries.some(entry => entry.isIntersecting)) {
        onLoadMore(page.nextArgs, nextKey);
      }
    }, { rootMargin: '720px 0px 720px 0px' });
    observer.observe(node);
    return () => observer.disconnect();
  }, [page?.hasMore, page?.nextOffset, page?.nextArgs, onLoadMore, loadingKey, nextKey]);

  useEffect(() => {
    if (!contextMenu) return;
    function closeIfOutside(ev: globalThis.PointerEvent) {
      if (ev.button !== 0) return;
      const target = ev.target as HTMLElement | null;
      if (target?.closest('[data-testid="media-gallery-context-menu"]')) return;
      setContextMenu(null);
    }
    function handleKey(ev: globalThis.KeyboardEvent) {
      if (ev.key === 'Escape') setContextMenu(null);
    }
    document.addEventListener('pointerdown', closeIfOutside);
    document.addEventListener('keydown', handleKey);
    return () => {
      document.removeEventListener('pointerdown', closeIfOutside);
      document.removeEventListener('keydown', handleKey);
    };
  }, [contextMenu]);

  useEffect(() => {
    function refreshActiveSelection() {
      if (selectionDragRef.current) scheduleSelectionRefresh();
    }
    window.addEventListener('scroll', refreshActiveSelection, true);
    window.addEventListener('resize', refreshActiveSelection);
    return () => {
      window.removeEventListener('scroll', refreshActiveSelection, true);
      window.removeEventListener('resize', refreshActiveSelection);
      if (selectionRefreshRafRef.current !== null) {
        window.cancelAnimationFrame(selectionRefreshRafRef.current);
        selectionRefreshRafRef.current = null;
      }
    };
  }, []);

  function setTileRef(key: string, node: HTMLDivElement | null) {
    if (node) tileRefs.current.set(key, node);
    else tileRefs.current.delete(key);
  }

  function openItem(item: any, index: number) {
    const { big, small } = mediaImageSources(item);
    const fallback = big ?? small;
    if (onOpenMedia) void onOpenMedia(item, index, fallback ?? null);
    else if (fallback) onOpenImage(fallback);
  }

  function menuTargetItems() {
    if (!contextMenu) return [];
    if (!contextMenu.item) return selectedItems;
    const key = mediaKey(contextMenu.item, contextMenu.index);
    if (selected.has(key) && selectedItems.length > 1) return selectedItems;
    return [contextMenu.item];
  }

  function openContextMenuAt(x: number, y: number, item?: any, index = -1) {
    setContextMenu({ ...clampContextMenuPosition(x, y), item, index });
  }

  function toggleContextItemSelection() {
    if (!contextMenu?.item) return;
    const key = mediaKey(contextMenu.item, contextMenu.index);
    setSelected(prev => toggledSelection(prev, key));
    setContextMenu(null);
  }

  function toggleItemSelection(item: any, index: number) {
    const key = mediaKey(item, index);
    setSelected(prev => toggledSelection(prev, key));
    setContextMenu(null);
  }

  function referenceMenuTarget() {
    const target = menuTargetItems().filter(item => item?.media_type === 'photo');
    if (target.length > 0) onReferenceMedia?.(target);
    setContextMenu(null);
  }

  async function mutateMenuTarget() {
    const target = menuTargetItems();
    setContextMenu(null);
    if (!canMutate || target.length === 0) return;
    if (!window.confirm(`${mutationLabel} ${target.length} 项？`)) return;
    try {
      if (restoreMode) await onRestoreItems?.(target);
      else await onTrashItems?.(target);
    } catch {
      // The parent renders the tool error and keeps the current selection for retry.
    }
  }

  function clearSelection() {
    setSelected(new Set());
    setContextMenu(null);
  }

  function handleSurfaceContextMenuCapture(ev: ReactMouseEvent<HTMLDivElement>) {
    ev.preventDefault();
    ev.stopPropagation();
    const target = ev.target as HTMLElement;
    const tile = target.closest('[data-testid="media-gallery-tile"]') as HTMLElement | null;
    if (tile) {
      const index = Number(tile.dataset.mediaIndex);
      if (Number.isInteger(index) && items[index]) {
        openContextMenuAt(ev.clientX, ev.clientY, items[index], index);
      }
      return;
    }
    if (selected.size > 0) openContextMenuAt(ev.clientX, ev.clientY);
  }

  function handleSurfacePointerDown(ev: ReactPointerEvent<HTMLDivElement>) {
    if (ev.button !== 0) return;
    const target = ev.target as HTMLElement;
    const tile = target.closest('[data-testid="media-gallery-tile"]') as HTMLElement | null;
    if (tile) {
      const tileRect = tile.getBoundingClientRect();
      const nearEdge = ev.clientX - tileRect.left <= SELECTION_TILE_EDGE_PX || tileRect.right - ev.clientX <= SELECTION_TILE_EDGE_PX;
      if (!nearEdge) return;
    }
    const surface = surfaceRef.current;
    if (!surface) return;
    ev.preventDefault();
    setContextMenu(null);
    const startPoint = surfaceContentPoint(surface, ev.clientX, ev.clientY);
    const additive = ev.ctrlKey || ev.metaKey || ev.shiftKey;
    selectionDragRef.current = {
      startX: startPoint.x,
      startY: startPoint.y,
      currentX: startPoint.x,
      currentY: startPoint.y,
      currentClientX: ev.clientX,
      currentClientY: ev.clientY,
      base: additive ? new Set(selectedRef.current) : new Set(),
      additive,
      moved: false,
      pointerId: ev.pointerId
    };
    setSelectionBox(clipSelectionBoxToSelectionViewport({
      left: ev.clientX,
      top: ev.clientY,
      width: 0,
      height: 0
    }, surface));
    surface.setPointerCapture(ev.pointerId);
  }

  function handleSurfacePointerMove(ev: ReactPointerEvent<HTMLDivElement>) {
    const drag = selectionDragRef.current;
    if (!drag) return;
    ev.preventDefault();
    updateSelectionDrag(ev.clientX, ev.clientY);
  }

  function handleSurfacePointerUp(ev: ReactPointerEvent<HTMLDivElement>) {
    const drag = selectionDragRef.current;
    const surface = surfaceRef.current;
    if (!drag || !surface) return;
    if (!drag.moved && !drag.additive) setSelected(new Set());
    selectionDragRef.current = null;
    setSelectionBox(null);
    if (selectionRefreshRafRef.current !== null) {
      window.cancelAnimationFrame(selectionRefreshRafRef.current);
      selectionRefreshRafRef.current = null;
    }
    if (surface.hasPointerCapture(ev.pointerId)) surface.releasePointerCapture(ev.pointerId);
  }

  function scheduleSelectionRefresh() {
    if (selectionRefreshRafRef.current !== null) return;
    selectionRefreshRafRef.current = window.requestAnimationFrame(() => {
      selectionRefreshRafRef.current = null;
      const drag = selectionDragRef.current;
      if (drag) updateSelectionDrag(drag.currentClientX, drag.currentClientY);
    });
  }

  function updateSelectionDrag(clientX: number, clientY: number) {
    const drag = selectionDragRef.current;
    const surface = surfaceRef.current;
    if (!drag || !surface) return;
    const currentPoint = surfaceContentPoint(surface, clientX, clientY);
    drag.currentX = currentPoint.x;
    drag.currentY = currentPoint.y;
    drag.currentClientX = clientX;
    drag.currentClientY = clientY;
    const contentBox = normalizeSelectionBox(drag.startX, drag.startY, currentPoint.x, currentPoint.y);
    const viewportBox = contentSelectionBoxToViewport(surface, contentBox);
    drag.moved = drag.moved || viewportBox.width > 3 || viewportBox.height > 3;
    const clippedBox = clipSelectionBoxToSelectionViewport(viewportBox, surface);
    setSelectionBox(clippedBox);
    const hitBox = selectionBoxToRect(contentBox);
    const next = new Set(drag.base);
    tileRefs.current.forEach((node, key) => {
      if (rectsIntersect(hitBox, expandedContentRect(surface, node, SELECTION_HIT_SLOP_PX))) next.add(key);
    });
    setSelected(next);
  }

  return (
    <div className="media-gallery-media">
      <div className="media-gallery-grid-toolbar">
        <div className="media-gallery-grid-title">
          <Images size={15} />
          <span>{result?.title ?? '媒体'}</span>
        </div>
        <div className="media-gallery-grid-stats">
          <span>{displayEntries.length} 项</span>
          {selectedItems.length > 0 && <span className="media-gallery-selected-pill">已选 {selectedItems.length}</span>}
          {selectedItems.length > 0 && canMutate && (
            <button type="button" className="media-gallery-inline-action" onClick={() => void mutateMenuTarget()}>
              {restoreMode ? <Undo2 size={14} /> : <Trash2 size={14} />}
              {mutationLabel}
            </button>
          )}
        </div>
      </div>
      <div
        ref={surfaceRef}
        data-testid="media-gallery-selection-surface"
        className="media-gallery-selection-surface"
        style={{
          marginLeft: -SELECTION_EDGE_GUTTER_PX,
          marginRight: -SELECTION_EDGE_GUTTER_PX,
          paddingLeft: SELECTION_EDGE_GUTTER_PX,
          paddingRight: SELECTION_EDGE_GUTTER_PX
        }}
        onPointerDown={handleSurfacePointerDown}
        onPointerMove={handleSurfacePointerMove}
        onPointerUp={handleSurfacePointerUp}
        onPointerCancel={handleSurfacePointerUp}
        onContextMenuCapture={handleSurfaceContextMenuCapture}
      >
        <div
          data-testid="media-gallery-masonry"
          className="media-gallery-masonry"
          style={{
            gridTemplateColumns: `repeat(auto-fill, ${MASONRY_TILE_WIDTH_PX}px)`,
            gridAutoRows: `${MASONRY_ROW_HEIGHT_PX}px`
          }}
        >
          {displayEntries.map(({ item, index }) => {
            const key = mediaKey(item, index);
            return (
              <MediaTile
                key={key}
                item={item}
                index={index}
                selected={selected.has(key)}
                onOpen={() => openItem(item, index)}
                onToggleSelect={() => toggleItemSelection(item, index)}
                onContextMenuAt={(x, y) => openContextMenuAt(x, y, item, index)}
                setTileRef={node => setTileRef(key, node)}
                canDragReference={canDragReference}
                loading={loadingKey === `open:${key}`}
              />
            );
          })}
        </div>
        {selectionBox && createPortal(
          <div
            data-testid="media-gallery-selection-box"
            className="media-gallery-selection-box"
            style={{
              left: selectionBox.left,
              top: selectionBox.top,
              width: selectionBox.width,
              height: selectionBox.height
            }}
          />,
          document.body
        )}
      </div>

      {page?.hasMore && page.nextArgs && onLoadMore && (
        <div ref={loadMoreRef} className="media-gallery-load-more">
          {loadingKey === nextKey && (
            <>
              <Loader2 size={18} className="animate-spin" />
              <span>加载中</span>
            </>
          )}
        </div>
      )}

      {contextMenu && createPortal(
        <GalleryContextMenu
          menu={contextMenu}
          selectedCount={selectedItems.length}
          targetCount={menuTargetItems().length}
          contextItemSelected={contextMenu.item ? selected.has(mediaKey(contextMenu.item, contextMenu.index)) : false}
          canReference={Boolean(onReferenceMedia) && menuTargetItems().some(item => item?.media_type === 'photo')}
          canMutate={canMutate && menuTargetItems().length > 0}
          mutationLabel={mutationLabel}
          restoreMode={restoreMode}
          canOpen={Boolean(contextMenu.item)}
          onOpen={() => {
            if (contextMenu.item) openItem(contextMenu.item, contextMenu.index);
            setContextMenu(null);
          }}
          onReference={referenceMenuTarget}
          onToggleSelection={toggleContextItemSelection}
          onClearSelection={clearSelection}
          onMutate={() => void mutateMenuTarget()}
        />,
        document.body
      )}
    </div>
  );
}

function MediaTile({
  item,
  index,
  selected,
  onOpen,
  onContextMenuAt,
  onToggleSelect,
  setTileRef,
  canDragReference,
  loading
}: {
  item: any;
  index: number;
  selected: boolean;
  onOpen: () => void;
  onToggleSelect: () => void;
  onContextMenuAt: (x: number, y: number) => void;
  setTileRef: (node: HTMLDivElement | null) => void;
  canDragReference: boolean;
  loading: boolean;
}) {
  const { small } = mediaImageSources(item);
  const isVideo = item?.media_type === 'video';
  const title = item?.name ?? `${isVideo ? '视频' : '照片'} ${index + 1}`;
  const duration = isVideo ? formatDuration(item?.duration_ms) : '';
  const aspectRatio = mediaAspectRatio(item);
  const tileRef = useRef<HTMLDivElement | null>(null);
  const [rowSpan, setRowSpan] = useState(() => mediaGridFallbackRowSpan(item));

  useEffect(() => {
    const node = tileRef.current;
    if (!node) return;
    const updateSpan = () => {
      const next = mediaGridRowSpanForHeight(node.getBoundingClientRect().height);
      setRowSpan(prev => prev === next ? prev : next);
    };
    updateSpan();
    const observer = new ResizeObserver(updateSpan);
    observer.observe(node);
    return () => observer.disconnect();
  }, [aspectRatio, title, loading, selected]);

  function handleTileRef(node: HTMLDivElement | null) {
    tileRef.current = node;
    setTileRef(node);
  }

  function openIfReady(ev?: ReactMouseEvent<HTMLDivElement>) {
    if (ev?.ctrlKey || ev?.metaKey) {
      ev.preventDefault();
      ev.stopPropagation();
      onToggleSelect();
      return;
    }
    if (!loading) onOpen();
  }

  function handleKeyDown(ev: KeyboardEvent<HTMLDivElement>) {
    if (ev.key === 'Enter' || ev.key === ' ') {
      ev.preventDefault();
      if (ev.ctrlKey || ev.metaKey) onToggleSelect();
      else openIfReady();
    }
  }

  function handleDragStart(ev: DragEvent<HTMLDivElement>) {
    if (!canDragReference) return;
    ev.dataTransfer.effectAllowed = 'copy';
    ev.dataTransfer.setData(MEDIA_GALLERY_DRAG_TYPE, JSON.stringify({ items: [mediaDragPayload(item)] }));
    ev.dataTransfer.setData('text/plain', `${title} ${item?.media_ref ?? ''}`.trim());
    const dragGhost = createDragGhost(title, isVideo);
    document.body.appendChild(dragGhost);
    ev.dataTransfer.setDragImage(dragGhost, 18, 18);
    window.setTimeout(() => dragGhost.remove(), 0);
  }

  function handlePointerDown(ev: ReactPointerEvent<HTMLDivElement>) {
    if (ev.button !== 2) return;
    ev.preventDefault();
    ev.stopPropagation();
    onContextMenuAt(ev.clientX, ev.clientY);
  }

  function handleContextMenu(ev: ReactMouseEvent<HTMLDivElement>) {
    ev.preventDefault();
    ev.stopPropagation();
    onContextMenuAt(ev.clientX, ev.clientY);
  }

  function handleSelectClick(ev: ReactMouseEvent<HTMLButtonElement>) {
    ev.preventDefault();
    ev.stopPropagation();
    onToggleSelect();
  }

  function handleSelectPointerDown(ev: ReactPointerEvent<HTMLButtonElement>) {
    ev.preventDefault();
    ev.stopPropagation();
  }

  return (
    <div
      ref={handleTileRef}
      data-testid="media-gallery-tile"
      data-media-index={index}
      data-selected={selected ? 'true' : 'false'}
      role="button"
      aria-pressed={selected}
      tabIndex={0}
      aria-label={`预览媒体 ${title}`}
      draggable={canDragReference}
      onClick={openIfReady}
      onKeyDown={handleKeyDown}
      onPointerDown={handlePointerDown}
      onPointerDownCapture={handlePointerDown}
      onDragStart={handleDragStart}
      onContextMenu={handleContextMenu}
      onContextMenuCapture={handleContextMenu}
      className="media-gallery-tile"
      style={{ gridRowEnd: `span ${rowSpan}` }}
    >
      <div className="media-gallery-tile-media" style={{ aspectRatio }}>
        {small ? (
          <AuthMediaImage
            src={small}
            alt={title}
            title={title}
            className="media-gallery-image"
          />
        ) : (
          <div className="media-gallery-tile-placeholder">
            {title}
          </div>
        )}
        {loading && (
          <span className="media-gallery-tile-loading">
            <Loader2 size={15} className="animate-spin" />
          </span>
        )}
        <button
          type="button"
          className="media-gallery-select-mark"
          onPointerDown={handleSelectPointerDown}
          onClick={handleSelectClick}
          aria-pressed={selected}
          aria-label={selected ? '取消选择图片' : '选择图片'}
          title={selected ? '取消选择' : '选择'}
        >
          {selected && <Check size={13} strokeWidth={3} />}
        </button>
        <span className="media-gallery-kind-badge">
          {isVideo ? `视频${duration ? ` ${duration}` : ''}` : '照片'}
        </span>
      </div>
      <div className="media-gallery-tile-body">
        <div className="media-gallery-tile-title" title={title}>
          {title}
        </div>
        <div className="media-gallery-tile-meta">
          {item?.bucket_name ?? item?.relative_path ?? item?.media_ref ?? item?.id}
        </div>
      </div>
    </div>
  );
}

function GalleryContextMenu({
  menu,
  selectedCount,
  targetCount,
  contextItemSelected,
  canReference,
  canMutate,
  mutationLabel,
  restoreMode,
  canOpen,
  onOpen,
  onReference,
  onToggleSelection,
  onClearSelection,
  onMutate
}: {
  menu: GalleryContextMenu;
  selectedCount: number;
  targetCount: number;
  contextItemSelected: boolean;
  canReference: boolean;
  canMutate: boolean;
  mutationLabel: string;
  restoreMode: boolean;
  canOpen: boolean;
  onOpen: () => void;
  onReference: () => void;
  onToggleSelection: () => void;
  onClearSelection: () => void;
  onMutate: () => void;
}) {
  return (
    <div
      data-testid="media-gallery-context-menu"
      className="media-gallery-context-menu"
      style={{ left: menu.x, top: menu.y }}
      onContextMenu={ev => {
        ev.preventDefault();
        ev.stopPropagation();
      }}
      onMouseDown={ev => ev.stopPropagation()}
    >
      {canOpen && <MenuItem icon={<Eye size={15} />} onClick={onOpen}>查看图片</MenuItem>}
      <MenuItem icon={<Link2 size={15} />} onClick={onReference} disabled={!canReference}>引用图片{targetCount > 1 ? ` ${targetCount}` : ''}</MenuItem>
      {menu.item && (
        <MenuItem icon={contextItemSelected ? <CheckSquare size={15} /> : <Square size={15} />} onClick={onToggleSelection}>{contextItemSelected ? '取消选择图片' : '选择图片'}</MenuItem>
      )}
      {selectedCount > 0 && <MenuItem icon={<RotateCcw size={15} />} onClick={onClearSelection}>清空选择 {selectedCount}</MenuItem>}
      <div className="media-gallery-menu-separator" />
      <MenuItem icon={restoreMode ? <Undo2 size={15} /> : <Trash2 size={15} />} onClick={onMutate} disabled={!canMutate} danger={!restoreMode}>{mutationLabel}{targetCount > 1 ? ` ${targetCount}` : ''}</MenuItem>
    </div>
  );
}

function MenuItem({
  children,
  icon,
  disabled,
  danger,
  onClick
}: {
  children: ReactNode;
  icon?: ReactNode;
  disabled?: boolean;
  danger?: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      disabled={disabled}
      onClick={ev => {
        ev.stopPropagation();
        onClick();
      }}
      className={`media-gallery-menu-item ${danger ? 'media-gallery-menu-item-danger' : ''}`}
    >
      <span className="media-gallery-menu-icon">{icon}</span>
      <span className="media-gallery-menu-label">{children}</span>
    </button>
  );
}

function AuthMediaImage({
  src,
  alt,
  title,
  className
}: {
  src: string | null;
  alt?: string;
  title?: string;
  className?: string;
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
    let nextObjectUrl: string | null = null;
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
        nextObjectUrl = URL.createObjectURL(blob);
        setObjectUrl(nextObjectUrl);
      })
      .catch(() => {
        if (!cancelled) setFailed(true);
      });
    return () => {
      cancelled = true;
      ctrl.abort();
      if (nextObjectUrl) URL.revokeObjectURL(nextObjectUrl);
    };
  }, [src]);

  useEffect(() => {
    return () => {
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [objectUrl]);

  const displaySrc = objectUrl ?? (src && !needsAuthenticatedFetch(src) ? src : null);
  if (!displaySrc || failed) return <div className="media-gallery-image-skeleton" />;
  return <img src={displaySrc} alt={alt} title={title} draggable={false} className={className} />;
}

function entryIcon(entry: any, size: number) {
  const category = entry?.category;
  if (category === 'videos' || entry?.media_type === 'video' || entry?.cover_media_type === 'video') return <Video size={size} />;
  if (category === 'documents') return <FileText size={size} />;
  if (category === 'recent_deleted') return <Trash2 size={size} />;
  if (entry?.entry_type === 'album') return <Folder size={size} />;
  if (category === 'screenshots_recordings') return <Images size={size} />;
  return <ImageIcon size={size} />;
}

function mediaKey(item: any, index: number) {
  return `${item?.media_type ?? 'media'}:${item?.id ?? item?.media_ref ?? index}`;
}

function mediaDisplayEntries(items: any[]) {
  return items
    .map((item, index) => ({ item, index, time: mediaSortTime(item) }))
    .sort((a, b) => {
      if (b.time !== a.time) return b.time - a.time;
      return a.index - b.index;
    });
}

function mediaSortTime(item: any) {
  const value = numberOrUndefined(
    item?.date_taken_ms ??
    item?.dateTakenMs ??
    item?.date_modified_ms ??
    item?.dateModifiedMs
  );
  if (value) return value;
  const sec = numberOrUndefined(item?.date_modified_sec ?? item?.dateModifiedSec);
  if (sec) return sec * 1000;
  const created = Date.parse(String(item?.created_at ?? item?.createdAt ?? item?.date ?? ''));
  return Number.isFinite(created) ? created : 0;
}

function galleryPageInfo(result: any, fallbackCount: number): GalleryPageInfo | null {
  const pagination = record(result?.pagination);
  const display = record(result?.display);
  const offset = numberOrUndefined(pagination?.offset ?? result?.offset);
  const limit = numberOrUndefined(pagination?.limit ?? result?.limit ?? display?.limit);
  const returnedCount = numberOrUndefined(pagination?.returned_count ?? pagination?.returnedCount ?? result?.count) ?? fallbackCount;
  const nextOffset = numberOrUndefined(pagination?.next_offset ?? pagination?.nextOffset ?? result?.next_offset ?? result?.nextOffset ?? display?.next_offset);
  const nextArgs = record(pagination?.next_args ?? pagination?.nextArgs);
  const hasMoreValue = pagination?.has_more ?? pagination?.hasMore ?? result?.has_more ?? result?.hasMore ?? display?.has_more;
  const hasMore = hasMoreValue === true || hasMoreValue === 'true' || typeof nextOffset === 'number' || Boolean(nextArgs);
  const start = numberOrUndefined(pagination?.start_index ?? pagination?.startIndex) ??
    (typeof offset === 'number' && returnedCount > 0 ? offset + 1 : undefined);
  const end = numberOrUndefined(pagination?.end_index ?? pagination?.endIndex) ??
    (typeof offset === 'number' && returnedCount > 0 ? offset + returnedCount : undefined);
  const hasPage = typeof offset === 'number' || typeof limit === 'number' || hasMore || typeof start === 'number' || typeof end === 'number';
  if (!hasPage) return null;
  return { start, end, count: returnedCount, limit, hasMore, nextOffset, nextArgs };
}

export function mediaGalleryImageSources(item: any) {
  const uploadUrl = uploadAssetUrl(item?.asset_id ?? item?.assetId ?? item?.cover_asset_id ?? item?.cover_assetId);
  const thumbB64 = imageDataUrl(
    item?.preview_b64 ??
    item?.thumb_b64 ??
    item?.thumbnail_b64 ??
    item?.cover_thumb_b64 ??
    item?.cover_b64
  );
  const bigB64 = imageDataUrl(item?.full_b64 ?? item?.image_b64 ?? item?.vision_b64 ?? item?.image_base64 ?? item?.cover_image_b64 ?? item?.cover_b64);
  const bigUrl = firstImageUrl(
    item?.full_url,
    item?.image_url,
    item?.asset_url,
    item?.url,
    item?.cover_image_url,
    item?.cover_url,
    uploadUrl
  );
  const smallUrl = versionedThumbnailUrl(firstImageUrl(
    item?.preview_url,
    item?.thumb_url,
    item?.thumbnail_url,
    item?.cover_thumb_url,
    item?.cover_thumbnail_url
  ), item);
  const big = bigUrl ?? bigB64 ?? thumbB64;
  const small = smallUrl ?? thumbB64 ?? big;
  return { big, small };
}

function mediaImageSources(item: any) {
  return mediaGalleryImageSources(item);
}

function originalMediaSource(result: OpenOriginalMediaResult) {
  if (!result) return null;
  if (typeof result === 'string') return result;
  const src = typeof result.src === 'string' ? result.src.trim() : '';
  return src || null;
}

function mediaTypeOf(item: any) {
  return String(item?.media_type ?? item?.mediaType ?? '').toLowerCase() || null;
}

function mediaOpenFallbackSource(item: any, fallbackSrc: string | null) {
  if (mediaTypeOf(item) !== 'video') return fallbackSrc;
  const source = versionedThumbnailUrl(typeof item?.thumb_url === 'string' ? item.thumb_url : fallbackSrc, item);
  return source ? thumbnailUrlWithMaxDim(source, VIDEO_OPEN_THUMB_MAX_DIM) : fallbackSrc;
}

function thumbnailUrlWithMaxDim(src: string, maxDim: number) {
  if (!src.startsWith('/api/chat/media-gallery/thumbnail?')) return src;
  try {
    const url = new URL(src, window.location.origin);
    url.searchParams.set('maxDim', String(maxDim));
    return `${url.pathname}?${url.searchParams.toString()}`;
  } catch {
    return src.replace(/([?&]maxDim=)\d+/, `$1${maxDim}`);
  }
}

function versionedThumbnailUrl(src: string | null, item: any) {
  if (!src || !src.startsWith('/api/chat/media-gallery/thumbnail?')) return src;
  const version = mediaVersionParam(item);
  if (!version) return src;
  try {
    const url = new URL(src, window.location.origin);
    url.searchParams.set('v', version);
    return `${url.pathname}?${url.searchParams.toString()}`;
  } catch {
    const separator = src.includes('?') ? '&' : '?';
    return `${src}${separator}v=${encodeURIComponent(version)}`;
  }
}

function mediaVersionParam(item: any) {
  const modified = numberOrUndefined(item?.date_modified_sec ?? item?.dateModifiedSec);
  const size = numberOrUndefined(item?.size_bytes ?? item?.sizeBytes);
  if (!modified && !size) return null;
  return `${modified ?? 0}-${size ?? 0}`;
}

function mediaLightboxOptions(item: any): OpenImageOptions | undefined {
  if (!item || typeof item !== 'object') return undefined;
  const mediaType = mediaTypeOf(item);
  const aspectRatio = numberOrUndefined(item?.aspectRatio ?? item?.aspect_ratio);
  if (aspectRatio && aspectRatio > 0) return { aspectRatio, mediaType };
  const width = numberOrUndefined(
    item?.width ??
    item?.image_width ??
    item?.video_width ??
    item?.source_width ??
    item?.thumb_width
  );
  const height = numberOrUndefined(
    item?.height ??
    item?.image_height ??
    item?.video_height ??
    item?.source_height ??
    item?.thumb_height
  );
  if (width && height && width > 0 && height > 0) {
    if (mediaType === 'video') {
      return {
        ...scaleToLongEdge(width, height, VIDEO_OPEN_THUMB_MAX_DIM),
        aspectRatio: width / height,
        mediaType
      };
    }
    return { width, height, mediaType };
  }
  return mediaType ? { mediaType, aspectRatio: mediaType === 'video' ? 16 / 9 : null } : undefined;
}

function scaleToLongEdge(width: number, height: number, maxLongEdge: number) {
  const longEdge = Math.max(width, height);
  if (longEdge <= 0 || longEdge <= maxLongEdge) return { width, height };
  const scale = maxLongEdge / longEdge;
  return {
    width: Math.max(1, Math.round(width * scale)),
    height: Math.max(1, Math.round(height * scale))
  };
}

function mediaDragPayload(item: any) {
  const out: Record<string, any> = {};
  [
    'media_type',
    'id',
    'name',
    'media_ref',
    'bucket_id',
    'bucket_name',
    'relative_path',
    'date_taken_ms',
    'date_modified_sec',
    'width',
    'height',
    'mime_type',
    'thumb_url',
    'preview_url',
    'thumbnail_url'
  ].forEach(key => {
    if (item?.[key] !== undefined && item?.[key] !== null) out[key] = item[key];
  });
  if (!out.media_ref && out.media_type && out.id) out.media_ref = `media://${out.media_type}/${out.id}`;
  return out;
}

function clampContextMenuPosition(x: number, y: number) {
  if (typeof window === 'undefined') return { x, y };
  const width = 190;
  const height = 250;
  const margin = 8;
  return {
    x: Math.max(margin, Math.min(x, window.innerWidth - width - margin)),
    y: Math.max(margin, Math.min(y, window.innerHeight - height - margin))
  };
}

function createDragGhost(title: string, isVideo: boolean) {
  const node = document.createElement('div');
  node.className = 'media-gallery-drag-ghost';
  node.style.cssText = [
    'position:fixed',
    'left:-9999px',
    'top:-9999px',
    'width:112px',
    'height:72px',
    'overflow:hidden',
    'border-radius:8px',
    'background:#e2e8f0',
    'box-shadow:0 10px 30px rgba(15,23,42,.25)',
    'contain:layout paint style'
  ].join(';');

  const label = document.createElement('div');
  label.textContent = `${isVideo ? '视频' : '照片'} ${title}`;
  label.style.cssText = 'display:grid;width:100%;height:100%;place-items:center;padding:8px;color:#64748b;font:600 12px system-ui;text-align:center';
  node.appendChild(label);
  return node;
}

function normalizeSelectionBox(startX: number, startY: number, currentX: number, currentY: number): SelectionBox {
  const left = Math.min(startX, currentX);
  const top = Math.min(startY, currentY);
  return {
    left,
    top,
    width: Math.abs(currentX - startX),
    height: Math.abs(currentY - startY)
  };
}

function surfaceContentPoint(surface: HTMLElement, clientX: number, clientY: number) {
  const rect = surface.getBoundingClientRect();
  return {
    x: clientX - rect.left,
    y: clientY - rect.top
  };
}

function contentSelectionBoxToViewport(surface: HTMLElement, box: SelectionBox): SelectionBox {
  const rect = surface.getBoundingClientRect();
  return {
    left: rect.left + box.left,
    top: rect.top + box.top,
    width: box.width,
    height: box.height
  };
}

function selectionBoxToRect(box: SelectionBox): RectLike {
  return {
    left: box.left,
    top: box.top,
    right: box.left + box.width,
    bottom: box.top + box.height
  };
}

function expandedContentRect(surface: HTMLElement, node: HTMLElement, slop: number) {
  const surfaceRect = surface.getBoundingClientRect();
  const nodeRect = node.getBoundingClientRect();
  return {
    left: nodeRect.left - surfaceRect.left - slop,
    top: nodeRect.top - surfaceRect.top - slop,
    right: nodeRect.right - surfaceRect.left + slop,
    bottom: nodeRect.bottom - surfaceRect.top + slop
  };
}

function clipSelectionBoxToSelectionViewport(box: SelectionBox, surface: HTMLElement): SelectionBox | null {
  const surfaceRect = selectionViewportRect(surface);
  const rawRight = box.left + box.width;
  const rawBottom = box.top + box.height;
  const left = Math.max(box.left, surfaceRect.left);
  const top = Math.max(box.top, surfaceRect.top);
  const right = Math.min(rawRight, surfaceRect.right);
  const bottom = Math.min(rawBottom, surfaceRect.bottom);
  if (right <= left || bottom <= top) return null;
  return {
    left,
    top,
    width: right - left,
    height: bottom - top
  };
}

function selectionViewportRect(surface: HTMLElement): RectLike {
  const scrollParent = nearestScrollParent(surface);
  if (scrollParent) return scrollParent.getBoundingClientRect();
  return {
    left: 0,
    top: 0,
    right: window.innerWidth,
    bottom: window.innerHeight
  };
}

function nearestScrollParent(node: HTMLElement) {
  let current = node.parentElement;
  while (current && current !== document.body && current !== document.documentElement) {
    const style = window.getComputedStyle(current);
    if (/(auto|scroll|overlay)/.test(`${style.overflow}${style.overflowY}${style.overflowX}`)) return current;
    current = current.parentElement;
  }
  return null;
}

type RectLike = { left: number; top: number; right: number; bottom: number };

function rectsIntersect(a: RectLike, b: RectLike) {
  return a.left <= b.right && a.right >= b.left && a.top <= b.bottom && a.bottom >= b.top;
}

function toggledSelection(prev: Set<string>, key: string) {
  const next = new Set(prev);
  if (next.has(key)) next.delete(key);
  else next.add(key);
  return next;
}

function formatDuration(ms: unknown) {
  const value = Number(ms);
  if (!Number.isFinite(value) || value <= 0) return '';
  const sec = Math.round(value / 1000);
  const minutes = Math.floor(sec / 60);
  const seconds = sec % 60;
  return `${minutes}:${String(seconds).padStart(2, '0')}`;
}

function mediaAspectRatio(item: any) {
  const width = numberOrUndefined(
    item?.width ??
    item?.image_width ??
    item?.video_width ??
    item?.source_width ??
    item?.thumb_width
  );
  const height = numberOrUndefined(
    item?.height ??
    item?.image_height ??
    item?.video_height ??
    item?.source_height ??
    item?.thumb_height
  );
  if (width && height && width > 0 && height > 0) {
    return String(Math.min(1.8, Math.max(0.62, width / height)));
  }
  return item?.media_type === 'video' ? '16 / 10' : '1 / 1';
}

function mediaGridFallbackRowSpan(item: any) {
  const width = numberOrUndefined(
    item?.width ??
    item?.image_width ??
    item?.video_width ??
    item?.source_width ??
    item?.thumb_width
  );
  const height = numberOrUndefined(
    item?.height ??
    item?.image_height ??
    item?.video_height ??
    item?.source_height ??
    item?.thumb_height
  );
  const ratio = width && height && width > 0 && height > 0
    ? Math.min(1.8, Math.max(0.62, width / height))
    : item?.media_type === 'video' ? 1.6 : 1;
  const estimatedTextHeight = 54;
  const estimatedTileWidth = MASONRY_TILE_WIDTH_PX;
  const estimatedHeight = Math.round(estimatedTileWidth / ratio + estimatedTextHeight);
  return mediaGridRowSpanForHeight(estimatedHeight);
}

function mediaGridRowSpanForHeight(height: number) {
  const value = Number(height);
  if (!Number.isFinite(value) || value <= 0) return 8;
  return Math.max(6, Math.ceil((value + MASONRY_GAP_PX) / (MASONRY_ROW_HEIGHT_PX + MASONRY_GAP_PX)));
}

function imageDataUrl(value: unknown) {
  if (typeof value !== 'string') return null;
  const clean = value.trim();
  if (clean.length < 16 || clean.startsWith('<')) return null;
  if (clean.startsWith('data:image/')) return clean;
  return `data:image/jpeg;base64,${clean}`;
}

function uploadAssetUrl(value: unknown): string | null {
  if (typeof value !== 'string') return null;
  const clean = value.trim();
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(clean)) return null;
  return `/api/uploads/photos/${clean}`;
}

function firstImageUrl(...values: unknown[]) {
  for (const value of values) {
    const url = imageUrl(value);
    if (url) return url;
  }
  return null;
}

function imageUrl(value: unknown): string | null {
  if (record(value)) return imageUrl(record(value)?.url);
  if (typeof value !== 'string') return null;
  const clean = value.trim();
  if (!clean || clean.startsWith('<')) return null;
  if (clean.startsWith('data:image/')) return clean;
  if (/^(https?:)?\/\//i.test(clean)) return clean;
  if (/^(blob:|\/|\.\/|\.\.\/)/i.test(clean)) return clean;
  return null;
}

function mediaIdentity(item: any) {
  const type = item?.media_type ?? parseMediaRef(item?.media_ref)?.mediaType ?? 'media';
  const id = item?.id ?? parseMediaRef(item?.media_ref)?.id;
  if (id == null || String(id).trim() === '') return '';
  return `${type}:${String(id).trim()}`;
}

function mediaGalleryResultIdentity(result: any) {
  const sections = Array.isArray(result?.sections) ? result.sections : [];
  const items = Array.isArray(result?.items) ? result.items : [];
  return JSON.stringify({
    result_type: result?.result_type ?? result?.resultType,
    display_policy: result?.display_policy ?? result?.displayPolicy,
    view: result?.view,
    title: result?.title,
    category: result?.category,
    bucket_id: result?.bucket_id ?? result?.bucketId,
    count: result?.count,
    sections: sections.map((section: any) => ({
      title: section?.title,
      entries: (Array.isArray(section?.entries) ? section.entries : []).map((entry: any) => ({
        key: entry?.key,
        entry_type: entry?.entry_type,
        title: entry?.title,
        category: entry?.category,
        bucket_id: entry?.bucket_id,
        count: entry?.count
      }))
    })),
    items: items.map((item: any, index: number) => mediaIdentity(item) || mediaKey(item, index)),
    pagination: {
      offset: result?.pagination?.offset ?? result?.offset,
      limit: result?.pagination?.limit ?? result?.limit,
      next_offset: result?.pagination?.next_offset ?? result?.pagination?.nextOffset ?? result?.next_offset,
      has_more: result?.pagination?.has_more ?? result?.pagination?.hasMore ?? result?.has_more
    }
  });
}

function isRecentDeletedResult(result: any) {
  const category = String(result?.category ?? result?.display?.category ?? '').toLowerCase();
  const title = String(result?.title ?? '').trim();
  return category === 'recent_deleted' || title === '最近删除';
}

function trashItem(item: any) {
  const ref = parseMediaRef(item?.media_ref);
  const mediaType = item?.media_type ?? ref?.mediaType;
  const id = item?.id ?? ref?.id;
  if (!mediaType || id == null || String(id).trim() === '') return null;
  return {
    media_type: String(mediaType),
    id: String(id).trim(),
    media_ref: item?.media_ref ?? `media://${mediaType}/${id}`
  };
}

function parseMediaRef(value: unknown): { mediaType: string; id: string } | null {
  if (typeof value !== 'string') return null;
  const match = value.trim().match(/^media:\/\/(photo|video)\/(\d+)$/);
  if (!match) return null;
  return { mediaType: match[1], id: match[2] };
}

function needsAuthenticatedFetch(src: string) {
  return src.startsWith('/api/uploads/') || src.startsWith('/api/chat/media-gallery/thumbnail?');
}

function record(value: unknown): Record<string, any> | null {
  return value && typeof value === 'object' && !Array.isArray(value) ? value as Record<string, any> : null;
}

function unwrapToolValue(value: any) {
  if (value && typeof value === 'object' && 'value' in value && !('result_type' in value)) return value.value;
  return value;
}

function toolErrorMessage(result: any, fallback: string) {
  return result?.error_detail?.message ?? result?.errorDetail?.message ?? result?.error ?? fallback;
}

function entryKey(entry: any, sectionIndex: number, entryIndex: number) {
  return `entry:${sectionIndex}:${entry?.key ?? entry?.bucket_id ?? entry?.category ?? entry?.title ?? entryIndex}`;
}

function nextPageKey(result: any) {
  return `next:${result?.view ?? 'grid'}:${result?.category ?? result?.bucket_id ?? result?.title ?? ''}:${result?.offset ?? 0}`;
}

function numberOrUndefined(value: unknown): number | undefined {
  const n = typeof value === 'number' ? value : Number(value);
  return Number.isFinite(n) ? n : undefined;
}

const TRASH_SELECTED_KEY = 'trash:selected';
const RESTORE_SELECTED_KEY = 'restore:selected';

interface GalleryContextMenu {
  x: number;
  y: number;
  item?: any;
  index: number;
}

interface SelectionBox {
  left: number;
  top: number;
  width: number;
  height: number;
}

interface SelectionDragState {
  startX: number;
  startY: number;
  currentX: number;
  currentY: number;
  currentClientX: number;
  currentClientY: number;
  base: Set<string>;
  additive: boolean;
  moved: boolean;
  pointerId: number;
}

interface GalleryPageInfo {
  start?: number;
  end?: number;
  count: number;
  limit?: number;
  hasMore: boolean;
  nextOffset?: number;
  nextArgs?: Record<string, any> | null;
}
