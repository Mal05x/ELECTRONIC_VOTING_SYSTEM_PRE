package com.evoting.dto;
import lombok.Data;
import jakarta.validation.constraints.NotBlank;

@Data
public class PartyDTO {
    @NotBlank private String name;
    @NotBlank private String abbreviation;
    private Integer          foundedYear;
}
