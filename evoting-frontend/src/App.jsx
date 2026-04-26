import { useState, useEffect } from "react";
import { Routes, Route, Navigate, useLocation } from "react-router-dom";
import { useAuth }       from "./context/AuthContext.jsx";
import { useKeypair }    from "./context/KeypairContext.jsx";
import { Sidebar, Topbar } from "./components/Layout.jsx";
import LoginPage          from "./pages/LoginPage.jsx";
import DashboardView      from "./pages/DashboardView.jsx";
import ElectionsView      from "./pages/ElectionsView.jsx";
import VotersView         from "./pages/VotersView.jsx";
import EnrollmentView     from "./pages/EnrollmentView.jsx";
import TallyView          from "./pages/TallyView.jsx";
import AuditView          from "./pages/AuditView.jsx";
import TerminalsView      from "./pages/TerminalsView.jsx";
import ReceiptTrackerView from "./pages/ReceiptTrackerView.jsx";
import HardwareSimulator  from "./pages/HardwareSimulator.jsx";
import SettingsView       from "./pages/SettingsView.jsx";
import AdminUsersView     from "./pages/AdminUsersView.jsx";
import ImportView         from "./pages/ImportView.jsx";
import ErrorBoundary      from "./components/ErrorBoundary.jsx";
import ApprovalsView      from "./pages/ApprovalsView.jsx";
import RegistrationView   from "./pages/RegistrationView.jsx";
import ResetPasswordPage  from "./pages/ResetPasswordPage.jsx";
import OAuthCallback      from "./components/OAuthCallback";
import PopupRenderer from "./components/PopupRenderer.jsx";

function RequireAuth({ children }) {
  const { user } = useAuth();
  const loc = useLocation();
  if (!user) return <Navigate to="/login" state={{ from: loc }} replace />;
  return children;
}

function AppShell() {
  const [view,      setView]      = useState("dashboard");
  const [settingsTab, setSettingsTab] = useState(null);
  const [collapsed, setCollapsed] = useState(false);
  const { user, logout, sessionWarning, setSessionWarning } = useAuth();
  const keypair                   = useKeypair();

  // Listen for navigation events from StepUpModal etc.
  useEffect(() => {
    const handler = (e) => {
      if (e.detail?.view) setView(e.detail.view);
      if (e.detail?.tab)  setSettingsTab(e.detail.tab);
    };
    window.addEventListener("evoting:navigate", handler);
    return () => window.removeEventListener("evoting:navigate", handler);
  }, []);
  const isSuperAdmin = user?.role === "SUPER_ADMIN";

  const wrap = (title, el) => (
    <ErrorBoundary title={title}>{el}</ErrorBoundary>
  );

  const ViewMap = {
    dashboard:    wrap("Dashboard",          <DashboardView />),
    elections:    wrap("Elections",          <ElectionsView />),
    voters:       wrap("Voter Registry",     <VotersView />),
    terminals:    wrap("Terminals",          <TerminalsView />),
    enrollment:   wrap("Enrollment",         <EnrollmentView />),
    tally:        wrap("Live Tally",         <TallyView />),
    audit:        wrap("Audit Log",          <AuditView />),
    receipt:      wrap("Receipt Tracker",    <ReceiptTrackerView />),
    adminusers:   wrap("Admin Users",        <AdminUsersView />),
    import:       wrap("Import Candidates",  <ImportView />),
    approvals:    wrap("Pending Approvals",  <ApprovalsView />),
    registration: wrap("Registration",       <RegistrationView />),
    settings:     wrap("Settings",           <SettingsView />),
  };

  return (
    <>
      {/* Session expiry warning banner */}
      {sessionWarning && (
        <div className="fixed top-0 left-0 right-0 z-[300] bg-orange-500/95 backdrop-blur-sm
                        text-white text-xs font-semibold flex items-center justify-center
                        gap-4 px-6 py-2.5 shadow-lg">
          <span>⚠ Your session expires in less than 2 minutes.</span>
          <button
            onClick={async () => {
              // Re-login silently is not possible without credentials —
              // redirect to login so the user can re-authenticate.
              setSessionWarning(false);
              await logout();
            }}
            className="underline underline-offset-2 hover:no-underline transition-all">
            Log in again
          </button>
          <button onClick={() => setSessionWarning(false)}
            className="ml-2 opacity-70 hover:opacity-100">✕</button>
        </div>
      )}
    <div className="flex min-h-screen bg-bg" style={{ paddingTop: sessionWarning ? 40 : 0 }}>
      <Sidebar
        active={view} setActive={setView}
        collapsed={collapsed} setCollapsed={setCollapsed}
      />
      <main className="flex-1 flex flex-col overflow-auto min-w-0">
        <Topbar view={view} setView={setView} />
        <div key={view} className="flex-1 animate-fade-in">
          {ViewMap[view] ?? <DashboardView />}
        </div>
      </main>
    </div>
    </>
  );
}

export default function App() {
  const { user } = useAuth();
  return (
      <>
      <PopupRenderer />

    <Routes>
      <Route path="/login"        element={user ? <Navigate to="/" replace /> : <LoginPage />} />
       <Route path="/oauth-callback" element={<OAuthCallback />} />
      <Route path="/reset-password" element={<ResetPasswordPage />} />
      <Route path="/verify"    element={<ReceiptTrackerView />} />
      <Route path="/simulator" element={<HardwareSimulator />} />
      <Route path="/*" element={
        <RequireAuth>
          <AppShell />
        </RequireAuth>
      } />
    </Routes>
    </>
  );
}
