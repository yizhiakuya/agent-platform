import { useEffect, useRef, useState } from 'react';
import { streamChat } from '../api/sse';

interface ChatEvent {
  type: string;
  data: any;
}

export default function ChatPage() {
  const [input, setInput] = useState('');
  const [events, setEvents] = useState<ChatEvent[]>([]);
  const [busy, setBusy] = useState(false);
  const [lightboxSrc, setLightboxSrc] = useState<string | null>(null);
  const [sessionId, setSessionId] = useState<string | null>(null);
  const abortRef = useRef<AbortController | null>(null);
  // Stash of the just-sent message so a Stop / ESC can put it back into the input.
  const lastSentRef = useRef<string>('');
  // Index where the just-sent user_message was pushed; used to truncate
  // the in-flight events on cancel so the screen doesn't keep an orphan
  // half-streamed assistant bubble.
  const sentEventsIdxRef = useRef<number>(-1);

  function handleStop() {
    if (!busy) return;
    abortRef.current?.abort();
    // Put the message back into the input so the user can edit and resend.
    if (lastSentRef.current && !input) setInput(lastSentRef.current);
    // Drop the user_message + any partial assistant/tool events from this turn.
    const cutoff = sentEventsIdxRef.current;
    if (cutoff >= 0) setEvents(prev => prev.slice(0, cutoff));
    setBusy(false);
  }

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
  }, [busy]);

  async function send() {
    if (!input.trim() || busy) return;
    const message = input;
    setInput('');
    lastSentRef.current = message;
    setBusy(true);
    // Capture the index where this turn's events will start so handleStop
    // can roll back to here.
    setEvents(prev => {
      sentEventsIdxRef.current = prev.length;
      return prev;
    });
    const ctrl = new AbortController();
    abortRef.current = ctrl;

    let assistantBuf = '';
    try {
      await streamChat({
        message,
        sessionId: sessionId ?? undefined,
        signal: ctrl.signal,
        onEvent(name, data) {
          if (name === 'session') {
            // server tells us which session we're on; remember it so subsequent
            // sends carry it back and the LLM gets the conversation history.
            if (data?.sessionId) setSessionId(data.sessionId);
            return;
          }
          if (name === 'assistant_message') {
            // streaming chunks — accumulate into a single bubble
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
        onError() { /* swallow — onclose still fires */ },
        onClose() { setBusy(false); }
      });
    } catch {
      setBusy(false);
    }
  }

  return (
    <div className="flex flex-col h-[calc(100vh-100px)]">
      <div className="flex-1 overflow-y-auto bg-white border rounded p-4 space-y-3">
        {events.length === 0 && (
          <div className="text-slate-500 text-sm">
            试试:<em>"列出我最近的照片"</em>。助手会在你绑定的设备上调用
            <code className="mx-1 px-1.5 py-0.5 bg-slate-100 rounded">photos.list_recent</code>
            工具。
          </div>
        )}
        {events.map((e, i) => <Bubble key={i} ev={e} onOpenImage={setLightboxSrc} />)}
      </div>

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

      <form onSubmit={ev => { ev.preventDefault(); void send(); }} className="mt-3 flex gap-2">
        <input
          type="text"
          value={input}
          onChange={e => setInput(e.target.value)}
          placeholder={busy ? '生成中… 按 Esc 或点"停止"中断' : '输入消息…'}
          className="flex-1 border rounded px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500"
          disabled={busy}
        />
        {busy ? (
          <button
            type="button"
            onClick={handleStop}
            className="bg-red-600 hover:bg-red-700 text-white px-4 py-2 rounded"
            title="按 Esc 也可中断"
          >停止</button>
        ) : (
          <button
            type="submit"
            disabled={!input.trim()}
            className="bg-blue-600 hover:bg-blue-700 disabled:opacity-50 text-white px-4 py-2 rounded"
          >发送</button>
        )}
      </form>
    </div>
  );
}

type OpenImage = (src: string) => void;

function Bubble({ ev, onOpenImage }: { ev: ChatEvent; onOpenImage: OpenImage }) {
  switch (ev.type) {
    case 'user_message':
      return (
        <div className="flex justify-end">
          <div className="bg-blue-600 text-white px-3 py-2 rounded-lg max-w-prose">
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
          {ev.data?.args && <span>({JSON.stringify(ev.data.args)})</span>}
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
  // Special-case photo results so we render thumbnails instead of raw JSON.
  if (tool === 'photos.list_recent' && Array.isArray(result?.photos)) {
    return (
      <div className="grid grid-cols-3 sm:grid-cols-5 gap-2">
        {result.photos.map((p: any) => {
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
  return (
    <details className="text-xs text-slate-600">
      <summary>工具 {tool} 返回结果</summary>
      <pre className="mt-1 bg-slate-100 p-2 rounded overflow-x-auto">{JSON.stringify(result, null, 2)}</pre>
    </details>
  );
}
