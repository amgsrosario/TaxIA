import { Navigate, Route, Routes } from "react-router-dom";
import { useAuth } from "./auth/AuthContext";
import { Layout } from "./components/Layout";
import { AdminUsersPage } from "./pages/AdminUsersPage";
import { AskPage } from "./pages/AskPage";
import { ForcedPasswordChangePage } from "./pages/ForcedPasswordChangePage";
import { LoginPage } from "./pages/LoginPage";
import { ProfilePage } from "./pages/ProfilePage";
import { QaDetailPage } from "./pages/QaDetailPage";
import { QaListPage } from "./pages/QaListPage";
import type { ReactNode } from "react";

function RequireAuth({ children }: { children: ReactNode }) {
  const { session } = useAuth();
  if (!session) return <Navigate to="/login" replace />;
  // Sessão restrita (ADR-006): só o ecrã de mudança de password. O backend impõe o mesmo.
  if (session.mustChangePassword) return <ForcedPasswordChangePage />;
  return <>{children}</>;
}

export function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route
        element={
          <RequireAuth>
            <Layout />
          </RequireAuth>
        }
      >
        <Route path="/ask" element={<AskPage />} />
        <Route path="/qa" element={<QaListPage />} />
        <Route path="/qa/:id" element={<QaDetailPage />} />
        <Route path="/profile" element={<ProfilePage />} />
        <Route path="/admin/users" element={<AdminUsersPage />} />
        <Route path="*" element={<Navigate to="/qa" replace />} />
      </Route>
    </Routes>
  );
}
