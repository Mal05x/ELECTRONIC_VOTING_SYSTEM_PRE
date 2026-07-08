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

    /**
     * Optional. Set this to restrict a GUBERNATORIAL/SENATORIAL/
     * STATE_ASSEMBLY election to voters registered in that one state (by
     * State.id from the /api/admin/states reference list — or wherever your
     * states are enumerated). Leave null for PRESIDENTIAL elections, or for
     * a sub-national election you deliberately don't want scoped yet.
     */
    private Integer targetStateId;

    /**
     * Optional, and ONLY enforced for type=LOCAL_GOVERNMENT (see
     * Election.targetLgaId javadoc for why it's not honored for
     * SENATORIAL/STATE_ASSEMBLY even if you send it). By Lga.id.
     */
    private Integer targetLgaId;
}
