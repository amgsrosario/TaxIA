import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";
import { login as apiLogin, setAuthToken, setUnauthorizedHandler } from "../api/client";
import type { LoginResponse } from "../api/types";

/**
 * Sessão do piloto: o JWT vive em memória e em sessionStorage (sobrevive a
 * refresh, morre ao fechar o separador). Decisão deliberada e documentada
 * para o backoffice de piloto — sem refresh tokens nem persistência longa.
 */
const STORAGE_KEY = "taxia.pilot.session";

export interface SessionInfo {
  token: string;
  email: string;
  fullName: string;
  organizationId: string;
  roles: string[];
  expiresAt: string;
}

interface AuthContextValue {
  session: SessionInfo | null;
  signIn: (email: string, password: string) => Promise<void>;
  signOut: () => void;
}

const AuthContext = createContext<AuthContextValue | null>(null);

function loadStoredSession(): SessionInfo | null {
  try {
    const raw = sessionStorage.getItem(STORAGE_KEY);
    if (!raw) return null;
    const session = JSON.parse(raw) as SessionInfo;
    if (new Date(session.expiresAt).getTime() <= Date.now()) {
      sessionStorage.removeItem(STORAGE_KEY);
      return null;
    }
    return session;
  } catch {
    return null;
  }
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<SessionInfo | null>(() => {
    const stored = loadStoredSession();
    if (stored) setAuthToken(stored.token);
    return stored;
  });

  const signOut = useCallback(() => {
    setAuthToken(null);
    sessionStorage.removeItem(STORAGE_KEY);
    setSession(null);
  }, []);

  useEffect(() => {
    // Qualquer 401 da API termina a sessão local.
    setUnauthorizedHandler(signOut);
  }, [signOut]);

  const signIn = useCallback(async (email: string, password: string) => {
    const response: LoginResponse = await apiLogin({ email, password });
    const newSession: SessionInfo = {
      token: response.accessToken,
      email: response.email,
      fullName: response.fullName,
      organizationId: response.organizationId,
      roles: response.roles,
      expiresAt: response.expiresAt,
    };
    setAuthToken(newSession.token);
    sessionStorage.setItem(STORAGE_KEY, JSON.stringify(newSession));
    setSession(newSession);
  }, []);

  const value = useMemo(
    () => ({ session, signIn, signOut }),
    [session, signIn, signOut],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error("useAuth requer AuthProvider");
  return ctx;
}
