package ru.mirea.hospital.fx;

import java.time.*;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import ru.mirea.hospital.exception.BusinessException;
import ru.mirea.hospital.model.*;

public class AppointmentController {
    private final HospitalApplication app;
    private Appointment original;
    private Runnable saved;
    private boolean loading;
    @FXML private VBox root;
    @FXML private Label title, hint;
    @FXML private ComboBox<String> specialty;
    @FXML private DatePicker date;
    @FXML private ComboBox<LocalTime> time;
    @FXML private ComboBox<Doctor> doctor;

    public AppointmentController(HospitalApplication app) {
        this.app = app;
    }

    public void configure(Appointment original, Runnable saved) {
        this.original = original;
        this.saved = saved;
        title.setText(original == null ? "Запись на приём" : "Перенос · " + original.patientName());
        FxWork.run(
                root,
                () -> app.appointments.doctors(app.actor),
                values -> {
                    loading = true;
                    specialty
                            .getItems()
                            .setAll(
                                    values.stream()
                                            .map(Doctor::specialty)
                                            .distinct()
                                            .sorted()
                                            .toList());
                    if (original != null) {
                        specialty.setValue(original.specialty());
                        date.setValue(original.startsAt().toLocalDate());
                        time.getItems().setAll(original.startsAt().toLocalTime());
                        time.setValue(original.startsAt().toLocalTime());
                        Doctor selected =
                                values.stream()
                                        .filter(d -> d.id() == original.doctorId())
                                        .findFirst()
                                        .orElse(null);
                        doctor.getItems()
                                .setAll(
                                        values.stream()
                                                .filter(
                                                        d ->
                                                                d.specialty()
                                                                        .equals(
                                                                                original
                                                                                        .specialty()))
                                                .toList());
                        doctor.setValue(selected);
                        hint.setText("Текущие значения загружены. Выберите новое время или врача.");
                    }
                    loading = false;
                    if (original != null) loadTimes();
                });
    }

    @FXML
    private void loadTimes() {
        if (loading) return;
        LocalTime previous = time.getValue();
        time.getItems().clear();
        doctor.getItems().clear();
        String selected = specialty.getValue();
        LocalDate day = date.getValue();
        if (selected == null || day == null) return;
        FxWork.run(
                root,
                () ->
                        app.appointments.availableTimes(
                                app.actor, selected, day, original == null ? null : original.id()),
                values -> {
                    loading = true;
                    time.getItems().setAll(values);
                    if (values.contains(previous)) time.setValue(previous);
                    loading = false;
                    hint.setText(
                            values.isEmpty()
                                    ? "На эту дату свободного времени нет. Выберите другой день."
                                    : "Выберите время и свободного специалиста.");
                    if (time.getValue() != null) loadDoctors();
                });
    }

    @FXML
    private void loadDoctors() {
        if (loading) return;
        doctor.getItems().clear();
        String selected = specialty.getValue();
        LocalDate day = date.getValue();
        LocalTime at = time.getValue();
        if (selected == null || day == null || at == null) return;
        FxWork.run(
                root,
                () ->
                        app.appointments.availableDoctors(
                                app.actor,
                                selected,
                                day.atTime(at),
                                original == null ? null : original.id()),
                values -> {
                    doctor.getItems().setAll(values);
                    if (original != null)
                        doctor.setValue(
                                values.stream()
                                        .filter(d -> d.id() == original.doctorId())
                                        .findFirst()
                                        .orElse(null));
                    if (doctor.getValue() == null && values.size() == 1)
                        doctor.setValue(values.getFirst());
                });
    }

    @FXML
    private void save() {
        Doctor selected = doctor.getValue();
        LocalDate day = date.getValue();
        LocalTime at = time.getValue();
        if (specialty.getValue() == null || selected == null || day == null || at == null) {
            FxWork.error(
                    root, new BusinessException("Выберите специальность, дату, время и врача."));
            return;
        }
        FxWork.run(
                root,
                () -> {
                    if (original == null)
                        app.appointments.book(app.actor, selected.id(), day.atTime(at));
                    else
                        app.appointments.reschedule(
                                app.actor, original.id(), selected.id(), day.atTime(at));
                    return true;
                },
                ok -> saved.run());
    }
}
