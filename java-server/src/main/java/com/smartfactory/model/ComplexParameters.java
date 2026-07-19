package com.smartfactory.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Параметры производственного комплекса.
 * Соответствует цилиндру «Параметры комплекса» на блок-схеме.
 * Содержит информацию об оборудовании, линиях и их характеристиках.
 */
@Data
@NoArgsConstructor
public class ComplexParameters {

    /**
     * Описание одного исполнительного устройства (ванна, сушилка, кран и т.д.)
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Equipment {
        private String id;           // e.g. "BATH_DEGREASE", "BATH_ETCH", "BATH_ZINC", "DRYER", "CRANE"
        private String name;         // e.g. "Ванна обезжиривания"
        private double capacity;     // Максимальная загрузка (кг или шт.)
        private double currentLoad;  // Текущая загрузка
        private int processingTimeSec; // Базовое время обработки одной партии (сек)
        private boolean available;   // Доступно ли устройство
    }

    /**
     * Описание производственной линии (последовательность устройств)
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProductionLine {
        private String id;                  // e.g. "LINE_GALVANIC_1"
        private String name;                // e.g. "Гальваническая линия №1"
        private List<String> equipmentIds;  // Порядок устройств на линии
        private boolean active;
    }

    // Все устройства комплекса
    private List<Equipment> equipment = new ArrayList<>();

    // Все линии комплекса
    private List<ProductionLine> lines = new ArrayList<>();

    // Глобальные параметры
    private double maxTotalLoad = 500.0;          // Максимальная суммарная загрузка комплекса (кг)
    private double criticalUtilizationThreshold = 0.85; // Порог критической загруженности (85%)
    private int shiftDurationSec = 28800;         // Длительность смены (8 часов = 28800 сек)
    private int currentShiftElapsedSec = 0;       // Сколько прошло от начала смены

    /**
     * Инициализация параметров комплекса по умолчанию (симуляция).
     */
    public static ComplexParameters createDefault() {
        ComplexParameters params = new ComplexParameters();

        params.getEquipment().add(new Equipment(
                "BATH_DEGREASE", "01 Обезжиривание", 100.0, 0.0, 600, true));
        params.getEquipment().add(new Equipment(
                "BATH_ETCH", "02 Травление", 80.0, 0.0, 900, true));
        params.getEquipment().add(new Equipment(
                "BATH_ZINC", "03 Ванна цинка", 120.0, 0.0, 1200, true));
        params.getEquipment().add(new Equipment(
                "DRYER", "04 Сушильная камера", 150.0, 0.0, 1800, true));
        params.getEquipment().add(new Equipment(
                "CRANE", "Мостовой кран", 50.0, 0.0, 120, true));

        List<String> galvanicLine = List.of(
                "BATH_DEGREASE", "BATH_ETCH", "BATH_ZINC", "DRYER");
        params.getLines().add(new ProductionLine(
                "LINE_GALVANIC_1", "Гальваническая линия №1", galvanicLine, true));

        return params;
    }

    /**
     * Вычисляет коэффициент загруженности конкретного устройства.
     */
    public double getEquipmentUtilization(String equipmentId) {
        return equipment.stream()
                .filter(e -> e.getId().equals(equipmentId))
                .findFirst()
                .map(e -> e.getCapacity() > 0 ? e.getCurrentLoad() / e.getCapacity() : 0.0)
                .orElse(0.0);
    }

    /**
     * Средний коэффициент загруженности всех устройств.
     */
    public double getAverageUtilization() {
        return equipment.stream()
                .filter(e -> e.getCapacity() > 0)
                .mapToDouble(e -> e.getCurrentLoad() / e.getCapacity())
                .average()
                .orElse(0.0);
    }
}
