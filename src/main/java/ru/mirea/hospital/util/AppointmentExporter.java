package ru.mirea.hospital.util;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import ru.mirea.hospital.model.Appointment;

public interface AppointmentExporter {
    String[] HEADERS = {
        "ID",
        "Пациент",
        "Врач",
        "Специальность",
        "Время приёма",
        "Создана",
        "Статус",
        "Число переносов"
    };

    void export(List<Appointment> appointments, Path path) throws IOException;
}
