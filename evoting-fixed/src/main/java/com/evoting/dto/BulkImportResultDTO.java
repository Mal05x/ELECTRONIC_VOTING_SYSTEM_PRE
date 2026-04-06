package com.evoting.dto;
import lombok.Data;
import java.util.List;

/**
 * Result of a bulk candidate import (JSON / CSV / Excel).
 *
 * Fix 9: Error messages are capped at MAX_ERRORS (50). If the import
 * contains more failures than this, the truncated count is added as the
 * last element so the caller knows not all errors are shown.
 * This prevents a 10,000-row bad CSV from producing a 5MB JSON error array.
 */
@Data
public class BulkImportResultDTO {

    private static final int MAX_ERRORS = 50;

    private int          totalRows;
    private int          succeeded;
    private int          failed;
    private int          errorsShown;   // may be < failed when capped
    private List<String> errors;
    private boolean      errorsTruncated;

    private BulkImportResultDTO() {}

    public static BulkImportResultDTO of(int total, int succeeded,
                                         int failed, List<String> allErrors) {
        BulkImportResultDTO r = new BulkImportResultDTO();
        r.totalRows       = total;
        r.succeeded       = succeeded;
        r.failed          = failed;
        r.errorsTruncated = allErrors.size() > MAX_ERRORS;
        r.errors          = r.errorsTruncated
            ? allErrors.subList(0, MAX_ERRORS)
            : allErrors;
        if (r.errorsTruncated)
            r.errors.add("... and " + (allErrors.size() - MAX_ERRORS)
                + " more errors not shown. Fix the file and re-upload.");
        r.errorsShown = r.errors.size();
        return r;
    }
}
