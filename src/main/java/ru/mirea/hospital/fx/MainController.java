package ru.mirea.hospital.fx;

import java.io.IOException;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.function.Function;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.*;
import javafx.collections.transformation.*;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import ru.mirea.hospital.exception.BusinessException;
import ru.mirea.hospital.model.*;
import ru.mirea.hospital.util.*;

public class MainController {
    private final HospitalApplication app;
    private final ObservableList<Appointment> rows = FXCollections.observableArrayList();
    private final FilteredList<Appointment> filtered = new FilteredList<>(rows);
    @FXML private BorderPane root;
    @FXML private TableView<Appointment> table;
    @FXML private TableView<Doctor> doctors;
    @FXML private TextField search;
    @FXML private ComboBox<AppointmentStatus> status;
    @FXML private DatePicker from, to;
    @FXML private Label identity, count, filterError, selectedDetails;
    @FXML private VBox statistics;
    @FXML private Button usersButton, editButton, cancelButton, deleteButton;

    public MainController(HospitalApplication app) {
        this.app = app;
    }

    @FXML
    private void initialize() {
        column(table, "Пациент", Appointment::patientName);
        column(table, "Врач", Appointment::doctorName);
        column(table, "Специальность", Appointment::specialty);
        var time = column(table, "Время приёма", Appointment::startsAt);
        dateCells(time);
        dateCells(column(table, "Создана", Appointment::createdAt));
        column(table, "Статус", Appointment::status);
        column(table, "Переносы", Appointment::rescheduleCount);
        SortedList<Appointment> sorted = new SortedList<>(filtered);
        sorted.comparatorProperty().bind(table.comparatorProperty());
        table.setItems(sorted);
        table.getSortOrder().add(time);
        table.setPlaceholder(
                new Label("Записей пока нет. Измените фильтры или запишитесь на приём."));
        column(doctors, "Специалист", Doctor::fullName);
        column(doctors, "Направление", Doctor::specialty);
        status.getItems().setAll(AppointmentStatus.values());
        search.textProperty().addListener((o, a, b) -> filter());
        status.valueProperty().addListener((o, a, b) -> filter());
        from.valueProperty().addListener((o, a, b) -> filter());
        to.valueProperty().addListener((o, a, b) -> filter());
        table.getSelectionModel().selectedItemProperty().addListener((o, a, b) -> selection(b));
        selection(null);
        if (app.actor != null) {
            identity.setText(
                    app.actor.getFullName()
                            + " · "
                            + (app.actor.getRole() == Role.ADMIN ? "Администратор" : "Пациент"));
            usersButton.setVisible(app.actor.getRole() == Role.ADMIN);
            usersButton.setManaged(app.actor.getRole() == Role.ADMIN);
        }
    }

    private static <T, V> TableColumn<T, V> column(
            TableView<T> table, String name, Function<T, V> value) {
        TableColumn<T, V> column = new TableColumn<>(name);
        column.setCellValueFactory(
                cell -> new ReadOnlyObjectWrapper<>(value.apply(cell.getValue())));
        column.setPrefWidth(160);
        table.getColumns().add(column);
        return column;
    }

    private static void dateCells(TableColumn<Appointment, LocalDateTime> column) {
        column.setCellFactory(
                c ->
                        new TableCell<>() {
                            @Override
                            protected void updateItem(LocalDateTime value, boolean empty) {
                                super.updateItem(value, empty);
                                setText(
                                        empty || value == null
                                                ? null
                                                : value.format(
                                                        DateTimeFormatter.ofPattern(
                                                                "dd.MM.yyyy HH:mm")));
                            }
                        });
    }

    private void selection(Appointment a) {
        selectedDetails.setText(
                a == null
                        ? "Выберите запись в таблице для просмотра и изменения."
                        : a.patientName() + " → " + a.doctorName() + " · " + a.specialty());
        boolean upcoming = a != null && a.status() == AppointmentStatus.UPCOMING;
        editButton.setDisable(!upcoming);
        cancelButton.setDisable(!upcoming);
        deleteButton.setDisable(
                a == null
                        || (a.status() != AppointmentStatus.COMPLETED
                                && a.status() != AppointmentStatus.CANCELLED));
    }

    @FXML
    public void refresh() {
        FxWork.run(
                root,
                () ->
                        new Snapshot(
                                app.appointments.list(app.actor),
                                app.appointments.doctors(app.actor),
                                app.appointments.statistics(app.actor)),
                snapshot -> {
                    rows.setAll(snapshot.appointments());
                    doctors.getItems().setAll(snapshot.doctors());
                    statistics.getChildren().clear();
                    snapshot.statistics()
                            .forEach(
                                    (name, value) -> {
                                        Label label = new Label(name + "    " + value);
                                        label.getStyleClass().add("metric");
                                        statistics.getChildren().add(label);
                                    });
                    filter();
                    selection(table.getSelectionModel().getSelectedItem());
                });
    }

    private record Snapshot(
            List<Appointment> appointments, List<Doctor> doctors, Map<String, Long> statistics) {}

    private void filter() {
        LocalDate start = from.getValue(), end = to.getValue();
        if (start != null && end != null && start.isAfter(end)) {
            filterError.setText("Дата «С» должна быть не позже даты «По».");
            return;
        }
        filterError.setText("");
        String query = search.getText().strip().toLowerCase(Locale.ROOT);
        filtered.setPredicate(
                a ->
                        (a.doctorName() + " " + a.specialty() + " " + a.patientName())
                                        .toLowerCase(Locale.ROOT)
                                        .contains(query)
                                && (status.getValue() == null || a.status() == status.getValue())
                                && (start == null || !a.startsAt().toLocalDate().isBefore(start))
                                && (end == null || !a.startsAt().toLocalDate().isAfter(end)));
        count.setText(
                "Показано "
                        + filtered.size()
                        + " из "
                        + rows.size()
                        + " · Сортировка: нажмите заголовок столбца");
    }

    @FXML
    private void reset() {
        search.clear();
        status.setValue(null);
        from.setValue(null);
        to.setValue(null);
    }

    @FXML
    private void add() {
        editor(null);
    }

    @FXML
    private void edit() {
        if (table.getSelectionModel().getSelectedItem() != null)
            editor(table.getSelectionModel().getSelectedItem());
    }

    private void editor(Appointment appointment) {
        try {
            var loader = app.loader("appointment");
            var content = (VBox) loader.load();
            Dialog<Void> dialog = new Dialog<>();
            dialog.initOwner(root.getScene().getWindow());
            dialog.setTitle(appointment == null ? "Новая запись" : "Перенести запись");
            dialog.getDialogPane().setContent(content);
            dialog.getDialogPane()
                    .getStylesheets()
                    .add(getClass().getResource("/fx/hospital.css").toExternalForm());
            dialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
            ((Button) dialog.getDialogPane().lookupButton(ButtonType.CANCEL)).setText("Закрыть");
            AppointmentController controller = loader.getController();
            controller.configure(
                    appointment,
                    () -> {
                        dialog.close();
                        refresh();
                    });
            dialog.showAndWait();
        } catch (IOException e) {
            FxWork.error(root, e);
        }
    }

    @FXML
    private void cancel() {
        change(false);
    }

    @FXML
    private void delete() {
        change(true);
    }

    private void change(boolean delete) {
        Appointment a = table.getSelectionModel().getSelectedItem();
        if (a == null) return;
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(root.getScene().getWindow());
        alert.setHeaderText(delete ? "Удалить запись безвозвратно?" : "Отменить запись на приём?");
        alert.setContentText(
                a.doctorName()
                        + " · "
                        + a.startsAt().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")));
        if (alert.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        FxWork.run(
                root,
                () -> {
                    if (delete) app.appointments.delete(app.actor, a.id());
                    else app.appointments.cancel(app.actor, a.id());
                    return true;
                },
                ok -> refresh());
    }

    @FXML
    private void export() {
        if (!filterError.getText().isEmpty()) {
            FxWork.error(root, new BusinessException("Исправьте диапазон дат перед экспортом."));
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Экспорт текущего списка");
        chooser.getExtensionFilters()
                .setAll(
                        new FileChooser.ExtensionFilter("Excel (*.xlsx)", "*.xlsx"),
                        new FileChooser.ExtensionFilter("CSV (*.csv)", "*.csv"));
        chooser.setInitialFileName("appointments-" + LocalDate.now() + ".xlsx");
        var file = chooser.showSaveDialog(root.getScene().getWindow());
        if (file == null) return;
        List<Appointment> current = List.copyOf(table.getItems());
        FxWork.run(
                root,
                () -> {
                    String name = file.getName().toLowerCase(Locale.ROOT);
                    AppointmentExporter exporter;
                    if (name.endsWith(".xlsx")) exporter = new ExcelExporter();
                    else if (name.endsWith(".csv")) exporter = new CsvExporter();
                    else throw new BusinessException("Укажите расширение .xlsx или .csv.");
                    exporter.export(current, file.toPath());
                    return file.getName();
                },
                name -> {
                    Alert alert =
                            new Alert(
                                    Alert.AlertType.INFORMATION,
                                    "Сохранено записей: " + current.size() + "\nФайл: " + name);
                    alert.initOwner(root.getScene().getWindow());
                    alert.setHeaderText("Экспорт завершён");
                    alert.showAndWait();
                });
    }

    @FXML
    private void users() {
        try {
            var loader = app.loader("users");
            Dialog<Void> dialog = new Dialog<>();
            dialog.initOwner(root.getScene().getWindow());
            dialog.setTitle("Пользователи");
            dialog.getDialogPane().setContent(loader.load());
            dialog.getDialogPane()
                    .getStylesheets()
                    .add(getClass().getResource("/fx/hospital.css").toExternalForm());
            dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
            ((UsersController) loader.getController()).refresh();
            dialog.showAndWait();
            refresh();
        } catch (IOException e) {
            FxWork.error(root, e);
        }
    }

    @FXML
    private void logout() {
        try {
            app.actor = null;
            app.show("login");
        } catch (IOException e) {
            FxWork.error(root, e);
        }
    }
}
