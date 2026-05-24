import { getToken, setToken } from '../lib/auth';

export class ApiError extends Error {
  constructor(public status: number, message: string) {
    super(message);
    this.name = 'ApiError';
  }
}

async function request<T>(
  path: string,
  init: RequestInit = {}
): Promise<T> {
  const token = getToken();
  const headers = new Headers(init.headers);
  if (!headers.has('Content-Type') && init.body) {
    headers.set('Content-Type', 'application/json');
  }
  if (token) headers.set('Authorization', `Bearer ${token}`);

  const resp = await fetch(path, { ...init, headers });
  if (resp.status === 401) {
    setToken(null);
    window.location.assign('/login');
    throw new ApiError(401, '未登录或登录已过期');
  }
  if (!resp.ok) {
    let message = `HTTP ${resp.status}`;
    try {
      const body = await resp.json();
      if (body?.message) message = body.message;
    } catch { /* ignore */ }
    throw new ApiError(resp.status, message);
  }
  if (resp.status === 204) return undefined as T;
  return resp.json() as Promise<T>;
}

export const api = {
  login: (username: string, password: string) =>
    request<{ userId: string; username: string; token: string }>(
      '/api/auth/login', { method: 'POST', body: JSON.stringify({ username, password }) }
    ),

  register: (username: string, password: string) =>
    request<{ userId: string; username: string }>(
      '/api/auth/register', { method: 'POST', body: JSON.stringify({ username, password }) }
    ),

  listDevices: () =>
    request<DeviceDto[]>('/api/me/devices'),

  listDeviceOnlineStatus: () =>
    request<DeviceOnlineStatusDto[]>('/api/devices/online-status'),

  createEnrollment: () =>
    request<EnrollmentResponse>('/api/me/devices/enrollments', { method: 'POST' }),

  revokeDevice: (deviceId: string) =>
    request<void>(`/api/me/devices/${encodeURIComponent(deviceId)}`, { method: 'DELETE' }),

  listSessions: () =>
    request<SessionDto[]>('/api/sessions'),

  listMessages: (sessionId: string) =>
    request<MessageDto[]>(`/api/sessions/${sessionId}/messages`),

  createSession: (title?: string) =>
    request<SessionDto>('/api/sessions', {
      method: 'POST',
      body: JSON.stringify(title ? { title } : {})
    }),

  deleteSession: (sessionId: string) =>
    request<void>(`/api/sessions/${sessionId}`, { method: 'DELETE' }),

  cancelChatRun: (clientRunId: string) =>
    request<void>(`/api/chat/runs/${encodeURIComponent(clientRunId)}/cancel`, { method: 'POST' }),

  browseMediaGallery: (input: MediaGalleryBrowseRequest = {}) =>
    request<unknown>('/api/chat/media-gallery/browse', {
      method: 'POST',
      body: JSON.stringify(input)
    }),

  openMediaGalleryOriginal: (input: MediaGalleryOriginalRequest) =>
    request<unknown>('/api/chat/media-gallery/original', {
      method: 'POST',
      body: JSON.stringify(input)
    }),

  trashMediaGalleryItems: (input: MediaGalleryTrashRequest) =>
    request<unknown>('/api/chat/media-gallery/trash', {
      method: 'POST',
      body: JSON.stringify(input)
    }),

  restoreMediaGalleryItems: (input: MediaGalleryRestoreRequest) =>
    request<unknown>('/api/chat/media-gallery/restore', {
      method: 'POST',
      body: JSON.stringify(input)
    }),

  sessionExportUrl: (sessionId: string) =>
    `/api/sessions/${sessionId}/export.jsonl`,

  downloadSessionExport: async (sessionId: string) => {
    const token = getToken();
    const headers = new Headers();
    if (token) headers.set('Authorization', `Bearer ${token}`);
    const resp = await fetch(`/api/sessions/${sessionId}/export.jsonl`, { headers });
    if (resp.status === 401) {
      setToken(null);
      window.location.assign('/login');
      throw new ApiError(401, '未登录或登录已过期');
    }
    if (!resp.ok) throw new ApiError(resp.status, `HTTP ${resp.status}`);
    const blob = await resp.blob();
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = `session-${sessionId}.jsonl`;
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
  },

  getPreferences: () =>
    request<UserPreferenceDto>('/api/me/preferences'),

  updatePreferences: (input: UpdatePreferenceRequest) =>
    request<UserPreferenceDto>('/api/me/preferences', {
      method: 'PUT',
      body: JSON.stringify(input)
    }),

  listMemories: (input: { limit?: number; includeRaw?: boolean } = {}) => {
    const params = new URLSearchParams();
    params.set('limit', String(input.limit ?? 100));
    params.set('includeRaw', String(input.includeRaw ?? true));
    return request<MemoryFactDto[]>(`/api/memories?${params.toString()}`);
  },

  deleteMemory: (id: string) =>
    request<void>(`/api/memories/${encodeURIComponent(id)}`, { method: 'DELETE' }),

  uploadPhoto: async (file: File) => {
    const token = getToken();
    const headers = new Headers();
    if (token) headers.set('Authorization', `Bearer ${token}`);
    const form = new FormData();
    form.append('file', file, file.name || 'image');
    const resp = await fetch('/api/uploads/photos', {
      method: 'POST',
      headers,
      body: form
    });
    if (resp.status === 401) {
      setToken(null);
      window.location.assign('/login');
      throw new ApiError(401, '未登录或登录已过期');
    }
    if (!resp.ok) {
      let message = `HTTP ${resp.status}`;
      try {
        const body = await resp.json();
        if (body?.message) message = body.message;
      } catch { /* ignore */ }
      throw new ApiError(resp.status, message);
    }
    return resp.json() as Promise<PhotoUploadResponse>;
  }
};

export interface DeviceDto {
  id: string;
  name: string;
  model?: string;
  osVersion?: string;
  lastSeenAt?: string;
  createdAt: string;
}

export interface DeviceOnlineStatusDto {
  deviceId: string;
  online: boolean;
  connectedAt?: string;
  toolCount: number;
  tools?: DeviceToolSpecDto[];
}

export interface DeviceToolSpecDto {
  name: string;
  description?: string;
  schema?: unknown;
  confirmRequired?: boolean;
  schemaVersion?: string;
  toolClass?: string;
  safetyLevel?: string;
  defaultDisplayPolicy?: string;
  resultType?: string;
}

export interface MediaGalleryBrowseRequest {
  args?: unknown;
  deviceId?: string;
}

export interface MediaGalleryOriginalRequest {
  id: string;
  mediaType?: string;
  maxDim?: number;
  deviceId?: string;
  dateModifiedSec?: number;
  sizeBytes?: number;
}

export interface MediaGalleryTrashRequest {
  args: unknown;
  deviceId?: string;
}

export interface MediaGalleryRestoreRequest {
  args: unknown;
  deviceId?: string;
}

export interface EnrollmentResponse {
  token: string;
  qrPayload: string;
  expiresAt: string;
}

export interface SessionDto {
  id: string;
  userId: string;
  title?: string;
  createdAt: string;
  lastMessageAt?: string;
}

export interface MessageDto {
  id: string;
  sessionId: string;
  role: 'USER' | 'ASSISTANT' | 'TOOL_CALL' | 'TOOL_RESULT';
  content: string;
  metadata?: unknown;
  createdAt: string;
}

export interface UserPreferenceDto {
  content: string;
  updatedAt: string | null;
  autoMemoryEnabled?: boolean | null;
}

export interface UpdatePreferenceRequest {
  content?: string | null;
  autoMemoryEnabled?: boolean | null;
}

export interface MemoryFactDto {
  id: string;
  userId: string;
  kind: 'fact' | 'preference' | 'rule' | 'lesson' | string;
  content: string;
  sourceMessageId?: string | null;
  createdAt?: string | null;
  isCurated?: boolean;
  curated?: boolean;
  accessCount: number;
  curatedAt?: string | null;
}

export interface PhotoUploadResponse {
  assetId: string;
  imageUrl: string;
  bytes: number;
  contentType: string;
}
