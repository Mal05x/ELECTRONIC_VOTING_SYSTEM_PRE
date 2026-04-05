/**
 * useStepUpAuth — hook for ECDSA step-up authentication.
 *
 * Usage:
 *   const { requireAuth } = useStepUpAuth();
 *
 *   const handleImport = async () => {
 *     const headers = await requireAuth("IMPORT_CANDIDATES", {
 *       summary: "Import 3 candidates into 2027 Presidential Election",
 *     });
 *     if (!headers) return; // user cancelled or no keypair
 *     await importCandidatesJson(electionId, candidates, headers);
 *   };
 *
 * The hook manages:
 *   - 5-minute grace period per action type (no re-prompt for bulk ops)
 *   - Modal display (via StepUpModal via context)
 *   - Challenge request + signing
 *   - Returns Axios-compatible headers object
 */
import { useCallback, useRef } from "react";
import { useKeypair }          from "../context/KeypairContext.jsx";
import { requestChallenge }    from "../api/stepup.js";

// Grace period: once authorized for an action type, skip prompt for 5 minutes
const GRACE_MS = 5 * 60 * 1000;
const graceCache = {}; // { [actionType]: { headers, grantedAt } }

export function useStepUpAuth() {
  const { signChallenge, hasLocalKey, needsSetup } = useKeypair();

  // Resolver ref — resolved by StepUpModal when user clicks Authorize or Cancel
  const resolverRef = useRef(null);

  /**
   * Request step-up authorization for an action.
   * Returns headers to include on the Axios request, or null if denied/cancelled.
   *
   * @param {string} actionType  - e.g. "IMPORT_CANDIDATES"
   * @param {object} opts
   * @param {string} opts.summary  - Human-readable description shown in modal
   * @param {boolean} opts.force   - Skip grace period cache
   */
  const requireAuth = useCallback(async (actionType, opts = {}) => {
    const { summary = "Perform this action", force = false } = opts;

    // Check grace period cache
    if (!force && graceCache[actionType]) {
      const { headers, grantedAt } = graceCache[actionType];
      if (Date.now() - grantedAt < GRACE_MS) {
        return headers; // still within grace period — skip modal
      }
      delete graceCache[actionType];
    }

    // Check keypair
    if (!hasLocalKey || needsSetup) {
      return { __noKeypair: true }; // StepUpModal handles displaying the error
    }

    try {
      // Step 1: Get challenge nonce from backend
      const challenge = await requestChallenge(actionType);
      // challenge = { nonce, actionType, expiresIn, sigPayload }

      // Step 2: Sign the payload (format: "ACTION_TYPE:NONCE")
      const signature = await signChallenge(challenge.sigPayload);
      if (!signature) return null;

      // Step 3: Build headers
      const headers = {
        "X-Action-Nonce":     challenge.nonce,
        "X-Action-Signature": signature,
      };

      // Cache for grace period
      graceCache[actionType] = { headers, grantedAt: Date.now() };

      return headers;
    } catch (e) {
      if (e.response?.status === 412) {
        // No keypair registered
        return { __noKeypair: true };
      }
      throw e;
    }
  }, [signChallenge, hasLocalKey, needsSetup]);

  return { requireAuth };
}
