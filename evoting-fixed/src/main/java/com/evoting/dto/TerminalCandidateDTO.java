package com.evoting.dto;
import lombok.AllArgsConstructor;
import lombok.Data;
import java.util.UUID;

/** Candidate data returned to the ESP32-S3 terminal for ballot display. */
@Data @AllArgsConstructor
public class TerminalCandidateDTO {
    private UUID   id;
    private String fullName;
    private String partyAbbreviation;
    private String position;
    private String imageUrl;   // presigned S3 URL or null
}
