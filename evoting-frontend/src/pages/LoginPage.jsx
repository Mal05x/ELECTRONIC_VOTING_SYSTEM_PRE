import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../context/AuthContext.jsx";
import { forgotPassword } from "../api/auth.js";
import { Ic, Spinner } from "../components/ui.jsx";

export default function LoginPage() {
  const { login, loading, error } = useAuth();
  const navigate = useNavigate();

  const [user,     setUser]     = useState(() => localStorage.getItem("evoting_saved_user") || "");
  const [pass,     setPass]     = useState("");
  const [remember, setRemember] = useState(false);
  const [showPass, setShowPass] = useState(false);

  // Forgot password modal
  const [showForgot, setShowForgot] = useState(false);
  const [fpEmail,    setFpEmail]    = useState("");
  const [fpLoading,  setFpLoading]  = useState(false);
  const [fpSent,     setFpSent]     = useState(false);
  const [fpError,    setFpError]    = useState("");

  const submit = async (e) => {
    e.preventDefault();
    if (remember) localStorage.setItem("evoting_saved_user", user.trim());
    else          localStorage.removeItem("evoting_saved_user");
    const ok = await login(user.trim(), pass, remember);
    if (ok) navigate("/", { replace: true });
  };

  const openForgot = () => {
    setShowForgot(true);
    setFpSent(false);
    setFpEmail("");
    setFpError("");
  };

  const handleForgot = async (e) => {
    e.preventDefault();
    setFpError(""); setFpLoading(true);
    try {
      await forgotPassword(fpEmail.trim());
      setFpSent(true);
    } catch (err) {
      setFpError(err.response?.data?.error || "Failed to send reset email.");
    } finally { setFpLoading(false); }
  };

  return (
    <div className="min-h-screen flex w-full bg-bg">

      {/* ── LEFT PANEL: Branding ────────────────────────────── */}
      <div className="hidden lg:flex w-1/2 relative flex-col justify-between p-12 overflow-hidden border-r border-white/5 bg-[#05050a]">

        {/* Background layers */}
        <div className="absolute top-0 left-0 w-full h-full bg-grid-pattern opacity-30 pointer-events-none" />
        <div className="absolute -left-[20%] top-[20%] w-[600px] h-[600px] rounded-full bg-purple-900/20 blur-[120px] pointer-events-none" />
        <div className="absolute right-[10%] bottom-[10%] w-[400px] h-[400px] rounded-full bg-violet-800/10 blur-[100px] pointer-events-none" />

        {/* Biometric / circuit graphic */}
        <div className="absolute top-1/2 left-1/2 -translate-x-1/2 -translate-y-1/2 w-[800px] h-[800px] opacity-[0.04] pointer-events-none flex items-center justify-center">
          <svg viewBox="0 0 100 100" className="w-full h-full">
            <path d="M50 0C22.4 0 0 22.4 0 50s22.4 50 50 50 50-22.4 50-50S77.6 0 50 0zm0 96C24.6 96 4 75.4 4 50S24.6 4 50 4s46 20.6 46 46-20.6 46-46 46z" fill="white"/>
            <path d="M50 12c-21 0-38 17-38 38s17 38 38 38 38-17 38-38-17-38-38-38zm0 72c-18.8 0-34-15.2-34-34s15.2-34 34-34 34 15.2 34 34-15.2 34-34 34z" fill="white"/>
            <path d="M50 24c-14.3 0-26 11.7-26 26s11.7 26 26 26 26-11.7 26-26-11.7-26-26-26zm0 48c-12.1 0-22-9.9-22-22s9.9-22 22-22 22 9.9 22 22-9.9 22-22 22z" fill="white"/>
            <path d="M50 36c-7.7 0-14 6.3-14 14s6.3 14 14 14 14-6.3 14-14-6.3-14-14-14zm0 24c-5.5 0-10-4.5-10-10s4.5-10 10-10 10 4.5 10 10-4.5 10-10 10z" fill="white"/>
            <circle cx="50" cy="50" r="4" fill="white"/>
            <path d="M50 50 L 50 0 M 50 50 L 100 50 M 50 50 L 50 100 M 50 50 L 0 50" stroke="white" strokeWidth="0.5" fill="none"/>
            <path d="M 20 20 L 35 35 M 80 20 L 65 35 M 20 80 L 35 65 M 80 80 L 65 65" stroke="white" strokeWidth="0.5" fill="none"/>
            <circle cx="35" cy="35" r="1.5" fill="white"/> <circle cx="65" cy="35" r="1.5" fill="white"/>
            <circle cx="35" cy="65" r="1.5" fill="white"/> <circle cx="65" cy="65" r="1.5" fill="white"/>
          </svg>
        </div>

        {/* Top logo */}
        <div className="relative z-10 flex items-center gap-3 animate-fade-in">
          <div className="w-11 h-11 rounded-xl bg-purple-gradient flex items-center justify-center shadow-purple-sm border border-white/10">
            <Ic n="shield" s={22} c="#fff" sw={2.2} />
          </div>
          <div className="font-display text-2xl font-bold text-white tracking-wide">MFA Evoting</div>
        </div>

        {/* Bottom copy */}
        <div className="relative z-10 max-w-md animate-fade-up" style={{ animationDelay: "150ms" }}>
          <div className="inline-flex items-center gap-2 px-3 py-1.5 rounded-full bg-purple-500/10 border border-purple-500/20 text-purple-300 text-[10px] font-bold tracking-widest uppercase mb-5">
            <span className="w-1.5 h-1.5 rounded-full bg-purple-400 animate-pulse" />
            Hardware-Software Co-Design
          </div>
          <h2 className="text-[40px] font-display font-extrabold text-white leading-[1.1] mb-5 tracking-tight">
            Zero-Trust <br/>
            <span className="text-transparent bg-clip-text bg-gradient-to-r from-purple-400 to-purple-200">
              Architecture.
            </span>
          </h2>
          <p className="text-sm text-sub leading-relaxed font-medium">
            Secured by JCOP 4 Smart Cards and Match-on-Card biometrics. Every hardware terminal authenticated via mTLS.
          </p>

          {/* Feature pills */}
          <div className="flex flex-wrap gap-2 mt-6">
            {["3-Factor Auth", "Hardware Encrypted", "Merkle Verified"].map(tag => (
              <span key={tag}
                className="text-[10px] font-semibold text-purple-300/70 border border-purple-500/15
                           bg-purple-500/5 px-2.5 py-1 rounded-full tracking-wide">
                {tag}
              </span>
            ))}
          </div>
        </div>
      </div>

      {/* ── RIGHT PANEL: Login Form ────────────────────────── */}
      <div className="w-full lg:w-1/2 relative flex items-center justify-center p-8 sm:p-12 bg-[#07070E]">

        <div className="absolute top-[-10%] right-[-10%] w-[500px] h-[500px] rounded-full bg-purple-900/10 blur-[100px] pointer-events-none" />

        <div className="w-full max-w-[380px] relative z-10 animate-fade-up" style={{ animationDelay: "200ms" }}>

          {/* Mobile-only logo */}
          <div className="lg:hidden flex flex-col items-center mb-10">
            <div className="w-14 h-14 rounded-2xl bg-purple-gradient flex items-center justify-center shadow-purple-sm mb-4 border border-white/10">
              <Ic n="shield" s={28} c="#fff" sw={2} />
            </div>
            <div className="font-display text-2xl font-extrabold text-white tracking-tight">MFA Evoting</div>
            <div className="text-xs text-sub mt-1">Secure Admin Dashboard</div>
          </div>

          <div className="mb-8 text-center lg:text-left">
            <h1 className="text-3xl font-display font-bold text-white tracking-tight mb-2">Welcome back</h1>
            <p className="text-sm text-sub">Enter your credentials to access the secure dashboard.</p>
          </div>

          <form onSubmit={submit} className="space-y-5">

            {/* Username */}
            <div>
              <label className="block text-xs font-semibold text-sub mb-2 uppercase tracking-wide">
                Username
              </label>
              <input
                className="inp inp-lg bg-[#0d0d1a] focus:bg-[#121222] border-white/5 transition-colors"
                type="text" placeholder="Enter username"
                value={user} onChange={e => setUser(e.target.value)}
                autoFocus autoComplete="username"
              />
            </div>

            {/* Password */}
            <div>
              <div className="flex justify-between items-center mb-2">
                <label className="block text-xs font-semibold text-sub uppercase tracking-wide">
                  Password
                </label>
                <button type="button"
                  className="text-xs font-semibold text-purple-400 hover:text-purple-300 transition-colors"
                  onClick={openForgot}>
                  Forgot password?
                </button>
              </div>
              <div className="relative">
                <input
                  className="inp inp-lg bg-[#0d0d1a] focus:bg-[#121222] border-white/5 tracking-widest transition-colors pr-11"
                  type={showPass ? "text" : "password"}
                  placeholder="••••••••••"
                  value={pass} onChange={e => setPass(e.target.value)}
                  autoComplete="current-password"
                />
                <button type="button" tabIndex={-1}
                  className="absolute right-3 top-1/2 -translate-y-1/2 text-white/30 hover:text-white/60 transition-colors"
                  onClick={() => setShowPass(v => !v)}>
                  <Ic n={showPass ? "eye" : "lock"} s={15} />
                </button>
              </div>
            </div>

            {/* Remember me */}
            <label className="flex items-center gap-3 cursor-pointer select-none w-fit">
              <div className="relative">
                <input type="checkbox" className="sr-only"
                  checked={remember} onChange={e => setRemember(e.target.checked)} />
                <div className={`w-9 h-5 rounded-full transition-colors duration-200
                                 ${remember ? "bg-purple-500" : "bg-white/5 border border-white/10"}`}>
                  <div className={`absolute top-0.5 w-4 h-4 rounded-full bg-white shadow
                                   transition-transform duration-200
                                   ${remember ? "translate-x-4" : "translate-x-0.5"}`} />
                </div>
              </div>
              <span className="text-xs font-semibold text-sub">Remember me</span>
            </label>

            {/* Error */}
            {error && (
              <div className="flex items-center gap-2.5 bg-red-500/10 border border-red-500/20
                              rounded-xl px-4 py-3 text-sm font-medium text-danger animate-slide-in">
                <Ic n="warning" s={15} c="#F87171" />
                {error}
              </div>
            )}

            {/* Submit */}
            <button type="submit" disabled={loading || !user || !pass}
              className="btn btn-primary btn-lg w-full justify-center mt-2
                         shadow-purple-lg transition-transform hover:-translate-y-0.5 active:translate-y-0
                         disabled:opacity-50 disabled:cursor-not-allowed disabled:translate-y-0">
              {loading
                ? <Spinner s={18} />
                : <><span>Secure Sign In</span><Ic n="lock" s={16} c="#fff" sw={2.5} /></>
              }
            </button>
          </form>


        </div>
      </div>

      {/* ── Forgot Password Modal ─────────────────────────── */}
      {showForgot && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center p-6 bg-black/70 backdrop-blur-sm"
          onClick={e => e.target === e.currentTarget && setShowForgot(false)}>
          <div className="w-full max-w-md bg-[#0d0d1a] border border-white/8 rounded-2xl p-8
                          shadow-2xl animate-fade-up">

            <div className="flex items-center gap-3 mb-6">
              <div className="w-10 h-10 rounded-xl bg-purple-500/15 border border-purple-500/25
                              flex items-center justify-center flex-shrink-0">
                <Ic n="lock" s={18} c="#A78BFA" />
              </div>
              <div>
                <div className="text-base font-bold text-white">Reset Password</div>
                <div className="text-xs text-sub mt-0.5">Enter your registered email address</div>
              </div>
              <button
                className="ml-auto text-white/30 hover:text-white/60 transition-colors"
                onClick={() => setShowForgot(false)}>
                <Ic n="close" s={16} />
              </button>
            </div>

            {fpSent ? (
              <div className="text-center py-4">
                <div className="text-4xl mb-4">✉️</div>
                <div className="text-sm font-bold text-white mb-2">Reset link sent</div>
                <div className="text-xs text-sub leading-relaxed">
                  Check your inbox for a password reset link. It expires in 15 minutes.
                </div>
                <button
                  className="btn btn-primary btn-md mt-6 w-full justify-center"
                  onClick={() => setShowForgot(false)}>
                  Back to Login
                </button>
              </div>
            ) : (
              <form onSubmit={handleForgot} className="space-y-4">
                <div>
                  <label className="block text-xs font-semibold text-sub mb-2 uppercase tracking-wide">
                    Email Address
                  </label>
                  <input
                    className="inp inp-lg bg-[#0d0d1a] focus:bg-[#121222] border-white/5 transition-colors"
                    type="email" placeholder="admin@evoting.gov.ng"
                    value={fpEmail} onChange={e => setFpEmail(e.target.value)}
                    autoFocus
                  />
                </div>

                {fpError && (
                  <div className="flex items-center gap-2 bg-red-500/10 border border-red-500/20
                                  rounded-xl px-3 py-2.5 text-xs text-danger">
                    <Ic n="warning" s={13} c="#F87171" /> {fpError}
                  </div>
                )}

                <div className="flex gap-3 pt-1">
                  <button type="submit" disabled={fpLoading || !fpEmail}
                    className="btn btn-primary btn-md flex-1 justify-center">
                    {fpLoading ? <Spinner s={15} /> : "Send Reset Link"}
                  </button>
                  <button type="button"
                    className="btn btn-md flex-1 justify-center border border-white/8
                               bg-white/3 text-sub hover:bg-white/6 hover:text-white transition-colors
                               rounded-xl text-sm font-semibold"
                    onClick={() => setShowForgot(false)}>
                    Cancel
                  </button>
                </div>
              </form>
            )}
          </div>
        </div>
      )}

    </div>
  );
}
