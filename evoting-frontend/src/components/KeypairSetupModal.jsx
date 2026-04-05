/**
 * KeypairSetupModal — shown to SUPER_ADMIN on first login if no keypair registered.
 * Generates an ECDSA P-256 keypair in the browser, stores private key in localStorage,
 * and POSTs the public key to the backend.
 */
import { useState } from "react";
import { Ic, Spinner } from "./ui.jsx";
import { generateAndStoreKeypair, hasStoredKeypair } from "../api/webcrypto.js";
import { registerKeypair } from "../api/multisig.js";

export function KeypairSetupModal({ onComplete, onDone }) {
  // Support both prop names
  const handleDone = onComplete || onDone || (() => {});
  const [step,    setStep]    = useState("intro"); // intro | generating | done | error
  const [error,   setError]   = useState("");
  const [pubKey,  setPubKey]  = useState("");

  const generate = async () => {
    setStep("generating");
    setError("");
    try {
      const { publicKeyB64 } = await generateAndStoreKeypair();
      setPubKey(publicKeyB64);
      await registerKeypair(publicKeyB64);
      setStep("done");
    } catch (e) {
      setError(e.response?.data?.error || e.message || "Key generation failed");
      setStep("error");
    }
  };

  return (
    <div className="fixed inset-0 z-[200] flex items-center justify-center p-6 bg-black/80 backdrop-blur-sm">
      <div className="w-full max-w-md bg-card border border-border-hi rounded-2xl shadow-2xl animate-fade-up">

        {/* Header */}
        <div className="p-6 border-b border-border">
          <div className="flex items-center gap-3">
            <div className="w-11 h-11 rounded-xl bg-purple-500/15 border border-purple-500/25
                            flex items-center justify-center flex-shrink-0">
              <Ic n="shield" s={22} c="#A78BFA" />
            </div>
            <div>
              <div className="text-base font-bold text-white">Cryptographic Key Setup</div>
              <div className="text-xs text-muted mt-0.5">Required for multi-signature approvals</div>
            </div>
          </div>
        </div>

        <div className="p-6">

          {/* Intro */}
          {step === "intro" && (
            <div className="flex flex-col gap-5">
              <p className="text-sm text-sub leading-relaxed">
                As a <span className="text-purple-400 font-bold">SUPER_ADMIN</span>, sensitive actions
                like activating elections and closing votes require cryptographic approval from
                at least 2 administrators.
              </p>
              <p className="text-sm text-sub leading-relaxed">
                This generates an <span className="text-ink font-semibold">ECDSA P-256 keypair</span> in
                your browser. Your private key never leaves this device. The public key is registered
                with the backend so your signatures can be verified.
              </p>

              <div className="bg-orange-500/8 border border-orange-500/20 rounded-xl p-4">
                <div className="flex items-start gap-2.5">
                  <Ic n="warning" s={15} c="#fb923c" />
                  <div className="text-xs text-orange-200 leading-relaxed">
                    <strong>Important:</strong> Your private key is stored in this browser only.
                    If you clear browser data or use a different browser, you will need to
                    generate a new keypair and re-register it.
                  </div>
                </div>
              </div>

              <div className="flex flex-col gap-2">
                <button className="btn btn-primary btn-lg w-full justify-center gap-2"
                  onClick={generate}>
                  <Ic n="shield" s={16} c="#fff" />
                  Generate Key Now
                </button>
                <div className="text-center text-[10px] text-muted">
                  You can also find this in <strong className="text-sub">Settings → Security & 2FA</strong> later
                </div>
              </div>
            </div>
          )}

          {/* Generating */}
          {step === "generating" && (
            <div className="flex flex-col items-center gap-5 py-6">
              <Spinner s={40} />
              <div className="text-sm font-semibold text-ink">Generating keypair...</div>
              <div className="text-xs text-muted text-center">
                Creating ECDSA P-256 key in your browser's secure crypto engine
              </div>
            </div>
          )}

          {/* Done */}
          {step === "done" && (
            <div className="flex flex-col gap-5">
              <div className="flex flex-col items-center gap-3 py-4">
                <div className="w-14 h-14 rounded-2xl bg-green-500/10 border border-green-500/20
                                flex items-center justify-center">
                  <Ic n="check" s={28} c="#34D399" sw={3} />
                </div>
                <div className="text-base font-bold text-white">Key Registered</div>
                <div className="text-xs text-muted text-center">
                  Your signing key is stored in this browser and registered with the backend.
                </div>
              </div>

              <div className="bg-elevated rounded-xl p-3">
                <div className="text-[10px] font-bold text-muted uppercase tracking-wider mb-1.5">
                  Public Key (registered with backend)
                </div>
                <div className="mono text-[10px] text-purple-400 break-all leading-relaxed">
                  {pubKey.substring(0, 80)}...
                </div>
              </div>

              <button className="btn btn-primary btn-lg w-full justify-center"
                onClick={handleDone}>
                Continue to Dashboard
              </button>
            </div>
          )}

          {/* Error */}
          {step === "error" && (
            <div className="flex flex-col gap-5">
              <div className="bg-red-500/10 border border-red-500/20 rounded-xl p-4 flex items-start gap-3">
                <Ic n="warning" s={16} c="#F87171" />
                <div className="text-sm text-danger">{error}</div>
              </div>
              <button className="btn btn-primary btn-md w-full justify-center"
                onClick={() => setStep("intro")}>
                Try Again
              </button>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
