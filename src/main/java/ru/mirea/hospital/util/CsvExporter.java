package ru.mirea.hospital.util;

import ru.mirea.hospital.model.Appointment;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class CsvExporter implements AppointmentExporter {
    public void export(List<Appointment> appointments, Path path) throws IOException {
        try (var writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
            writer.write('\ufeff');
            writeRow(writer, HEADERS);
            for (Appointment a : appointments) writeRow(writer, new String[]{String.valueOf(a.id()), a.patientName(),
                    a.doctorName(), a.specialty(), a.startsAt().toString(), a.createdAt().toString(), a.status().toString(), String.valueOf(a.rescheduleCount())});
        }
    }
    private void writeRow(Writer writer, String[] values) throws IOException {
        List<String> escaped = new ArrayList<>();
        for (String value : values) {
            if (!value.isEmpty() && "=+-@\t\r\n".indexOf(value.charAt(0)) >= 0) value = "'" + value;
            escaped.add("\"" + value.replace("\"", "\"\"") + "\"");
        }
        writer.write(String.join(";", escaped) + "\r\n");
    }
}
