package com.evoting.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;
import java.time.OffsetDateTime;

@Data
public class ElectionDTO {

    @NotBlank
    private String name;

    private String description;

    /**
     * Electoral scope.
     * Defaults to "PRESIDENTIAL" so existing integrations and V1 firmware
     * that don't send this field don't break — matches the DB DEFAULT.
     *
     * Valid values (enforced by DB constraint chk_election_type):
     *   PRESIDENTIAL | GUBERNATORIAL | SENATORIAL | STATE_ASSEMBLY | LOCAL_GOVERNMENT
     */
    @Pattern(
            regexp = "PRESIDENTIAL|GUBERNATORIAL|SENATORIAL|STATE_ASSEMBLY|LOCAL_GOVERNMENT",
            message = "type must be one of: PRESIDENTIAL, GUBERNATORIAL, SENATORIAL, STATE_ASSEMBLY, LOCAL_GOVERNMENT"
    )
    private String type = "PRESIDENTIAL";

    @NotNull
    private OffsetDateTime startTime;

    @NotNull
    private OffsetDateTime endTime;
}
