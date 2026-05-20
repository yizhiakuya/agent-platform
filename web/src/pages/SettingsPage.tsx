import { useEffect, useState } from 'react';
import { api, ApiError, type MemoryFactDto } from '../api/client';

type SaveState = 'idle' | 'saving' | 'saved' | 'error';

export default function SettingsPage() {
  const [content, setContent] = useState('');
  const [autoMemoryEnabled, setAutoMemoryEnabled] = useState(true);
  const [updatedAt, setUpdatedAt] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [saveState, setSaveState] = useState<SaveState>('idle');
  const [saveError, setSaveError] = useState<string | null>(null);
  const [memories, setMemories] = useState<MemoryFactDto[]>([]);
  const [memoriesLoading, setMemoriesLoading] = useState(true);
  const [memoriesError, setMemoriesError] = useState<string | null>(null);
  const [deletingMemoryId, setDeletingMemoryId] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    api.getPreferences()
      .then(p => {
        if (cancelled) return;
        setContent(p.content ?? '');
        setAutoMemoryEnabled(p.autoMemoryEnabled ?? true);
        setUpdatedAt(p.updatedAt);
      })
      .catch((e: unknown) => {
        if (cancelled) return;
        setLoadError(e instanceof ApiError ? e.message : String(e));
      })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, []);

  useEffect(() => {
    let cancelled = false;
    setMemoriesLoading(true);
    setMemoriesError(null);
    api.listMemories({ limit: 100, includeRaw: true })
      .then(rows => { if (!cancelled) setMemories(rows); })
      .catch((e: unknown) => {
        if (!cancelled) setMemoriesError(e instanceof ApiError ? e.message : String(e));
      })
      .finally(() => { if (!cancelled) setMemoriesLoading(false); });
    return () => { cancelled = true; };
  }, []);

  async function save() {
    setSaveState('saving');
    setSaveError(null);
    try {
      const updated = await api.updatePreferences({ content, autoMemoryEnabled });
      setUpdatedAt(updated.updatedAt);
      setAutoMemoryEnabled(updated.autoMemoryEnabled ?? true);
      setSaveState('saved');
      setTimeout(() => setSaveState(s => (s === 'saved' ? 'idle' : s)), 2000);
    } catch (e) {
      setSaveState('error');
      setSaveError(e instanceof ApiError ? e.message : String(e));
    }
  }

  async function deleteMemory(memory: MemoryFactDto) {
    const confirmed = window.confirm('删除这条长期记忆？删除后不会再被召回。');
    if (!confirmed) return;
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

  return (
    <div className="workbench grid lg:grid-cols-[minmax(0,1fr)_20rem]">
      <section className="page-surface flex min-h-[calc(100vh-2rem)] min-w-0 flex-col overflow-hidden lg:min-h-0">
        <div className="panel-header">
          <div className="flex flex-col gap-4 sm:flex-row sm:items-start sm:justify-between">
            <div>
              <div className="section-title">Settings</div>
              <h1 className="mt-1 page-title">记忆与规则</h1>
              <p className="page-subtitle">管理会被带入对话的个人规则，以及 agent 保存的长期记忆。</p>
            </div>
            <div className="flex flex-wrap items-center gap-2 text-sm sm:justify-end">
              {saveState === 'saved' && <span className="font-semibold text-emerald-600">已保存</span>}
              {saveState === 'error' && (
                <span className="font-semibold text-red-600">保存失败{saveError ? `: ${saveError}` : ''}</span>
              )}
              <button
                onClick={save}
                disabled={loading || saveState === 'saving'}
                className="btn-primary"
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

        <div className="flex-1 space-y-4 p-4">
          {loading && <div className="status-muted">加载中...</div>}
          {loadError && <div className="status-error">加载失败: {loadError}</div>}

          {!loading && !loadError && (
            <>
              <div className="border-b border-slate-200 pb-4">
                <div className="flex flex-col gap-4 sm:flex-row sm:items-center sm:justify-between">
                  <div className="min-w-0">
                    <div id="auto-memory-title" className="text-sm font-semibold text-slate-950">
                      自动长期记忆
                    </div>
                    <p id="auto-memory-help" className="mt-1 text-sm leading-6 text-slate-500">
                      开启后会自动召回相关记忆，并在对话后提取可复用事实。关闭后已有记忆保留，下方仍可查看和删除。
                    </p>
                  </div>
                  <label className="inline-flex shrink-0 items-center gap-3">
                    <input
                      id="auto-memory-enabled"
                      type="checkbox"
                      role="switch"
                      checked={autoMemoryEnabled}
                      onChange={e => setAutoMemoryEnabled(e.target.checked)}
                      aria-labelledby="auto-memory-title"
                      aria-describedby="auto-memory-help"
                      className="peer sr-only"
                    />
                    <span
                      className={[
                        'relative inline-flex h-7 w-12 rounded-full transition peer-focus:outline-none peer-focus:ring-2 peer-focus:ring-blue-500 peer-focus:ring-offset-2',
                        autoMemoryEnabled ? 'bg-blue-600' : 'bg-slate-300'
                      ].join(' ')}
                    >
                      <span
                        className={[
                          'absolute left-1 top-1 h-5 w-5 rounded-full bg-white shadow-sm transition',
                          autoMemoryEnabled ? 'translate-x-5' : ''
                        ].join(' ')}
                      />
                    </span>
                    <span className="w-10 text-sm font-semibold text-slate-700">
                      {autoMemoryEnabled ? '开启' : '关闭'}
                    </span>
                  </label>
                </div>
              </div>

              <div className="space-y-3">
                <div>
                  <div className="text-sm font-semibold text-slate-950">个人规则</div>
                  <p className="mt-1 text-sm leading-6 text-slate-500">
                    这段文本会直接进入每次对话的用户上下文，适合放固定偏好、称呼和项目级规则。
                  </p>
                </div>
                <textarea
                  value={content}
                  onChange={e => setContent(e.target.value)}
                  rows={16}
                  spellCheck={false}
                  placeholder={'# 关于我\n\n# 偏好\n\n- 用中文回答。\n- 答案尽量简短。\n'}
                  className="field-input mt-0 min-h-[24rem] resize-y font-mono leading-relaxed"
                />
              </div>

              <div className="border-t border-slate-200 pt-4">
                <div className="flex flex-col gap-2 sm:flex-row sm:items-start sm:justify-between">
                  <div>
                    <div className="text-sm font-semibold text-slate-950">长期记忆</div>
                    <p className="mt-1 text-sm leading-6 text-slate-500">
                      这些是 agent 通过记忆工具或自动提取保存的事实、偏好、规则和经验。
                    </p>
                  </div>
                  <button
                    type="button"
                    onClick={() => {
                      setMemoriesLoading(true);
                      setMemoriesError(null);
                      api.listMemories({ limit: 100, includeRaw: true })
                        .then(setMemories)
                        .catch((e: unknown) => setMemoriesError(e instanceof ApiError ? e.message : String(e)))
                        .finally(() => setMemoriesLoading(false));
                    }}
                    disabled={memoriesLoading}
                    className="btn-secondary self-start"
                  >
                    {memoriesLoading ? '刷新中...' : '刷新'}
                  </button>
                </div>

                <div className="mt-3 space-y-2">
                  {memoriesLoading && <div className="status-muted">加载长期记忆中...</div>}
                  {memoriesError && <div className="status-error">长期记忆加载失败: {memoriesError}</div>}
                  {!memoriesLoading && !memoriesError && memories.length === 0 && (
                    <div className="rounded-lg border border-dashed border-slate-300 bg-slate-50 p-4 text-sm text-slate-500">
                      还没有长期记忆。
                    </div>
                  )}
                  {!memoriesLoading && memories.length > 0 && (
                    <div className="space-y-2">
                      {memories.map(memory => (
                        <MemoryRow
                          key={memory.id}
                          memory={memory}
                          deleting={deletingMemoryId === memory.id}
                          onDelete={() => deleteMemory(memory)}
                        />
                      ))}
                    </div>
                  )}
                </div>
              </div>
            </>
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
          <InfoBlock title="自动记忆" body="开关控制自动召回和自动保存。关闭后历史记忆保留，设置页仍可查看和删除。" />
          <InfoBlock title="长期记忆" body="由 agent 保存到记忆库，适合可复用事实、稳定偏好、规则和经验。" />
          <InfoBlock title="建议内容" body="称呼、默认语气、项目偏好、永远要记住或避免的规则。" />
          <InfoBlock title="格式" body="支持 Markdown。分标题写更容易被模型正确引用。" />
          <div className="rounded-lg border border-slate-200 bg-white p-4 text-sm leading-6 text-slate-700">
            <div className="text-xs font-semibold uppercase text-slate-500">Example</div>
            <code className="mt-2 block whitespace-pre-wrap rounded-md bg-slate-50 p-3 text-xs text-slate-700">
              {`# 偏好\n- 用中文回答\n- 先修 GitNexus 问题`}
            </code>
          </div>
        </div>
      </aside>
    </div>
  );
}

function MemoryRow({
  memory,
  deleting,
  onDelete
}: {
  memory: MemoryFactDto;
  deleting: boolean;
  onDelete: () => void;
}) {
  const curated = isCurated(memory);
  return (
    <div className="rounded-lg border border-slate-200 bg-white p-4">
      <div className="flex flex-col gap-3 sm:flex-row sm:items-start sm:justify-between">
        <div className="min-w-0 space-y-2">
          <div className="flex flex-wrap items-center gap-2 text-xs">
            <span className="rounded bg-slate-100 px-2 py-1 font-semibold uppercase text-slate-600">
              {kindLabel(memory.kind)}
            </span>
            <span className={[
              'rounded px-2 py-1 font-semibold',
              curated ? 'bg-emerald-50 text-emerald-700' : 'bg-amber-50 text-amber-700'
            ].join(' ')}>
              {curated ? '已确认' : '自动提取'}
            </span>
            <span className="text-slate-400">{formatDate(memory.createdAt)}</span>
            <span className="text-slate-400">召回 {memory.accessCount ?? 0} 次</span>
          </div>
          <p className="whitespace-pre-wrap break-words text-sm leading-6 text-slate-800">{memory.content}</p>
        </div>
        <button
          type="button"
          onClick={onDelete}
          disabled={deleting}
          className="btn-secondary shrink-0 text-red-600 hover:border-red-200 hover:bg-red-50 hover:text-red-700"
        >
          {deleting ? '删除中...' : '删除'}
        </button>
      </div>
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

function isCurated(memory: MemoryFactDto) {
  return Boolean(memory.isCurated ?? memory.curated);
}
