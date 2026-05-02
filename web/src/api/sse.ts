import { fetchEventSource } from '@microsoft/fetch-event-source';
import { getToken } from '../lib/auth';

// `any` here is intentional — server-side SSE event payloads are union typed
// per event name and the bubble renderer in ChatPage knows what to expect.
export type SseHandler = (eventName: string, data: any) => void;

export interface ChatStreamOptions {
  message: string;
  sessionId?: string;
  deviceId?: string;
  onEvent: SseHandler;
  onError?: (err: unknown) => void;
  onClose?: () => void;
  signal?: AbortSignal;
}

/**
 * POST /api/chat/stream — emits user_message / tool_call_started /
 * tool_call_result / assistant_message events. Uses Microsoft's
 * fetch-event-source so we can do POST + body (native EventSource is GET-only).
 */
export async function streamChat(opts: ChatStreamOptions): Promise<void> {
  const { onEvent, onError, onClose, signal, ...body } = opts;
  const token = getToken();
  await fetchEventSource('/api/chat/stream', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Accept': 'text/event-stream',
      ...(token ? { 'Authorization': `Bearer ${token}` } : {})
    },
    body: JSON.stringify(body),
    signal,
    onmessage(ev) {
      try {
        const parsed = ev.data ? JSON.parse(ev.data) : null;
        onEvent(ev.event || 'message', parsed);
      } catch (e) {
        onError?.(e);
      }
    },
    onclose() { onClose?.(); },
    onerror(err) { onError?.(err); throw err; }
  });
}
