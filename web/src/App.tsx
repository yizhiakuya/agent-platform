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
    <div className="app-shell flex flex-col">
      <header className="app-header">
        <div className="app-container flex min-h-14 flex-wrap items-center justify-between gap-3 py-2">
          <Link to="/chat" className="text-sm font-semibold tracking-normal text-slate-950">
            Agent Platform
          </Link>
          <nav className="flex min-w-0 flex-wrap items-center gap-1 text-sm">
            <Link to="/devices" className="btn-ghost min-h-9 px-2.5">设备</Link>
            <Link to="/chat" className="btn-ghost min-h-9 px-2.5">聊天</Link>
            <Link to="/settings" className="btn-ghost min-h-9 px-2.5">设置</Link>
            <button
              className="btn-ghost min-h-9 px-2.5 text-slate-500 hover:text-red-600"
              onClick={() => { setToken(null); window.location.assign('/login'); }}
            >
              退出登录
            </button>
          </nav>
        </div>
      </header>
      <main className="app-container flex-1 py-4 sm:py-6">
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
