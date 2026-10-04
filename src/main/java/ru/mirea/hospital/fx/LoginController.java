package ru.mirea.hospital.fx;

import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import ru.mirea.hospital.exception.BusinessException;
import ru.mirea.hospital.service.UserService;

public class LoginController {
    private final HospitalApplication app;
    @FXML private VBox root;
    @FXML private TextField login, name;
    @FXML private PasswordField password;
    @FXML private Label feedback;

    public LoginController(HospitalApplication app) {
        this.app = app;
    }

    @FXML
    private void login() {
        String l = login.getText(), p = password.getText();
        FxWork.run(
                root,
                () -> {
                    app.connect();
                    var user = app.users.login(l, p);
                    return app.users.requireUser(user);
                },
                user -> {
                    app.actor = user;
                    try {
                        app.show("main");
                    } catch (Exception e) {
                        FxWork.error(root, e);
                    }
                });
    }

    @FXML
    private void register() {
        String l = login.getText(), p = password.getText(), n = name.getText();
        feedback.setText("");
        try {
            UserService.validateRegistration(l, n, p);
        } catch (BusinessException e) {
            FxWork.error(root, e);
            return;
        }
        FxWork.run(
                root,
                () -> {
                    app.connect();
                    return app.users.register(l, n, p);
                },
                user -> {
                    feedback.setText("Регистрация завершена. Теперь нажмите «Войти».");
                    password.clear();
                });
    }
}
