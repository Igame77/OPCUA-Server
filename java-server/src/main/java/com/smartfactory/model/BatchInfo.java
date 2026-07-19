package com.smartfactory.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Информация о партии / заказе на обработку.
 * Расширяет простую строку из очереди, добавляя приоритет,
 * материал, требуемые этапы, расчётное время.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BatchInfo {

    /**
     * Этап обработки партии
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ProcessingStep {
        private String equipmentId;   // ID устройства
        private String operation;     // Название операции
        private int durationSec;      // Длительность (сек)
        private boolean completed;    // Завершён ли
    }

    // Идентификация
    private String id;                     // Уникальный ID
    private String name;                   // Имя партии (e.g. "Партия_1_Сталь")
    private String material;               // Материал (Сталь, Медь, Алюминий)
    private double weight;                 // Вес партии (кг)

    // Приоритизация (вычисляемые поля)
    private int priority = 0;              // Итоговый приоритет (0 = не рассчитан)
    private int urgencyScore = 0;          // Балл срочности
    private int materialScore = 0;         // Балл по типу материала
    private int weightScore = 0;           // Балл по весу
    private String assignedLineId;         // Назначенная линия
    private int orderInLine = 0;           // Очерёдность на линии

    // Обработка
    private String status = "WAITING";     // WAITING, PRIORITIZED, SCHEDULED, ACTIVE, COMPLETED, REJECTED
    private int totalProcessingTimeSec = 0; // Суммарное расчётное время обработки
    private List<ProcessingStep> steps = new ArrayList<>(); // Последовательность шагов

    // Временные метки (имитация)
    private long createdAt;
    private long scheduledStartTime;
    private long scheduledEndTime;

    /**
     * Создаёт партию из простого имени (обратная совместимость со старым форматом)
     */
    public static BatchInfo fromLegacyName(String name) {
        BatchInfo batch = new BatchInfo();
        batch.setId("BATCH_" + System.currentTimeMillis() + "_" + (int)(Math.random() * 1000));
        batch.setName(name);
        batch.setCreatedAt(System.currentTimeMillis());

        // Определяем материал по имени
        if (name.contains("Сталь") || name.contains("сталь")) {
            batch.setMaterial("Сталь");
            batch.setWeight(45.0 + Math.random() * 30);
        } else if (name.contains("Медь") || name.contains("медь")) {
            batch.setMaterial("Медь");
            batch.setWeight(20.0 + Math.random() * 20);
        } else if (name.contains("Алюм") || name.contains("алюм")) {
            batch.setMaterial("Алюминий");
            batch.setWeight(15.0 + Math.random() * 25);
        } else {
            batch.setMaterial("Сталь"); // default
            batch.setWeight(30.0 + Math.random() * 40);
        }

        batch.setStatus("WAITING");
        return batch;
    }
}
