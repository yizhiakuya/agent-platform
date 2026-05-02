// Tiny token store. localStorage is fine for self-host single-user — for a
// public-facing deployment we'd switch to a same-site, HttpOnly cookie set by
// gateway and stop persisting JWT to JS-readable storage.
const KEY = 'agent.token';

export function getToken(): string | null {
  return localStorage.getItem(KEY);
}

export function setToken(token: string | null): void {
  if (token) localStorage.setItem(KEY, token);
  else localStorage.removeItem(KEY);
}

export function isAuthed(): boolean {
  return !!getToken();
}
