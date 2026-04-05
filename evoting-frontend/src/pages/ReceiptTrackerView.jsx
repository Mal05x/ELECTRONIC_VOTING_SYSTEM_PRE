import { useState } from "react";
import { Ic, Spinner } from "../components/ui.jsx";
import client from "../api/client.js";

/**
 * Public receipt verifier — calls GET /api/receipt/{transactionId}
 * Backend: ReceiptController — checks ballot_box table for transaction ID.
 */
export default function ReceiptTrackerView() {
  const [txId,    setTxId]    = useState("");
  const [loading, setLoading] = useState(false);
  const [result,  setResult]  = useState(null);
  const [error,   setError]   = useState("");

  const verifyReceipt = async (e) => {
    e.preventDefault();
    const id = txId.trim().toUpperCase();
    if (!id) return;
    setLoading(true);
    setResult(null);
    setError("");
    try {
      const res = await client.get(`/receipt/${id}`);
      setResult(res.data); // { verified, transactionId, electionId, castAt, message }
    } catch (err) {
      if (err.response?.status === 404) {
        setResult({
          verified: false,
          message: "Receipt Not Found",
          detail: "This Transaction ID does not match any confirmed ballot in the ledger.",
        });
      } else {
        setError(err.response?.data?.message || "Verification failed. Please try again.");
      }
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="min-h-screen bg-bg flex flex-col items-center justify-center p-6 relative overflow-hidden">
      <div className="absolute top-[10%] left-[20%] w-[400px] h-[400px] rounded-full bg-green-900/10 blur-[90px] pointer-events-none" />
      <div className="fixed inset-0 bg-grid-pattern opacity-100 pointer-events-none" />

      <div className="relative w-full max-w-[500px] animate-fade-up">
        <div className="text-center mb-8">
          <div className="w-14 h-14 mx-auto rounded-2xl bg-surface border border-border flex items-center justify-center mb-4 shadow-sm">
            <Ic n="shield" s={24} c="#34D399" sw={2} />
          </div>
          <h1 className="font-display text-2xl font-bold tracking-tight text-ink">
            Public Receipt Tracker
          </h1>
          <p className="text-sm text-sub mt-2">
            Independently verify your ballot's inclusion in the final tally.
          </p>
        </div>

        <div className="c-card p-8">
          <form onSubmit={verifyReceipt} className="space-y-4">
            <div>
              <label className="block text-xs font-semibold text-sub mb-2">
                Transaction ID (printed on voting terminal receipt)
              </label>
              <input
                className="inp inp-lg font-mono text-center tracking-wider"
                type="text"
                placeholder="e.g. 8F4C2B9A1E..."
                value={txId}
                onChange={e => setTxId(e.target.value.toUpperCase())}
                autoComplete="off"
                spellCheck={false}
              />
            </div>

            {error && (
              <div className="flex items-center gap-2 bg-red-500/10 border border-red-500/20
                              rounded-xl px-3 py-2.5 text-xs text-danger">
                <Ic n="warning" s={13} c="#F87171" /> {error}
              </div>
            )}

            <button type="submit" disabled={loading || !txId.trim()}
              className="btn btn-primary btn-lg w-full justify-center">
              {loading ? <Spinner s={18} /> : <><Ic n="shield" s={16} c="#fff" /> Verify Receipt</>}
            </button>
          </form>

          {result && (
            <div className={`mt-6 p-5 rounded-xl border animate-fade-up
              ${result.verified
                ? "bg-green-500/5 border-green-500/20"
                : "bg-red-500/5 border-red-500/20"}`}>
              <div className="flex items-center gap-3 mb-3">
                <Ic n={result.verified ? "check" : "warning"} s={20}
                    c={result.verified ? "#34D399" : "#F87171"} />
                <h3 className={`font-bold ${result.verified ? "text-success" : "text-danger"}`}>
                  {result.verified ? "Receipt Verified" : (result.message || "Not Found")}
                </h3>
              </div>

              {result.verified ? (
                <div className="space-y-2.5 text-xs">
                  <div className="flex justify-between items-center py-1.5 border-b border-border/40">
                    <span className="text-sub font-semibold">Transaction ID</span>
                    <span className="mono text-ink">{result.transactionId}</span>
                  </div>
                  <div className="flex justify-between items-center py-1.5 border-b border-border/40">
                    <span className="text-sub font-semibold">Cast At</span>
                    <span className="mono text-ink">
                      {result.castAt
                        ? new Date(result.castAt).toLocaleString("en-NG")
                        : "—"}
                    </span>
                  </div>
                  <div className="flex justify-between items-center py-1.5">
                    <span className="text-sub font-semibold">Status</span>
                    <span className="badge badge-green text-[10px]">✓ Confirmed in ballot chain</span>
                  </div>
                  <p className="text-muted leading-relaxed pt-2">{result.message}</p>
                </div>
              ) : (
                <p className="text-xs text-sub leading-relaxed">{result.detail || result.message}</p>
              )}
            </div>
          )}
        </div>

        <div className="mt-6 text-center">
          <p className="text-[11px] text-muted leading-relaxed max-w-sm mx-auto">
            This system uses Match-on-Card biometrics. Confirming your receipt proves your
            vote was counted — your candidate selection remains cryptographically anonymous.
          </p>
        </div>
      </div>
    </div>
  );
}
