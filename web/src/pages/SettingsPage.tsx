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
      <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div>
          <h1 className="page-title">设置</h1>
          <p className="page-subtitle">维护每次对话都会注入的个人规则。</p>
        </div>
        <div className="flex flex-wrap items-center gap-2 text-sm sm:justify-end">
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
            className="btn-primary min-h-9"
          >
            {saveState === 'saving' ? '保存中...' : '保存'}
          </button>
        </div>
      </div>

      <div className="status-info">
        在这里写下你的偏好和规则,每次对话都会自动注入到助手的系统 prompt 里 —
        可以把它当作你的个人 <code>CLAUDE.md</code>。
        例如:希望助手如何称呼你、默认语气、项目背景、需要始终记住的事、要避免的内容。
        例如:
        <code className="mx-1 px-1 bg-white/60 rounded">- 用中文回答</code>
        <code className="mx-1 px-1 bg-white/60 rounded">- 答案尽量简短</code>。
        支持 markdown,格式自由。
      </div>

      {loading && <div className="status-muted">加载中...</div>}
      {loadError && <div className="status-error">加载失败:{loadError}</div>}

      {!loading && !loadError && (
        <textarea
          value={content}
          onChange={e => setContent(e.target.value)}
          rows={20}
          spellCheck={false}
          placeholder={'# 关于我\n\n# 偏好\n\n- 用中文回答。\n- 答案尽量简短。\n'}
          className="field-input min-h-[28rem] resize-y font-mono leading-relaxed"
        />
      )}
    </div>
  );
}
