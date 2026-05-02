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
    <div className="min-h-screen flex items-center justify-center bg-slate-50">
      <form onSubmit={submit} className="w-full max-w-sm bg-white border rounded-lg shadow-sm p-6 space-y-4">
        <h1 className="text-xl font-semibold">{mode === 'login' ? '登录' : '注册账号'}</h1>
        <label className="block">
          <span className="text-sm text-slate-600">用户名</span>
          <input
            type="text" autoComplete="username"
            value={username} onChange={e => setUsername(e.target.value)}
            className="mt-1 w-full border rounded px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500"
            required
          />
        </label>
        <label className="block">
          <span className="text-sm text-slate-600">密码</span>
          <input
            type="password" autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
            value={password} onChange={e => setPassword(e.target.value)}
            className="mt-1 w-full border rounded px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500"
            required minLength={8}
          />
        </label>
        {error && <div className="text-sm text-red-600">{error}</div>}
        <button
          type="submit" disabled={busy}
          className="w-full bg-blue-600 hover:bg-blue-700 disabled:opacity-50 text-white py-2 rounded"
        >{busy ? '…' : (mode === 'login' ? '登录' : '注册账号')}</button>
        <button
          type="button"
          className="w-full text-sm text-slate-500 hover:text-slate-800"
          onClick={() => setMode(mode === 'login' ? 'register' : 'login')}
        >{mode === 'login' ? '还没有账号?去注册' : '已有账号?去登录'}</button>
      </form>
    </div>
  );
}
