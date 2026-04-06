package com.evoting.dto;
import com.evoting.model.CardStatusLog.CardEvent;
import lombok.AllArgsConstructor;
import lombok.Data;
import java.time.OffsetDateTime;
import java.util.UUID;

@Data @AllArgsConstructor
public class CardStatusDTO {
    private String          cardIdHash;
    private UUID            electionId;
    private CardEvent       eventType;
    private String          triggeredBy;
    private OffsetDateTime  createdAt;
}
