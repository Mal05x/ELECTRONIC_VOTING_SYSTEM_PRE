package com.evoting.dto;
import lombok.Data;

@Data
public class HeartbeatDTO {
    private String  terminalId;
    private Short   batteryLevel;
    private boolean tamperFlag;
    private String  ipAddress;
}
