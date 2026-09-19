package com.smartfactory.state;

import lombok.Data;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Equivalent to the global 'api_state' dictionary in Python.
 * Holds the current state of the Galvanic Line for the Android/Web MES API.
 * Extended with APS algorithm state fields.
 */
@Component
@Data
public class ApiState {
    // === Оригинальные поля (не трогаем) ===
    private int temperature = 0;
    private int humidity = 0;
    private boolean doorClosed = true;
    private boolean manualMode = false;
    private String status = "STARTING";
    private String step = "Инициализация";
    private boolean simRunning = false;
    private boolean clientConnected = false;

    // === Новые поля: состояние APS-алгоритма ===
    private String apsPhase = "IDLE";              // Текущая фаза алгоритма
    private double utilizationCoeff = 0.0;         // Коэффициент загруженности
    private int processingTimeEstimate = 0;        // Расчётное время обработки (сек)
    private boolean canIncludeResult = false;       // Результат проверки включения
    private String currentBatch = "";               // Текущая обрабатываемая партия
    private String rejectionCause = "";             // Причина отказа
    private int actionSequenceSteps = 0;            // Кол-во шагов в последовательности
    private int recalculationCount = 0;             // Счётчик пересчётов
    private int totalCyclesCompleted = 0;           // Всего завершённых циклов APS

    // === Новые поля: состояние 7 технологических узлов линии цинкования ===
    private Stage1Degreasing stage1 = new Stage1Degreasing();
    private Stage2Rinsing stage2 = new Stage2Rinsing();
    private Stage3Pickling stage3 = new Stage3Pickling();
    private Stage4Rinsing stage4 = new Stage4Rinsing();
    private Stage5Fluxing stage5 = new Stage5Fluxing();
    private Stage6DryingChamber stage6 = new Stage6DryingChamber();
    private Stage7ZincBath stage7 = new Stage7ZincBath();

    // История APS-шагов (последние N записей для лога)
    private List<String> apsHistory = new ArrayList<>();

    // Системный лог (последние 30 записей)
    private List<LogEntry> systemLog = new ArrayList<>();

    /**
     * Возвращает фабричное состояние в формате словаря Python FastAPI
     */
    public java.util.Map<String, Object> getFactoryStateMap() {
        return java.util.Map.of(
                "stage_1_alkaline_degreasing", java.util.Map.of(
                        "temperature", stage1.getTemperature(),
                        "is_active", stage1.isActive(),
                        "heater_on", stage1.isHeaterOn()
                ),
                "stage_2_rinsing_1", java.util.Map.of(
                        "is_active", stage2.isActive(),
                        "pump_running", stage2.isPumpRunning()
                ),
                "stage_3_pickling", java.util.Map.of(
                        "temperature", stage3.getTemperature(),
                        "is_active", stage3.isActive(),
                        "agitator_on", stage3.isAgitatorOn()
                ),
                "stage_4_rinsing_2", java.util.Map.of(
                        "is_active", stage4.isActive(),
                        "pump_running", stage4.isPumpRunning()
                ),
                "stage_5_fluxing", java.util.Map.of(
                        "temperature", stage5.getTemperature(),
                        "is_active", stage5.isActive(),
                        "heater_on", stage5.isHeaterOn()
                ),
                "stage_6_drying_chamber", java.util.Map.of(
                        "temperature_celsius", stage6.getTemperatureCelsius(),
                        "humidity_percent", stage6.getHumidityPercent(),
                        "is_active", stage6.isActive(),
                        "fan_on", stage6.isFanOn(),
                        "sensor_connected", stage6.isSensorConnected()
                ),
                "stage_7_zinc_bath", java.util.Map.of(
                        "temperature", stage7.getTemperature(),
                        "is_active", stage7.isActive(),
                        "heater_on", stage7.isHeaterOn()
                )
        );
    }

    /**
     * Добавляет запись в историю APS (хранит последние 20).
     */
    public synchronized void addApsHistoryEntry(String entry) {
        apsHistory.add(entry);
        if (apsHistory.size() > 20) {
            apsHistory = new ArrayList<>(apsHistory.subList(apsHistory.size() - 20, apsHistory.size()));
        }
    }

    /**
     * Добавляет запись в системный лог (хранит последние 30).
     */
    public synchronized void addSystemLog(String source, String message) {
        String time = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
        systemLog.add(new LogEntry(time, source, message));
        if (systemLog.size() > 30) {
            systemLog = new ArrayList<>(systemLog.subList(systemLog.size() - 30, systemLog.size()));
        }
    }

    @Data
    public static class Stage1Degreasing {
        private int temperature = 50;
        private boolean isActive = false;
        private boolean heaterOn = false;
    }

    @Data
    public static class Stage2Rinsing {
        private boolean isActive = false;
        private boolean pumpRunning = false;
    }

    @Data
    public static class Stage3Pickling {
        private int temperature = 25;
        private boolean isActive = false;
        private boolean agitatorOn = false;
    }

    @Data
    public static class Stage4Rinsing {
        private boolean isActive = false;
        private boolean pumpRunning = false;
    }

    @Data
    public static class Stage5Fluxing {
        private int temperature = 60;
        private boolean isActive = false;
        private boolean heaterOn = false;
    }

    @Data
    public static class Stage6DryingChamber {
        private int temperatureCelsius = 55;
        private int humidityPercent = 20;
        private boolean isActive = false;
        private boolean fanOn = false;
        private boolean sensorConnected = true;
    }

    @Data
    public static class Stage7ZincBath {
        private int temperature = 450;
        private boolean isActive = true;
        private boolean heaterOn = false;
    }

    @Data
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    public static class LogEntry {
        private String time;
        private String source;
        private String message;
    }
}
