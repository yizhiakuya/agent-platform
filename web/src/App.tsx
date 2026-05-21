import { Navigate, NavLink, Outlet, Route, Routes } from 'react-router-dom';
import LoginPage from './pages/LoginPage';
import DevicesPage from './pages/DevicesPage';
import ChatPage from './pages/ChatPage';
import VisualChatPage from './pages/VisualChatPage';
import SettingsPage from './pages/SettingsPage';
import { ChatStoreProvider } from './lib/chatStore';
import { isAuthed, setToken } from './lib/auth';

function RequireAuth({ children }: { children: React.ReactNode }) {
  return isAuthed() ? <>{children}</> : <Navigate to="/login" replace />;
}

function Shell() {
  const navItems = [
    { to: '/chat', label: '视觉会话', mark: 'V' },
    { to: '/chat/classic', label: '经典聊天', mark: 'C' },
    { to: '/devices', label: '设备', mark: 'D' },
    { to: '/settings', label: '设置', mark: 'S' }
  ];

  return (
    <div className="app-shell">
      <aside className="app-rail">
        <div className="brand-lockup">
          <div className="brand-mark">AP</div>
          <div className="min-w-0">
            <div className="brand-name">Agent Platform</div>
            <div className="brand-subtitle">Mobile agent console</div>
          </div>
        </div>

        <nav className="rail-nav" aria-label="主导航">
          {navItems.map(item => (
            <NavLink
              key={item.to}
              to={item.to}
              className={({ isActive }) => ['rail-link', isActive ? 'rail-link-active' : ''].join(' ')}
            >
              <span className="rail-link-mark">{item.mark}</span>
              <span>{item.label}</span>
            </NavLink>
          ))}
        </nav>

        <div className="rail-footer">
          <div className="rail-status">
            <span className="status-dot bg-emerald-400" />
            <span>平台在线</span>
          </div>
          <button
            className="rail-logout"
            onClick={() => {
              setToken(null);
              window.location.assign('/login');
            }}
          >
            退出登录
          </button>
        </div>
      </aside>

      <main className="app-main">
        <Outlet />
      </main>
    </div>
  );
}

export default function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route
        path="/chat"
        element={
          <ChatStoreProvider>
            <VisualChatPage />
          </ChatStoreProvider>
        }
      />
      <Route
        element={
          <RequireAuth>
            <ChatStoreProvider>
              <Shell />
            </ChatStoreProvider>
          </RequireAuth>
        }
      >
        <Route path="/devices" element={<DevicesPage />} />
        <Route path="/chat/classic" element={<ChatPage />} />
        <Route path="/settings" element={<SettingsPage />} />
        <Route index element={<Navigate to="/chat" replace />} />
      </Route>
    </Routes>
  );
}
