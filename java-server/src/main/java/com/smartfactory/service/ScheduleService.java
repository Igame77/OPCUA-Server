package com.smartfactory.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartfactory.model.BatchInfo;
import lombok.Data;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Equivalent to the schedule.json file handling in Python.
 * Ensures thread-safe reading and writing.
 * Extended to work with BatchInfo objects instead of plain Strings.
 */
@Service
public class ScheduleService {
    private static final String SCHEDULE_FILE = "schedule.json";
    private final ObjectMapper mapper = new ObjectMapper();
    private final Object fileLock = new Object();

    @Data
    public static class ScheduleData {
        private List<BatchInfo> queue = new ArrayList<>();
        private List<BatchInfo> current_tasks = new ArrayList<>();
    }

    @PostConstruct
    public void initScheduleFile() {
        synchronized (fileLock) {
            File file = new File(SCHEDULE_FILE);
            if (!file.exists() || file.length() == 0) {
                ScheduleData defaultData = new ScheduleData();
                defaultData.getQueue().add(BatchInfo.fromLegacyName("Партия_1_Сталь"));
                defaultData.getQueue().add(BatchInfo.fromLegacyName("Заказ_B22_Медь"));
                defaultData.getQueue().add(BatchInfo.fromLegacyName("Партия_4_Алюм"));
                writeSchedule(defaultData);
            }
        }
    }

    public ScheduleData readSchedule() {
        synchronized (fileLock) {
            try {
                return mapper.readValue(new File(SCHEDULE_FILE), ScheduleData.class);
            } catch (Exception e) {
                System.out.println("[WARNING] schedule.json error. Recovering default data...");
                ScheduleData defaultData = new ScheduleData();
                defaultData.getQueue().add(BatchInfo.fromLegacyName("Партия_1_Сталь"));
                writeSchedule(defaultData);
                return defaultData;
            }
        }
    }

    public void writeSchedule(ScheduleData data) {
        synchronized (fileLock) {
            try {
                mapper.writerWithDefaultPrettyPrinter().writeValue(new File(SCHEDULE_FILE), data);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public List<BatchInfo> addTask(String taskName) {
        synchronized (fileLock) {
            ScheduleData data = readSchedule();
            data.getQueue().add(BatchInfo.fromLegacyName(taskName));
            writeSchedule(data);
            return data.getQueue();
        }
    }

    public BatchInfo popTask() {
        synchronized (fileLock) {
            ScheduleData data = readSchedule();
            if (!data.getQueue().isEmpty()) {
                BatchInfo task = data.getQueue().remove(0);
                data.getCurrent_tasks().add(task);
                writeSchedule(data);
                return task;
            }
            return null;
        }
    }
}
