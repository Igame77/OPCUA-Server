package com.smartfactory.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Последовательность действий для обработки партии.
 * Соответствует блокам «Расчёт последовательности действий» и
 * «Утверждение последовательности действий (расписания)» на блок-схеме.
 */
@Data
@NoArgsConstructor
public class ActionSequence {

    /**
     * Один шаг в последовательности действий
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Action {
        private int stepNumber;          // Порядковый номер шага
        private String equipmentId;      // ID устройства
        private String equipmentName;    // Имя устройства
        private String operation;        // Операция
        private int startTimeSec;        // Время начала (сек от начала смены)
        private int durationSec;         // Длительность (сек)
        private String status;           // PLANNED, APPROVED, IN_PROGRESS, COMPLETED, SKIPPED
    }

    // Для какой партии
    private String batchId;
    private String batchName;

    // Список действий
    private List<Action> actions = new ArrayList<>();

    // Статус утверждения
    private boolean approved = false;
    private String approvalStatus = "PENDING"; // PENDING, APPROVED, REJECTED
    private String rejectionCause;             // Причина отказа

    // Суммарные данные
    private int totalDurationSec = 0;
    private int estimatedStartSec = 0;
    private int estimatedEndSec = 0;

    // Нужен ли пересчёт?
    private boolean needsRecalculation = false;
    private int recalculationCount = 0;

    /**
     * Рассчитывает суммарные данные из действий
     */
    public void recalculateTotals() {
        if (actions.isEmpty()) return;
        totalDurationSec = actions.stream().mapToInt(Action::getDurationSec).sum();
        estimatedStartSec = actions.stream().mapToInt(Action::getStartTimeSec).min().orElse(0);
        estimatedEndSec = actions.stream()
                .mapToInt(a -> a.getStartTimeSec() + a.getDurationSec())
                .max().orElse(0);
    }
}
