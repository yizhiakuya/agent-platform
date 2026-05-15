import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { api, ApiError } from '../api/client';
import { setToken } from '../lib/auth';

export default function LoginPage() {
  const [username, setUsername] = useState('admin');
  const [password, setPassword] = useState('');
  const [mode, setMode] = useState<'login' | 'register'>('login');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const nav = useNavigate();

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      if (mode === 'register') {
        await api.register(username, password);
      }
      const r = await api.login(username, password);
      setToken(r.token);
      nav('/chat');
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="grid min-h-screen bg-slate-950 text-white lg:grid-cols-[minmax(0,1fr)_28rem]">
      <section className="relative flex min-h-[42vh] items-end overflow-hidden p-6 sm:p-10 lg:min-h-screen">
        <div className="absolute inset-0 bg-[radial-gradient(circle_at_18%_20%,rgba(8,168,181,0.32),transparent_34%),radial-gradient(circle_at_80%_70%,rgba(227,154,40,0.20),transparent_30%)]" />
        <div className="absolute inset-x-0 bottom-0 h-1/2 bg-gradient-to-t from-slate-950 to-transparent" />
        <div className="relative max-w-3xl">
          <div className="inline-flex items-center gap-2 rounded-md border border-white/10 bg-white/10 px-3 py-2 text-xs text-cyan-100">
            <span className="h-2 w-2 rounded-full bg-emerald-300" />
            Private mobile agent console
          </div>
          <h1 className="mt-6 max-w-2xl text-4xl font-semibold leading-tight sm:text-6xl">
            Agent Platform
          </h1>
          <p className="mt-5 max-w-xl text-base leading-7 text-slate-300">
            连接手机、管理会话、让 agent 通过设备工具完成真实操作。
          </p>
          <div className="mt-8 grid max-w-xl gap-3 sm:grid-cols-3">
            <LoginMetric label="Device tools" value="Live" />
            <LoginMetric label="Photo context" value="Ready" />
            <LoginMetric label="Sessions" value="Synced" />
          </div>
        </div>
      </section>

      <section className="flex items-center justify-center bg-white px-4 py-8 text-slate-950 lg:min-h-screen">
        <form onSubmit={submit} className="w-full max-w-sm">
          <div className="mb-8">
            <div className="text-xs font-semibold uppercase text-slate-500">Access</div>
            <h2 className="mt-2 text-3xl font-semibold">{mode === 'login' ? '登录控制台' : '注册账号'}</h2>
            <p className="mt-2 text-sm leading-6 text-slate-500">进入你的移动 agent 工作区。</p>
          </div>
          <div className="space-y-4">
            <label className="block">
              <span className="field-label">用户名</span>
              <input
                type="text"
                autoComplete="username"
                value={username}
                onChange={e => setUsername(e.target.value)}
                className="field-input"
                required
              />
            </label>
            <label className="block">
              <span className="field-label">密码</span>
              <input
                type="password"
                autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
                value={password}
                onChange={e => setPassword(e.target.value)}
                className="field-input"
                required
                minLength={8}
              />
            </label>
            {error && <div className="status-error">{error}</div>}
            <button type="submit" disabled={busy} className="btn-accent w-full">
              {busy ? '处理中...' : (mode === 'login' ? '登录' : '注册并登录')}
            </button>
            <button
              type="button"
              className="btn-secondary w-full"
              onClick={() => setMode(mode === 'login' ? 'register' : 'login')}
            >
              {mode === 'login' ? '还没有账号，去注册' : '已有账号，去登录'}
            </button>
          </div>
        </form>
      </section>
    </div>
  );
}

function LoginMetric({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-lg border border-white/10 bg-white/10 p-3">
      <div className="text-[11px] uppercase text-slate-400">{label}</div>
      <div className="mt-2 text-sm font-semibold text-white">{value}</div>
    </div>
  );
}
