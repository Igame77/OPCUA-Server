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

    /**
     * Возвращает системный лог (последние записи) для мобильного приложения.
     */
    @GetMapping("/logs")
    public Map<String, Object> getLogs() {
        return Map.of(
                "systemLog", apiState.getSystemLog(),
                "apsHistory", apiState.getApsHistory()
        );
    }

    @PostMapping("/queue")
    public Map<String, Object> addTask(@RequestBody TaskItem item) {
        List<?> updatedQueue = scheduleService.addTask(item.getTaskName());
        apiState.addSystemLog("REST_API", "POST /api/queue — '" + item.getTaskName() + "' добавлена в очередь");
        return Map.of(
                "message", "Задача '" + item.getTaskName() + "' успешно добавлена!",
                "queue", updatedQueue
        );
    }

    /**
     * Удаляет задачу из очереди по имени.
     */
    @PostMapping("/delete-task")
    public Map<String, Object> deleteTask(@RequestBody TaskItem item) {
        List<?> updatedQueue = scheduleService.removeTask(item.getTaskName());
        apiState.addSystemLog("REST_API", "DELETE — '" + item.getTaskName() + "' удалена из очереди");
        return Map.of(
                "message", "Задача '" + item.getTaskName() + "' удалена из очереди",
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

        String action = item.isInsertAtFront() ? "ВКЛИНИТЬ" : "В_ОЧЕРЕДЬ";
        apiState.addSystemLog("OPERATOR", "Ручное решение: '" + item.getBatchName() + "' → " + action);

        return Map.of(
                "message", "Ручное решение принято: " + item.getBatchName(),
                "action", action
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

    /**
     * Toggles manual mode and updates corresponding state
     */
    @PostMapping("/toggle-manual")
    public Map<String, Boolean> toggleManualMode() {
        boolean newState = !apiState.isManualMode();
        apiState.setManualMode(newState);
        if (newState) {
            apiState.setStatus("MANUAL_INTERVENTION");
            apiState.setStep("ОЖИДАНИЕ_ОПЕРАТОРА (Ручной режим)");
            apiState.addSystemLog("HW_BRIDGE", "Аварийное вмешательство: ВКЛЮЧЕНО");
        } else {
            apiState.setStatus("AUTO_NORMAL");
            apiState.setStep("Возврат в автоматический режим");
            apiState.addSystemLog("HW_BRIDGE", "Аварийное вмешательство: ОТКЛЮЧЕНО");
        }
        return Map.of("manualMode", newState);
    }
}
