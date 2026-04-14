package com.evoting.service;
import com.evoting.model.AuditLog;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Builds a SHA-256 Merkle Tree from vote hashes.
 * The root is published on the public dashboard — any tampering invalidates it.
 *
 * ── FIX BUG 3: Second-preimage vulnerability in hex-string concatenation ────
 *
 * BEFORE (vulnerable):
 *   next.add(AuditLog.sha256(left + right));
 *   // Problem: sha256("abc" + "def") == sha256("ab" + "cdef")
 *   // Hex string concatenation is ambiguous at the 32-char boundary, meaning
 *   // two distinct pairs of children can produce the same parent hash if an
 *   // attacker can control how the hex strings are split.
 *
 * AFTER (fixed):
 *   1. Hex strings are decoded to raw 32-byte arrays.
 *   2. The two byte arrays are concatenated into a 64-byte buffer.
 *   3. SHA-256 is computed over the 64-byte binary input.
 *   Because SHA-256 operates on the unambiguous binary representation,
 *   every distinct pair of children maps to a unique parent hash.
 *
 * ── FIX DQ (determinism): Sort hashes before tree construction ───────────────
 *
 * Without sorting, the Merkle root depends on vote insertion order (time of
 * casting). This means:
 *   - Two independent verifiers who receive the ballot_box rows in different
 *     orders (e.g., different query sort) compute DIFFERENT roots.
 *   - The root leaks timing information — an attacker who knows the order of
 *     votes can correlate terminal activity to root changes.
 *
 * By sorting vote hashes lexicographically before building the tree, the root
 * is a pure function of the set of votes, independent of insertion order.
 * Independent verifiers always compute the same root.
 */
@Service
public class MerkleTreeService {

    public String computeMerkleRoot(List<String> voteHashes) {
        if (voteHashes == null || voteHashes.isEmpty())
            return AuditLog.sha256("EMPTY_TREE");

        // Sort for deterministic ordering — root is a function of the SET of
        // votes, not their insertion order. Independent verifiers always agree.
        List<String> layer = new ArrayList<>(voteHashes);
        Collections.sort(layer);

        while (layer.size() > 1) {
            List<String> next = new ArrayList<>();
            for (int i = 0; i < layer.size(); i += 2) {
                String left  = layer.get(i);
                String right = (i + 1 < layer.size()) ? layer.get(i + 1) : left;
                next.add(hashPair(left, right));
            }
            layer = next;
        }
        return layer.get(0);
    }

    /**
     * Hash two child nodes using binary concatenation.
     *
     * Both hex strings are decoded to their 32-byte binary representations,
     * concatenated into a 64-byte array, and hashed. This is equivalent to the
     * Bitcoin Merkle tree algorithm (sans double-SHA256, which is overkill here
     * since we don't face length-extension on SHA-256 when the input length is
     * fixed at 64 bytes).
     */
    private String hashPair(String leftHex, String rightHex) {
        byte[] left    = AuditLog.hexToBytes(leftHex);
        byte[] right   = AuditLog.hexToBytes(rightHex);
        byte[] combined = new byte[left.length + right.length];
        System.arraycopy(left,  0, combined, 0,           left.length);
        System.arraycopy(right, 0, combined, left.length, right.length);
        return AuditLog.sha256Bytes(combined);
    }
}
