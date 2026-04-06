package com.evoting.service;
import com.evoting.dto.BulkImportResultDTO;
import com.evoting.dto.CandidateImportRowDTO;
import com.evoting.model.Candidate;
import com.evoting.model.Party;
import com.evoting.repository.CandidateRepository;
import com.evoting.repository.ElectionRepository;
import com.evoting.repository.PartyRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.util.*;

@Service @Slf4j
public class ElectionDataImportService {

    @Autowired private CandidateRepository  candidateRepo;
    @Autowired private PartyRepository      partyRepo;
    @Autowired private ElectionRepository   electionRepo;
    @Autowired private AuditLogService      auditLog;
    @Autowired private ObjectMapper         mapper;
    @Autowired private Validator            validator;   // Fix 8: injected Bean Validator

    // ── JSON import ──────────────────────────────────────────────────────────

    @Transactional
    public BulkImportResultDTO importFromJson(String json, String importedBy) throws Exception {
        List<CandidateImportRowDTO> rows = mapper.readValue(json,
            new TypeReference<List<CandidateImportRowDTO>>() {});
        return processRows(rows, importedBy);
    }

    // ── CSV import ───────────────────────────────────────────────────────────

    @Transactional
    public BulkImportResultDTO importFromCsv(MultipartFile file, UUID electionId, String importedBy)
            throws IOException, CsvException {
        List<CandidateImportRowDTO> rows = new ArrayList<>();
        try (CSVReader reader = new CSVReader(new InputStreamReader(file.getInputStream()))) {
            List<String[]> lines = reader.readAll();
            if (lines.isEmpty()) throw new IllegalArgumentException("CSV file is empty");
            for (int i = 1; i < lines.size(); i++) {
                String[] cols = lines.get(i);
                CandidateImportRowDTO row = new CandidateImportRowDTO();
                row.setFullName(cols.length > 0 ? cols[0].trim() : null);
                row.setPartyAbbreviation(cols.length > 1 ? cols[1].trim() : null);
                row.setPosition(cols.length > 2 ? cols[2].trim() : null);
                row.setElectionId(electionId);
                rows.add(row);
            }
        }
        return processRows(rows, importedBy);
    }

    // ── Excel import ─────────────────────────────────────────────────────────

    @Transactional
    public BulkImportResultDTO importFromExcel(MultipartFile file, UUID electionId, String importedBy)
            throws IOException {
        List<CandidateImportRowDTO> rows = new ArrayList<>();
        try (Workbook wb = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = wb.getSheetAt(0);
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                CandidateImportRowDTO dto = new CandidateImportRowDTO();
                dto.setFullName(cellStr(row, 0));
                dto.setPartyAbbreviation(cellStr(row, 1));
                dto.setPosition(cellStr(row, 2));
                dto.setElectionId(electionId);
                if (dto.getFullName() != null && !dto.getFullName().isBlank())
                    rows.add(dto);
            }
        }
        return processRows(rows, importedBy);
    }

    // ── Core processing ───────────────────────────────────────────────────────

    private BulkImportResultDTO processRows(List<CandidateImportRowDTO> rows, String importedBy) {
        int success = 0, failed = 0;
        List<String> errors = new ArrayList<>();

        for (CandidateImportRowDTO row : rows) {
            try {
                // Fix 8: validate each row with Bean Validation before touching DB
                Set<ConstraintViolation<CandidateImportRowDTO>> violations = validator.validate(row);
                if (!violations.isEmpty()) {
                    String msg = violations.stream()
                        .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                        .reduce((a, b) -> a + "; " + b).orElse("validation failed");
                    throw new IllegalArgumentException(msg);
                }

                if (!electionRepo.existsById(row.getElectionId()))
                    throw new IllegalArgumentException("Election not found: " + row.getElectionId());

                Party party = null;
                if (row.getPartyAbbreviation() != null && !row.getPartyAbbreviation().isBlank()) {
                    party = partyRepo.findByAbbreviationIgnoreCase(row.getPartyAbbreviation())
                        .orElseGet(() -> partyRepo.save(Party.builder()
                            .name(row.getPartyAbbreviation())
                            .abbreviation(row.getPartyAbbreviation().toUpperCase())
                            .build()));
                }

                candidateRepo.save(Candidate.builder()
                    .electionId(row.getElectionId())
                    .partyId(party != null ? party.getId() : null)
                    .fullName(row.getFullName())
                    .party(party != null ? party.getAbbreviation() : null)
                    .position(row.getPosition())
                    .build());

                success++;
            } catch (Exception e) {
                failed++;
                // Fix 9: error list capped — see BulkImportResultDTO
                errors.add("[" + row.getFullName() + "]: " + e.getMessage());
                log.warn("Import row failed: {}", e.getMessage());
            }
        }

        auditLog.log("ELECTION_DATA_IMPORTED", importedBy,
            "Candidates: success=" + success + " failed=" + failed);
        return BulkImportResultDTO.of(rows.size(), success, failed, errors);
    }

    private String cellStr(Row row, int col) {
        Cell cell = row.getCell(col, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
        if (cell == null) return null;
        return switch (cell.getCellType()) {
            case STRING  -> cell.getStringCellValue().trim();
            case NUMERIC -> String.valueOf((long) cell.getNumericCellValue());
            default      -> null;
        };
    }
}
