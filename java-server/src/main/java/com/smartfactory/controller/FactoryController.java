package com.smartfactory.controller;

import com.smartfactory.model.ActionSequence;
import com.smartfactory.model.ComplexParameters;
import com.smartfactory.model.SuperpositionData;
import com.smartfactory.service.ApsAlgorithmService;
import com.smartfactory.service.ScheduleService;
import com.smartfactory.state.ApiState;
import lombok.Data;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Equivalent to the FastAPI application.
 * Acts as the bridge for the Android application or Web Dashboard.
 * Extended with APS state endpoints.
 */
@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*") // Allow requests from any origin (e.g. local Android apps)
public class FactoryController {

    private final ApiState apiState;
    private final ScheduleService scheduleService;
    private final ApsAlgorithmService apsService;

    public FactoryController(ApiState apiState, ScheduleService scheduleService,
                             ApsAlgorithmService apsService) {
        this.apiState = apiState;
        this.scheduleService = scheduleService;
        this.apsService = apsService;
    }

    @GetMapping("/state")
    public ApiState getState() {
        return apiState;
    }

    @GetMapping("/queue")
    public ScheduleService.ScheduleData getQueue() {
        return scheduleService.readSchedule();
    }

    @PostMapping("/queue")
    public Map<String, Object> addTask(@RequestBody TaskItem item) {
        List<?> updatedQueue = scheduleService.addTask(item.getTaskName());
        return Map.of(
                "message", "Задача '" + item.getTaskName() + "' успешно добавлена!",
                "queue", updatedQueue
        );
    }

    /**
     * Полное состояние APS-алгоритма для визуализации на фронтенде.
     */
    @GetMapping("/aps-state")
    public Map<String, Object> getApsState() {
        ComplexParameters params = apsService.getComplexParams();
        SuperpositionData superposition = apsService.getSuperpositionData();
        ActionSequence sequence = apsService.getCurrentSequence();

        return Map.of(
                "apiState", apiState,
                "complexParams", params,
                "superposition", superposition != null ? superposition : Map.of(),
                "actionSequence", sequence != null ? sequence : Map.of(),
                "equipment", params.getEquipment(),
                "lines", params.getLines()
        );
    }

    /**
     * Ручное решение оператора о вклинивании в очередь.
     * Соответствует блоку «Ручное решение о вклинивании или в очередь».
     */
    @PostMapping("/manual-decision")
    public Map<String, String> manualDecision(@RequestBody ManualDecisionItem item) {
        apsService.manualQueueDecision(item.getBatchName(), item.isInsertAtFront());

        // Снимаем ручной режим после решения
        apiState.setManualMode(false);
        apiState.setStatus("AUTO_NORMAL");

        return Map.of(
                "message", "Ручное решение принято: " + item.getBatchName(),
                "action", item.isInsertAtFront() ? "ВКЛИНИТЬ" : "В_ОЧЕРЕДЬ"
        );
    }

    @Data
    public static class TaskItem {
        private String taskName;
    }

    @Data
    public static class ManualDecisionItem {
        private String batchName;
        private boolean insertAtFront;
    }
}
