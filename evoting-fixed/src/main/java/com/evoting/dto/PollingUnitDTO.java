package com.evoting.dto;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data @AllArgsConstructor
public class PollingUnitDTO {
    private Long    id;
    private String  name;
    private String  code;
    private Integer lgaId;
    private String  lgaName;
    private Integer stateId;
    private String  stateName;
    private Integer capacity;
}
