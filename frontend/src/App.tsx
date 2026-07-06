import { Navigate, Route, Routes } from "react-router-dom";
import { useAuth } from "./auth/AuthContext";
import { Layout } from "./components/Layout";
import { LoginPage } from "./pages/LoginPage";
import { QaDetailPage } from "./pages/QaDetailPage";
import { QaListPage } from "./pages/QaListPage";
import type { ReactNode } from "react";

function RequireAuth({ children }: { children: ReactNode }) {
  const { session } = useAuth();
  if (!session) return <Navigate to="/login" replace />;
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
        <Route path="/qa" element={<QaListPage />} />
        <Route path="/qa/:id" element={<QaDetailPage />} />
        <Route path="*" element={<Navigate to="/qa" replace />} />
      </Route>
    </Routes>
  );
}
