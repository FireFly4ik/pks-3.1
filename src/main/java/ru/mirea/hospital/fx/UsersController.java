package ru.mirea.hospital.fx;

import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import ru.mirea.hospital.model.User;

public class UsersController {
    private final HospitalApplication app;
    @FXML private VBox root;
    @FXML private TableView<User> table;
    @FXML private TextField login, name;
    @FXML private PasswordField password;

    public UsersController(HospitalApplication app) {
        this.app = app;
    }

    @FXML
    private void initialize() {
        TableColumn<User, String> loginColumn = new TableColumn<>("Логин");
        loginColumn.setCellValueFactory(c -> new ReadOnlyObjectWrapper<>(c.getValue().getLogin()));
        TableColumn<User, String> nameColumn = new TableColumn<>("ФИО");
        nameColumn.setCellValueFactory(
                c -> new ReadOnlyObjectWrapper<>(c.getValue().getFullName()));
        nameColumn.setPrefWidth(260);
        TableColumn<User, String> roleColumn = new TableColumn<>("Роль");
        roleColumn.setCellValueFactory(
                c ->
                        new ReadOnlyObjectWrapper<>(
                                c.getValue().getRole() == ru.mirea.hospital.model.Role.ADMIN
                                        ? "Администратор"
                                        : "Пациент"));
        table.getColumns().add(loginColumn);
        table.getColumns().add(nameColumn);
        table.getColumns().add(roleColumn);
        table.getSelectionModel()
                .selectedItemProperty()
                .addListener(
                        (o, a, b) -> {
                            if (b != null) {
                                name.setText(b.getFullName());
                                login.setText(b.getLogin());
                                password.clear();
                            }
                        });
    }

    public void refresh() {
        FxWork.run(
                root, () -> app.users.list(app.actor), values -> table.getItems().setAll(values));
    }

    @FXML
    private void add() {
        String l = login.getText(), n = name.getText(), p = password.getText();
        FxWork.run(
                root,
                () -> {
                    app.users.requireAdmin(app.actor);
                    return app.users.register(l, n, p);
                },
                user -> {
                    password.clear();
                    refresh();
                });
    }

    @FXML
    private void rename() {
        User selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) {
            FxWork.error(root, new IllegalArgumentException("Выберите пользователя в таблице."));
            return;
        }
        String n = name.getText();
        FxWork.run(
                root,
                () -> {
                    app.users.rename(app.actor, selected.getId(), n);
                    return true;
                },
                ok -> refresh());
    }

    @FXML
    private void delete() {
        User selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) {
            FxWork.error(root, new IllegalArgumentException("Выберите пользователя в таблице."));
            return;
        }
        Alert alert =
                new Alert(
                        Alert.AlertType.CONFIRMATION,
                        "Удалить пользователя «" + selected.getFullName() + "»?");
        alert.initOwner(root.getScene().getWindow());
        alert.setHeaderText("Подтверждение удаления");
        if (alert.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        FxWork.run(
                root,
                () -> {
                    app.users.delete(app.actor, selected.getId());
                    return true;
                },
                ok -> refresh());
    }
}
