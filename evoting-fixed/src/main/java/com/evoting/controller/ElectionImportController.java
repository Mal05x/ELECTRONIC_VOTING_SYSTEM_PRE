package com.evoting.controller;
import com.evoting.dto.BulkImportResultDTO;
import com.evoting.service.ElectionDataImportService;
import com.evoting.security.RequiresStepUp;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.UUID;

/**
 * Bulk election data import — three routes:
 *
 *   POST /api/admin/import/json
 *        Body: JSON array of CandidateImportRowDTO
 *
 *   POST /api/admin/import/csv?electionId={uuid}
 *        Multipart field "file" — CSV with header: fullName,partyAbbreviation,position
 *
 *   POST /api/admin/import/excel?electionId={uuid}
 *        Multipart field "file" — .xlsx with same columns as CSV
 *
 * All three return BulkImportResultDTO (total, succeeded, failed, per-row errors).
 */
@RestController
@RequestMapping("/api/admin/import")
public class ElectionImportController {

    @Autowired private ElectionDataImportService importService;

    /** JSON array import */
    @PostMapping(value = "/json", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('ADMIN') or hasRole('SUPER_ADMIN')")
    @RequiresStepUp("IMPORT_CANDIDATES")
    public ResponseEntity<BulkImportResultDTO> importJson(
            @RequestBody String json, Authentication auth) throws Exception {
        return ResponseEntity.ok(importService.importFromJson(json, auth.getName()));
    }

    /** CSV file upload */
    @PostMapping(value = "/csv", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN') or hasRole('SUPER_ADMIN')")
    @RequiresStepUp("IMPORT_CANDIDATES")
    public ResponseEntity<BulkImportResultDTO> importCsv(
            @RequestParam("file") MultipartFile file,
            @RequestParam("electionId") UUID electionId,
            Authentication auth) throws Exception {
        return ResponseEntity.ok(importService.importFromCsv(file, electionId, auth.getName()));
    }

    /** Excel (.xlsx) file upload */
    @PostMapping(value = "/excel", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN') or hasRole('SUPER_ADMIN')")
    @RequiresStepUp("IMPORT_CANDIDATES")
    public ResponseEntity<BulkImportResultDTO> importExcel(
            @RequestParam("file") MultipartFile file,
            @RequestParam("electionId") UUID electionId,
            Authentication auth) throws Exception {
        return ResponseEntity.ok(importService.importFromExcel(file, electionId, auth.getName()));
    }
}
