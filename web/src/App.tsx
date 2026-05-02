import { Navigate, Route, Routes, Link, Outlet } from 'react-router-dom';
import LoginPage from './pages/LoginPage';
import DevicesPage from './pages/DevicesPage';
import ChatPage from './pages/ChatPage';
import SettingsPage from './pages/SettingsPage';
import { ChatStoreProvider } from './lib/chatStore';
import { isAuthed, setToken } from './lib/auth';

function RequireAuth({ children }: { children: React.ReactNode }) {
  return isAuthed() ? <>{children}</> : <Navigate to="/login" replace />;
}

function Shell() {
  return (
    <div className="min-h-screen flex flex-col">
      <header className="border-b bg-white">
        <div className="max-w-5xl mx-auto px-6 py-3 flex items-center justify-between">
          <Link to="/chat" className="font-semibold">Agent Platform</Link>
          <nav className="flex items-center gap-4 text-sm">
            <Link to="/devices" className="text-slate-600 hover:text-slate-900">设备</Link>
            <Link to="/chat" className="text-slate-600 hover:text-slate-900">聊天</Link>
            <Link to="/settings" className="text-slate-600 hover:text-slate-900">设置</Link>
            <button
              className="text-slate-500 hover:text-red-600"
              onClick={() => { setToken(null); window.location.assign('/login'); }}
            >退出登录</button>
          </nav>
        </div>
      </header>
      <main className="flex-1 max-w-5xl mx-auto w-full px-6 py-6">
        <Outlet />
      </main>
    </div>
  );
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route element={
        <RequireAuth>
          <ChatStoreProvider>
            <Shell />
          </ChatStoreProvider>
        </RequireAuth>
      }>
        <Route path="/devices" element={<DevicesPage />} />
        <Route path="/chat" element={<ChatPage />} />
        <Route path="/settings" element={<SettingsPage />} />
        <Route index element={<Navigate to="/chat" replace />} />
      </Route>
    </Routes>
  );
}
