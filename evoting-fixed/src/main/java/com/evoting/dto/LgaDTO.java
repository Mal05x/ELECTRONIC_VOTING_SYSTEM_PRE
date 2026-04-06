package com.evoting.dto;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data @AllArgsConstructor
public class LgaDTO {
    private Integer id;
    private String  name;
    private Integer stateId;
    private String  stateName;
}
