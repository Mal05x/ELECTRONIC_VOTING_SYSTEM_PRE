package com.evoting.dto;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreatePollingUnitDTO {
    @NotBlank private String  name;
    private String            code;
    @NotNull  private Integer lgaId;
    @Min(1)   private int     capacity;
}
