import { fetchEventSource } from '@microsoft/fetch-event-source';
import { getToken } from '../lib/auth';

// `any` here is intentional — server-side SSE event payloads are union typed
// per event name and the bubble renderer in ChatPage knows what to expect.
export type SseHandler = (eventName: string, data: any) => void;

export interface ChatStreamOptions {
  message: string;
  sessionId?: string;
  deviceId?: string;
  clientRunId?: string;
  attachments?: ChatImageAttachment[];
  onEvent: SseHandler;
  onError?: (err: unknown) => void;
  onClose?: () => void;
  signal?: AbortSignal;
}

export interface ChatImageAttachment {
  imageUrl: string;
  assetId?: string;
  contentType?: string;
  bytes?: number;
  name?: string;
  width?: number;
  height?: number;
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
    // Keep streaming when the tab is backgrounded — without this the lib
    // closes on visibilitychange=hidden and tries to reconnect on return,
    // which on a POST endpoint means re-submitting the original body.
    openWhenHidden: true,
    onmessage(ev) {
      try {
        const parsed = ev.data ? JSON.parse(ev.data) : null;
        onEvent(ev.event || 'message', parsed);
      } catch (e) {
        onError?.(e);
      }
    },
    onclose() { onClose?.(); },
    // Throwing from onerror disables fetchEventSource's automatic retry.
    // Without this, transient network blips on background tabs would
    // POST the user's message a second time.
    onerror(err) { onError?.(err); throw err; }
  });
}
