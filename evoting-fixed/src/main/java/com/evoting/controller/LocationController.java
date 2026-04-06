package com.evoting.controller;
import com.evoting.dto.*;
import com.evoting.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Public read-only cascade API for the voter registration form.
 * Flow: pick State → load LGAs → load Polling Units.
 */
@RestController
@RequestMapping("/api/locations")
public class LocationController {

    @Autowired private StateRepository      stateRepo;
    @Autowired private LgaRepository        lgaRepo;
    @Autowired private PollingUnitRepository pollingUnitRepo;

    /** GET /api/locations/states */
    @GetMapping("/states")
    public ResponseEntity<List<StateDTO>> getAllStates() {
        return ResponseEntity.ok(stateRepo.findAll().stream()
            .map(s -> new StateDTO(s.getId(), s.getName(), s.getCode()))
            .sorted(Comparator.comparing(StateDTO::getName))
            .collect(Collectors.toList()));
    }

    /** GET /api/locations/states/{stateId}/lgas */
    @GetMapping("/states/{stateId}/lgas")
    public ResponseEntity<List<LgaDTO>> getLgas(@PathVariable Integer stateId) {
        return ResponseEntity.ok(lgaRepo.findByStateIdOrderByNameAsc(stateId).stream()
            .map(l -> new LgaDTO(l.getId(), l.getName(), l.getState().getId(), l.getState().getName()))
            .collect(Collectors.toList()));
    }

    /** GET /api/locations/lgas/{lgaId}/polling-units */
    @GetMapping("/lgas/{lgaId}/polling-units")
    public ResponseEntity<List<PollingUnitDTO>> getPollingUnits(@PathVariable Integer lgaId) {
        return ResponseEntity.ok(pollingUnitRepo.findByLgaIdOrderByNameAsc(lgaId).stream()
            .map(p -> new PollingUnitDTO(
                p.getId(), p.getName(), p.getCode(),
                p.getLga().getId(), p.getLga().getName(),
                p.getLga().getState().getId(), p.getLga().getState().getName(),
                p.getCapacity()))
            .collect(Collectors.toList()));
    }
}
