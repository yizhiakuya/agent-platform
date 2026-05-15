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
      setTimeout(() => setSaveState(s => (s === 'saved' ? 'idle' : s)), 2000);
    } catch (e) {
      setSaveState('error');
      setSaveError(e instanceof ApiError ? e.message : String(e));
    }
  }

  return (
    <div className="workbench grid lg:grid-cols-[minmax(0,1fr)_20rem]">
      <section className="page-surface flex min-h-[calc(100vh-2rem)] min-w-0 flex-col overflow-hidden lg:min-h-0">
        <div className="panel-header">
          <div className="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
            <div>
              <div className="section-title">Preferences</div>
              <h1 className="mt-1 page-title">个人规则</h1>
              <p className="page-subtitle">这些内容会进入每次对话的用户上下文。</p>
            </div>
            <div className="flex flex-wrap items-center gap-2 text-sm sm:justify-end">
              {saveState === 'saved' && <span className="font-semibold text-emerald-600">已保存</span>}
              {saveState === 'error' && (
                <span className="font-semibold text-red-600">保存失败{saveError ? `: ${saveError}` : ''}</span>
              )}
              <button
                onClick={save}
                disabled={loading || saveState === 'saving'}
                className="btn-accent"
              >
                {saveState === 'saving' ? '保存中...' : '保存'}
              </button>
            </div>
          </div>
        </div>

        {updatedAt && (
          <div className="border-b border-slate-200 bg-slate-50 px-4 py-2 text-xs text-slate-500">
            最近保存 {new Date(updatedAt).toLocaleString()}
          </div>
        )}

        <div className="flex-1 p-4">
          {loading && <div className="status-muted">加载中...</div>}
          {loadError && <div className="status-error">加载失败: {loadError}</div>}

          {!loading && !loadError && (
            <textarea
              value={content}
              onChange={e => setContent(e.target.value)}
              rows={22}
              spellCheck={false}
              placeholder={'# 关于我\n\n# 偏好\n\n- 用中文回答。\n- 答案尽量简短。\n'}
              className="field-input mt-0 min-h-[32rem] resize-y font-mono leading-relaxed"
            />
          )}
        </div>
      </section>

      <aside className="page-surface overflow-hidden">
        <div className="panel-header">
          <div className="section-title">Context</div>
          <div className="mt-1 text-xl font-semibold text-slate-950">注入策略</div>
        </div>
        <div className="space-y-3 bg-slate-50/80 p-4">
          <InfoBlock title="作用范围" body="只影响你的会话，不会修改项目级 prompt 或 packaged skills。" />
          <InfoBlock title="建议内容" body="称呼、默认语气、项目偏好、永远要记住或避免的规则。" />
          <InfoBlock title="格式" body="支持 Markdown。分标题写更容易被模型正确引用。" />
          <div className="rounded-lg bg-slate-950 p-4 text-sm leading-6 text-white">
            <div className="text-xs font-semibold uppercase text-slate-400">Example</div>
            <code className="mt-2 block whitespace-pre-wrap text-xs text-cyan-100">
              {`# 偏好\n- 用中文回答\n- 先修 GitNexus 问题`}
            </code>
          </div>
        </div>
      </aside>
    </div>
  );
}

function InfoBlock({ title, body }: { title: string; body: string }) {
  return (
    <div className="rounded-lg border border-slate-200 bg-white p-3">
      <div className="text-sm font-semibold text-slate-950">{title}</div>
      <p className="mt-1 text-sm leading-6 text-slate-500">{body}</p>
    </div>
  );
}
