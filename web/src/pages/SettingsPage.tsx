import { useEffect, useState } from 'react';
import { api, ApiError } from '../api/client';

type SaveState = 'idle' | 'saving' | 'saved' | 'error';

export default function SettingsPage() {
  const [content, setContent] = useState('');
  const [updatedAt, setUpdatedAt] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [saveState, setSaveState] = useState<SaveState>('idle');
  const [saveError, setSaveError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    api.getPreferences()
      .then(p => {
        if (cancelled) return;
        setContent(p.content ?? '');
        setUpdatedAt(p.updatedAt);
      })
      .catch((e: unknown) => {
        if (cancelled) return;
        setLoadError(e instanceof ApiError ? e.message : String(e));
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, []);

  async function save() {
    setSaveState('saving');
    setSaveError(null);
    try {
      const updated = await api.updatePreferences(content);
      setUpdatedAt(updated.updatedAt);
      setSaveState('saved');
      // fade the "saved" badge after a beat so it doesn't pretend to be permanent
      setTimeout(() => setSaveState(s => (s === 'saved' ? 'idle' : s)), 2000);
    } catch (e) {
      setSaveState('error');
      setSaveError(e instanceof ApiError ? e.message : String(e));
    }
  }

  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-semibold">设置</h1>
        <div className="flex items-center gap-3 text-sm">
          {updatedAt && (
            <span className="text-slate-400">
              最近保存 {new Date(updatedAt).toLocaleString()}
            </span>
          )}
          {saveState === 'saved' && <span className="text-green-600">已保存</span>}
          {saveState === 'error' && (
            <span className="text-red-600">保存失败{saveError ? `:${saveError}` : ''}</span>
          )}
          <button
            onClick={save}
            disabled={loading || saveState === 'saving'}
            className="bg-blue-600 hover:bg-blue-700 disabled:opacity-50 text-white px-3 py-1.5 rounded"
          >
            {saveState === 'saving' ? '保存中…' : '保存'}
          </button>
        </div>
      </div>

      <div className="bg-blue-50 border border-blue-200 text-blue-900 text-sm rounded px-4 py-3">
        在这里写下你的偏好和规则,每次对话都会自动注入到助手的系统 prompt 里 —
        可以把它当作你的个人 <code>CLAUDE.md</code>。
        例如:希望助手如何称呼你、默认语气、项目背景、需要始终记住的事、要避免的内容。
        例如:
        <code className="mx-1 px-1 bg-white/60 rounded">- 用中文回答</code>
        <code className="mx-1 px-1 bg-white/60 rounded">- 答案尽量简短</code>。
        支持 markdown,格式自由。
      </div>

      {loading && <div className="text-slate-500">加载中…</div>}
      {loadError && <div className="text-red-600">加载失败:{loadError}</div>}

      {!loading && !loadError && (
        <textarea
          value={content}
          onChange={e => setContent(e.target.value)}
          rows={20}
          spellCheck={false}
          placeholder={'# 关于我\n\n# 偏好\n\n- 用中文回答。\n- 答案尽量简短。\n'}
          className="w-full bg-white border rounded px-3 py-2 font-mono text-sm
                     focus:outline-none focus:ring-2 focus:ring-blue-500
                     resize-y leading-relaxed"
        />
      )}
    </div>
  );
}
