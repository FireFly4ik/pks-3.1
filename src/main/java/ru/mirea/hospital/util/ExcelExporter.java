package ru.mirea.hospital.util;

import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import ru.mirea.hospital.model.Appointment;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;

public class ExcelExporter implements AppointmentExporter {
    public void export(List<Appointment> appointments, Path path) throws IOException {
        try (var book = new XSSFWorkbook()) {
            var sheet = book.createSheet("Записи на приём");
            var heading = book.createCellStyle();
            var font = book.createFont(); font.setBold(true); heading.setFont(font);
            var dateStyle = book.createCellStyle();
            dateStyle.setDataFormat(book.createDataFormat().getFormat("dd.mm.yyyy hh:mm"));
            var header = sheet.createRow(0);
            for (int i = 0; i < HEADERS.length; i++) { var cell = header.createCell(i); cell.setCellValue(HEADERS[i]); cell.setCellStyle(heading); }
            int index = 1;
            for (Appointment a : appointments) {
                var row = sheet.createRow(index++);
                row.createCell(0).setCellValue(a.id()); row.createCell(1).setCellValue(a.patientName());
                row.createCell(2).setCellValue(a.doctorName()); row.createCell(3).setCellValue(a.specialty());
                row.createCell(4).setCellValue(a.startsAt()); row.getCell(4).setCellStyle(dateStyle);
                row.createCell(5).setCellValue(a.createdAt()); row.getCell(5).setCellStyle(dateStyle);
                row.createCell(6).setCellValue(a.status().toString()); row.createCell(7).setCellValue(a.rescheduleCount());
            }
            sheet.createFreezePane(0, 1);
            sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(0, appointments.size(), 0, 7));
            int[] widths = {10, 32, 32, 24, 23, 23, 20, 20};
            for (int i = 0; i < widths.length; i++) sheet.setColumnWidth(i, widths[i] * 256);
            try (var output = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW)) { book.write(output); }
        }
    }
}
