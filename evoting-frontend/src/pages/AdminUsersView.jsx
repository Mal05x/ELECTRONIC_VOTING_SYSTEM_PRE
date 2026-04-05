import { initiateStateChange, signStateChange } from "../api/multisig.js";
import { useKeypair } from "../context/KeypairContext.jsx";
import { useState, useEffect } from "react";
import { useAuth } from "../context/AuthContext.jsx";
import {
  getAdminUsers, createAdminUser, changeAdminRole,
  deactivateAdmin, activateAdmin,
} from "../api/adminUsers.js";
import { SectionHeader, Ic, Spinner, StatusBadge } from "../components/ui.jsx";

const ROLES     = ["SUPER_ADMIN", "ADMIN", "OBSERVER"];
const ROLE_COLORS = {
  SUPER_ADMIN: "text-danger",
  ADMIN:       "text-purple-300",
  OBSERVER:    "text-sub",
};


function Toast({ msg, type, onClose }) {
  if (!msg) return null;
  return (
    <div className={`fixed bottom-6 right-6 z-50 flex items-center gap-3 px-5 py-3.5
                     rounded-2xl border shadow-card text-sm font-semibold animate-slide-in
                     ${type === "error"
                       ? "bg-card border-red-500/30 text-danger"
                       : "bg-card border-purple-500/30 text-purple-300"}`}>
      <Ic n={type === "error" ? "warning" : "check"} s={15} />
      {msg}
      <button onClick={onClose} className="text-muted hover:text-sub ml-1">
        <Ic n="close" s={12} />
      </button>
    </div>
  );
}

export default function AdminUsersView() {
  const { user } = useAuth();
  const isSuperAdmin = user?.role === "SUPER_ADMIN";

  //const { signChallenge } = useKeypair();
  const { hasLocalKey, hasServerKey, signChallenge } = useKeypair();
  const [confirmAuth, setConfirmAuth] = useState(null);
  const [users,    setUsers]    = useState([]);
  const [loading,  setLoading]  = useState(true);
  const [working,  setWorking]  = useState(null); // id being acted on
  const [toast,    setToast]    = useState({ msg: "", type: "success" });
  const [showCreate, setShowCreate] = useState(false);

  // New user form state
  const [newUsername, setNewUsername] = useState("");
  const [newPassword, setNewPassword] = useState("");
  const [newRole,     setNewRole]     = useState("ADMIN");
  const [creating,    setCreating]    = useState(false);

  const showToast = (msg, type = "success") => {
    setToast({ msg, type });
    setTimeout(() => setToast({ msg: "", type: "success" }), 3500);
  };

  const load = () => {
    setLoading(true);
    getAdminUsers()
      .then(setUsers)
      .catch(e => showToast(e.response?.data?.error || "Failed to load users", "error"))
      .finally(() => setLoading(false));
  };

  useEffect(load, []);

  const handleCreate = async (e) => {
    e.preventDefault();
    if (!newUsername.trim() || !newPassword.trim()) {
      showToast("Username and password are required", "error"); return;
    }
    if (newPassword.length < 8) {
      showToast("Password must be at least 8 characters", "error"); return;
    }
    setCreating(true);
    try {
      await createAdminUser({ username: newUsername.trim(), password: newPassword, role: newRole });
      showToast(`Account created for ${newUsername.trim()}`);
      setNewUsername(""); setNewPassword(""); setNewRole("ADMIN");
      setShowCreate(false);
      load();
    } catch (e) {
      showToast(e.response?.data?.error || "Failed to create account", "error");
    } finally { setCreating(false); }
  };

  const handleRoleChange = async (u, role) => {
    setWorking(u.id);
    try {
      await changeAdminRole(u.id, role);
      setUsers(p => p.map(x => x.id === u.id ? { ...x, role } : x));
      showToast(`${u.username} role changed to ${role}`);
    } catch (e) {
      showToast(e.response?.data?.error || "Failed to change role", "error");
    } finally { setWorking(null); }
  };

 const handleAuthorizeAction = async () => {
     if (!confirmAuth) return;
     setWorking(confirmAuth.id);

     const actionType = confirmAuth.active ? "DEACTIVATE_ADMIN" : "ACTIVATE_ADMIN";
     // Adding a description guarantees it renders perfectly in your Approvals table
     const description = `Request to ${confirmAuth.active ? 'deactivate' : 'reactivate'} admin account: ${confirmAuth.username}`;

     try {
       // 1. Propose the action to the backend
       await initiateStateChange(actionType, confirmAuth.id.toString(), description);

       // 2. Close the modal
       setConfirmAuth(null);

       // 3. Direct the Superadmin to the next step
       showToast(`Authorised! Go to the Approvals tab to sign and execute.`, "success");

     } catch (e) {
       console.error("Action Error:", e);
       showToast(e.response?.data?.error || "Failed to push to approvals", "error");
     } finally {
       setWorking(null);
     }
   };

  const counts = {
    total:    users.length,
    active:   users.filter(u => u.active).length,
    superAdmin: users.filter(u => u.role === "SUPER_ADMIN").length,
  };

  return (
    <div className="p-7 flex flex-col gap-5">
      <Toast msg={toast.msg} type={toast.type} onClose={() => setToast({ msg: "", type: "success" })} />

      {/* Stats */}
      <div className="grid grid-cols-3 gap-4">
        {[
          { label: "Total Admins",  value: counts.total,      icon: "voters",  accent: "purple" },
          { label: "Active",        value: counts.active,     icon: "check",   accent: "green"  },
          { label: "Super Admins",  value: counts.superAdmin, icon: "shield",  accent: "red"    },
        ].map((s, i) => (
          <div key={s.label} className="c-card p-5 flex items-center justify-between animate-fade-up"
               style={{ animationDelay: `${i * 50}ms` }}>
            <div>
              <div className="sect-lbl mb-1">{s.label}</div>
              <div className="text-2xl font-bold text-ink mono">{s.value}</div>
            </div>
            <div className={`w-10 h-10 rounded-xl flex items-center justify-center border
                            ${s.accent === "purple" ? "bg-purple-500/15 border-purple-500/25"
                            : s.accent === "green"  ? "bg-green-500/15 border-green-500/25"
                            : "bg-red-500/15 border-red-500/25"}`}>
              <Ic n={s.icon} s={18}
                  c={s.accent === "purple" ? "#A78BFA" : s.accent === "green" ? "#34D399" : "#F87171"} />
            </div>
          </div>
        ))}
      </div>

      <div className="c-card p-6 animate-fade-up" style={{ animationDelay: "150ms" }}>
        <SectionHeader
          title="Admin Accounts"
          sub="Manage election official access. SUPER_ADMIN role required to make changes."
          action={
            isSuperAdmin ? (
              <button className="btn btn-primary btn-sm" onClick={() => setShowCreate(v => !v)}>
                <Ic n="plus" s={14} c="#fff" sw={2.5} />
                New Admin
              </button>
            ) : null
          }
        />

        {/* Create form */}
        {showCreate && isSuperAdmin && (
          <form onSubmit={handleCreate}
                className="mb-6 p-5 rounded-2xl bg-elevated border border-border animate-fade-up">
            <div className="text-sm font-bold text-ink mb-4">Create New Admin Account</div>
            <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
              <div>
                <label className="block text-xs font-semibold text-sub mb-1.5 uppercase tracking-wide">
                  Username
                </label>
                <input className="inp inp-md" placeholder="admin_username"
                  value={newUsername} onChange={e => setNewUsername(e.target.value)} />
              </div>
              <div>
                <label className="block text-xs font-semibold text-sub mb-1.5 uppercase tracking-wide">
                  Password (min. 8 chars)
                </label>
                <input className="inp inp-md" type="password" placeholder="••••••••"
                  value={newPassword} onChange={e => setNewPassword(e.target.value)} />
              </div>
              <div>
                <label className="block text-xs font-semibold text-sub mb-1.5 uppercase tracking-wide">
                  Role
                </label>
                <select className="inp inp-md" value={newRole} onChange={e => setNewRole(e.target.value)}>
                  {ROLES.map(r => <option key={r} value={r}>{r}</option>)}
                </select>
              </div>
            </div>
            <div className="flex gap-3 mt-4">
              <button type="submit" disabled={creating}
                className="btn btn-primary btn-sm justify-center min-w-[120px]">
                {creating ? <Spinner s={14} /> : "Create Account"}
              </button>
              <button type="button" className="btn btn-ghost btn-sm"
                onClick={() => setShowCreate(false)}>Cancel</button>
            </div>
          </form>
        )}

        {/* Table header */}
        <div className="hidden xl:grid px-4 py-2 mb-1 gap-3"
          style={{ gridTemplateColumns: "1fr 180px 100px 120px 160px 120px" }}>
          {["Username", "Email", "Role", "Status", "Last Login", "Actions"].map(h => (
            <span key={h} className="sect-lbl">{h}</span>
          ))}
        </div>
        <hr className="divider mb-1" />

        {loading ? (
          <div className="flex justify-center py-16"><Spinner s={28} /></div>
        ) : users.length === 0 ? (
          <div className="py-8 text-center text-xs text-muted">No admin users found.</div>
        ) : (
          users.map((u, i) => (
            <div key={u.id}
              className={`trow animate-fade-up ${!u.active ? "opacity-50" : ""}`}
              style={{ gridTemplateColumns: "1fr 180px 100px 120px 160px 120px",
                       gap: "12px", animationDelay: `${i * 20}ms` }}>

             {/* Username */}
                           <div className="flex items-center gap-2.5">
                             <div className="w-7 h-7 rounded-lg flex items-center justify-center
                                             text-[11px] font-extrabold text-white flex-shrink-0"
                                  style={{ backgroundColor: "#8B5CF6" }}>
                               {(u.displayName?.[0] || u.username[0] || "A").toUpperCase()}
                             </div>
                             {/* USE 'u' HERE, NOT 'user' */}
                             <span className="font-bold text-ink">{u.displayName || u.username}</span>

                             {/* Compare the immutable login IDs to find out if this row is YOU */}
                             {u.username === user?.username && (
                               <span className="badge badge-purple text-[9px]">You</span>
                             )}
                           </div>

              {/* Email */}
              <span className="text-xs text-sub truncate hidden xl:block">
                {u.email || "—"}
              </span>

              {/* Role selector */}
              <div className="hidden xl:block">
                {isSuperAdmin && u.username !== user?.username ? (
                  <select
                    className={`inp text-[11px] py-1 px-2 ${ROLE_COLORS[u.role] || ""}`}
                    value={u.role}
                    disabled={working === u.id}
                    onChange={e => handleRoleChange(u, e.target.value)}>
                    {ROLES.map(r => <option key={r} value={r}>{r}</option>)}
                  </select>
                ) : (
                  <span className={`text-[11px] font-bold ${ROLE_COLORS[u.role] || "text-sub"}`}>
                    {u.role}
                  </span>
                )}
              </div>

              {/* Status */}
              <span className="hidden xl:block">
                <StatusBadge status={u.active ? "ACTIVE" : "CLOSED"} />
              </span>

              {/* Last login */}
              <span className="mono text-[10px] text-muted hidden xl:block">
                {u.lastLogin
                  ? new Date(u.lastLogin).toLocaleDateString("en-NG",
                      { day: "2-digit", month: "short", year: "numeric" })
                  : "Never"}
              </span>

            {/* Actions */}
                          <div className="flex gap-1.5">
                            {isSuperAdmin && u.username !== user?.username && (
                              <button
                                className={`btn btn-sm ${u.active ? "btn-danger" : "btn-success"} !text-[11px]`}
                                onClick={() => setConfirmAuth(u)}> {/* <-- THIS OPENS THE MODAL */}
                                <Ic n={u.active ? "lock" : "unlock"} s={11} />
                                {u.active ? "Deactivate" : "Activate"}
                              </button>
                            )}
                          </div>
            </div>
          ))
        )}
      </div>
      {/* Setup Authorise Modal */}
            {confirmAuth && (
              <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 backdrop-blur-sm animate-fade-in">
                <div className="bg-card p-6 rounded-2xl w-[400px] border border-border shadow-2xl animate-slide-up flex flex-col gap-4">

                  <div className="flex items-center gap-3 text-ink">
                    <div className="w-10 h-10 rounded-full bg-purple-500/20 flex items-center justify-center">
                      <Ic n="shield" s={20} c="#A78BFA" />
                    </div>
                    <div>
                      <h3 className="text-lg font-bold">Setup Authorisation</h3>
                      <div className="text-xs text-sub">Multi-Sig Proposal</div>
                    </div>
                  </div>

                  <p className="text-sm text-muted leading-relaxed">
                    You are about to propose the <strong className="text-ink">{confirmAuth.active ? 'deactivation' : 'activation'}</strong> of the profile for <span className="text-purple-400 font-bold">{confirmAuth.displayName || confirmAuth.username}</span>.
                  </p>

                  <div className="p-3 bg-elevated rounded-xl border border-border text-xs text-sub">
                    This action will be sent to the Approvals queue. It will not take effect until it is cryptographically signed.
                  </div>

                  <div className="flex gap-3 justify-end mt-2">
                    <button
                      className="btn btn-ghost btn-sm"
                      onClick={() => setConfirmAuth(null)}
                      disabled={working === confirmAuth.id}
                    >
                      Cancel
                    </button>
                    <button
                       className="btn btn-primary btn-sm min-w-[100px] justify-center"
                       onClick={handleAuthorizeAction}
                       disabled={working === confirmAuth.id}
                    >
                       {working === confirmAuth.id ? <Spinner s={14} /> : "Authorise"}
                    </button>
                  </div>

                </div>
              </div>
            )}
    </div>
  );
}
