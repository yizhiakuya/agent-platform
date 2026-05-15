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
    <div className="flex min-h-screen items-center justify-center bg-slate-100 px-4 py-8">
      <form onSubmit={submit} className="page-panel w-full max-w-sm p-5 sm:p-6">
        <div className="mb-5">
          <div className="text-sm font-semibold text-slate-950">Agent Platform</div>
          <h1 className="mt-3 page-title">{mode === 'login' ? '登录' : '注册账号'}</h1>
          <p className="page-subtitle">进入你的移动 agent 控制台。</p>
        </div>
        <div className="space-y-4">
        <label className="block">
          <span className="field-label">用户名</span>
          <input
            type="text" autoComplete="username"
            value={username} onChange={e => setUsername(e.target.value)}
            className="field-input"
            required
          />
        </label>
        <label className="block">
          <span className="field-label">密码</span>
          <input
            type="password" autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
            value={password} onChange={e => setPassword(e.target.value)}
            className="field-input"
            required minLength={8}
          />
        </label>
        {error && <div className="status-error">{error}</div>}
        <button
          type="submit" disabled={busy}
          className="btn-primary w-full"
        >
          {busy ? '处理中...' : (mode === 'login' ? '登录' : '注册账号')}
        </button>
        <button
          type="button"
          className="btn-ghost w-full"
          onClick={() => setMode(mode === 'login' ? 'register' : 'login')}
        >
          {mode === 'login' ? '还没有账号?去注册' : '已有账号?去登录'}
        </button>
        </div>
      </form>
    </div>
  );
}
