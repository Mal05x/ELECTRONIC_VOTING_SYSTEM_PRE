package com.evoting.service;
import com.evoting.model.AuditLog;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds a SHA-256 Merkle Tree from vote hashes.
 * The root is published on the public dashboard — any tampering invalidates it.
 */
@Service
public class MerkleTreeService {

    public String computeMerkleRoot(List<String> voteHashes) {
        if (voteHashes == null || voteHashes.isEmpty())
            return AuditLog.sha256("EMPTY_TREE");
        List<String> layer = new ArrayList<>(voteHashes);
        while (layer.size() > 1) {
            List<String> next = new ArrayList<>();
            for (int i = 0; i < layer.size(); i += 2) {
                String left  = layer.get(i);
                String right = (i + 1 < layer.size()) ? layer.get(i + 1) : left;
                next.add(AuditLog.sha256(left + right));
            }
            layer = next;
        }
        return layer.get(0);
    }
}
