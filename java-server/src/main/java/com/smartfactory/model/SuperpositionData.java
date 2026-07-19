package com.smartfactory.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Файл суперпозиции.
 * Соответствует цилиндру «Файл суперпозиции» на блок-схеме.
 * Содержит наложение расчётного времени обработки на загрузку устройств,
 * определяя свободные интервалы и возможность включения новой партии.
 */
@Data
@NoArgsConstructor
public class SuperpositionData {

    /**
     * Временной интервал на конкретном устройстве
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TimeSlot {
        private String equipmentId;
        private int startTimeSec;     // Начало интервала (секунды от начала смены)
        private int endTimeSec;       // Конец интервала
        private boolean occupied;     // Занят ли интервал
        private String occupiedBy;    // ID партии, занявшей интервал (если occupied)
    }

    /**
     * Результат сопоставления одной партии с расписанием
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FitResult {
        private String batchId;
        private boolean canFit;           // Возможно ли включить
        private String reason;            // Причина отказа (если canFit == false)
        private int proposedStartSec;     // Предложенное время начала
        private int proposedEndSec;       // Предложенное время окончания
        private double requiredCapacity;  // Требуемая загрузка
        private double availableCapacity; // Доступная загрузка
    }

    // Все временные интервалы по устройствам
    private List<TimeSlot> timeSlots = new ArrayList<>();

    // Результаты последнего сопоставления
    private List<FitResult> fitResults = new ArrayList<>();

    // Свободные интервалы (вычисленные)
    private List<TimeSlot> freeIntervals = new ArrayList<>();

    // Метаданные
    private long calculatedAt;
    private int shiftDurationSec;
    private double overallUtilization;
}
