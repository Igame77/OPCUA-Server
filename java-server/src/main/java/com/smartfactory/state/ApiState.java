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

    // История APS-шагов (последние N записей для лога)
    private List<String> apsHistory = new ArrayList<>();

    // Системный лог (последние 30 записей)
    private List<LogEntry> systemLog = new ArrayList<>();

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
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    public static class LogEntry {
        private String time;
        private String source;
        private String message;
    }
}
