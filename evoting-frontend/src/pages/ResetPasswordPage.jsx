import { useState, useEffect } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { resetPassword } from "../api/auth.js";
import { Spinner } from "../components/ui.jsx";

/**
 * ResetPasswordPage — rendered when admin clicks the email reset link.
 *
 * Route: /reset-password?token=<hex-token>
 *
 * Reads the token from the URL, lets the admin set a new password,
 * calls POST /api/auth/reset-password, then redirects to /login.
 */
export default function ResetPasswordPage() {
  const [searchParams]  = useSearchParams();
  const navigate         = useNavigate();
  const token            = searchParams.get("token") || "";

  const [newPw,     setNewPw]     = useState("");
  const [confirmPw, setConfirmPw] = useState("");
  const [loading,   setLoading]   = useState(false);
  const [error,     setError]     = useState("");
  const [success,   setSuccess]   = useState(false);
  const [showPw,    setShowPw]    = useState(false);

  useEffect(() => {
    if (!token) setError("Invalid or missing reset token. Please request a new link.");
  }, [token]);

  const pwStrength = p => {
    let s = 0;
    if (p.length >= 8)        s++;
    if (/[A-Z]/.test(p))      s++;
    if (/[0-9]/.test(p))      s++;
    if (/[^A-Za-z0-9]/.test(p)) s++;
    return s;
  };
  const strength      = pwStrength(newPw);
  const strengthColor = ["","#F87171","#FCD34D","#34D399","#A78BFA"][strength];
  const strengthLabel = ["","Weak","Fair","Good","Strong"][strength];

  const handleSubmit = async (e) => {
    e.preventDefault();
    setError("");
    if (!newPw || newPw.length < 8) {
      setError("Password must be at least 8 characters"); return;
    }
    if (newPw !== confirmPw) {
      setError("Passwords do not match"); return;
    }
    setLoading(true);
    try {
      await resetPassword(token, newPw);
      setSuccess(true);
      setTimeout(() => navigate("/login", { replace: true }), 3000);
    } catch (err) {
      setError(err.response?.data?.error || "Reset failed. The link may have expired.");
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen flex items-center justify-center bg-[#05050a] p-4">
      <div className="w-full max-w-md">

        {/* Logo */}
        <div className="flex items-center gap-3 mb-8 justify-center">
          <div className="w-10 h-10 rounded-xl bg-purple-500/15 border border-purple-500/30
                          flex items-center justify-center text-purple-400 font-black text-lg">
            ✦
          </div>
          <span className="text-white font-bold text-lg tracking-tight">MFA E-Voting</span>
        </div>

        <div className="bg-[#0f0f1a] border border-white/8 rounded-2xl p-8 shadow-2xl">

          {success ? (
            <div className="text-center space-y-4">
              <div className="w-14 h-14 rounded-full bg-green-500/15 border border-green-500/25
                              flex items-center justify-center mx-auto text-2xl">
                ✓
              </div>
              <h2 className="text-white font-bold text-xl">Password reset!</h2>
              <p className="text-slate-400 text-sm">
                Your password has been updated. Redirecting to login…
              </p>
              <div className="w-full bg-slate-800 rounded-full h-1 overflow-hidden mt-4">
                <div className="h-full bg-purple-500 rounded-full animate-[shrink_3s_linear_forwards]"
                     style={{width:"100%", transition:"width 3s linear"}} />
              </div>
            </div>
          ) : (
            <>
              <h2 className="text-white font-bold text-xl mb-1">Set new password</h2>
              <p className="text-slate-400 text-sm mb-6">
                Choose a strong password for your admin account.
              </p>

              {error && (
                <div className="mb-4 px-4 py-3 rounded-xl bg-red-500/10 border border-red-500/25
                                text-red-300 text-sm flex items-start gap-2">
                  <span className="mt-0.5">⚠</span>
                  <span>{error}</span>
                </div>
              )}

              <form onSubmit={handleSubmit} className="space-y-4">
                <div>
                  <label className="block text-xs font-semibold text-slate-400 mb-1.5 uppercase tracking-wide">
                    New Password
                  </label>
                  <div className="relative">
                    <input
                      type={showPw ? "text" : "password"}
                      value={newPw}
                      onChange={e => setNewPw(e.target.value)}
                      className="w-full px-4 py-2.5 bg-[#1a1a2e] border border-white/10 rounded-xl
                                 text-white placeholder-slate-600 text-sm focus:outline-none
                                 focus:border-purple-500/50 pr-10"
                      placeholder="Min. 8 characters"
                      autoComplete="new-password"
                      disabled={loading}
                    />
                    <button type="button" onClick={() => setShowPw(v => !v)}
                      className="absolute right-3 top-1/2 -translate-y-1/2 text-slate-500
                                 hover:text-slate-300 text-xs">
                      {showPw ? "Hide" : "Show"}
                    </button>
                  </div>
                  {newPw && (
                    <div className="mt-2">
                      <div className="flex gap-1 mb-1">
                        {[1,2,3,4].map(i => (
                          <div key={i}
                            className="h-1 flex-1 rounded-full transition-all duration-300"
                            style={{ background: i <= strength ? strengthColor : "#1e293b" }} />
                        ))}
                      </div>
                      <p className="text-[11px]" style={{ color: strengthColor }}>
                        {strengthLabel}
                      </p>
                    </div>
                  )}
                </div>

                <div>
                  <label className="block text-xs font-semibold text-slate-400 mb-1.5 uppercase tracking-wide">
                    Confirm Password
                  </label>
                  <input
                    type={showPw ? "text" : "password"}
                    value={confirmPw}
                    onChange={e => setConfirmPw(e.target.value)}
                    className={`w-full px-4 py-2.5 bg-[#1a1a2e] border rounded-xl text-white
                                placeholder-slate-600 text-sm focus:outline-none
                                ${confirmPw && confirmPw !== newPw
                                  ? "border-red-500/50"
                                  : confirmPw && confirmPw === newPw
                                    ? "border-green-500/40"
                                    : "border-white/10 focus:border-purple-500/50"}`}
                    placeholder="Repeat new password"
                    autoComplete="new-password"
                    disabled={loading}
                  />
                  {confirmPw && confirmPw !== newPw && (
                    <p className="text-[11px] text-red-400 mt-1">Passwords do not match</p>
                  )}
                </div>

                <button
                  type="submit"
                  disabled={loading || !token || strength < 1}
                  className="w-full py-2.5 bg-purple-600 hover:bg-purple-500 disabled:opacity-40
                             disabled:cursor-not-allowed text-white font-semibold rounded-xl
                             text-sm transition-all flex items-center justify-center gap-2 mt-2">
                  {loading ? <Spinner s={16} /> : null}
                  {loading ? "Resetting…" : "Reset Password"}
                </button>

                <button
                  type="button"
                  onClick={() => navigate("/login")}
                  className="w-full text-center text-xs text-slate-500 hover:text-slate-300
                             transition-colors mt-1">
                  Back to login
                </button>
              </form>
            </>
          )}
        </div>

        <p className="text-center text-[11px] text-slate-600 mt-6">
          MFA Electronic Voting System · Secure Admin Portal
        </p>
      </div>
    </div>
  );
}
