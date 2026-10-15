package ru.mirea.hospital.fx;

import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.VBox;
import ru.mirea.hospital.exception.BusinessException;
import ru.mirea.hospital.service.UserService;

public class LoginController {

    private final HospitalApplication app;

    @FXML private VBox root;
    @FXML private TextField login, name;
    @FXML private PasswordField password;
    @FXML private Label feedback;
    @FXML private Label titleLabel, subtitleLabel;
    @FXML private VBox registerFields;
    @FXML private Button submitButton;
    @FXML private ToggleButton loginModeBtn, registerModeBtn;

    private boolean isRegisterMode = false;

    public LoginController(HospitalApplication app) {
        this.app = app;
    }

    @FXML
    private void initialize() {
        ToggleGroup modeGroup = new ToggleGroup();
        loginModeBtn.setToggleGroup(modeGroup);
        registerModeBtn.setToggleGroup(modeGroup);

        feedback.textProperty()
                .addListener(
                        (obs, old, newText) -> {
                            boolean empty = newText == null || newText.isBlank();
                            feedback.setVisible(!empty);
                            feedback.setManaged(!empty);
                        });

        loginModeBtn.setSelected(true);
        updateMode();
    }

    @FXML
    private void switchToLogin() {
        isRegisterMode = false;
        updateMode();
    }

    @FXML
    private void switchToRegister() {
        isRegisterMode = true;
        updateMode();
    }

    private void updateMode() {
        feedback.setText("");
        if (isRegisterMode) {
            titleLabel.setText("Создание аккаунта");
            subtitleLabel.setText("Заполните все поля, чтобы зарегистрироваться.");
            registerFields.setVisible(true);
            registerFields.setManaged(true);
            submitButton.setText("Зарегистрироваться");
        } else {
            titleLabel.setText("Ваш следующий приём");
            subtitleLabel.setText("Войдите, чтобы управлять записями к специалистам.");
            registerFields.setVisible(false);
            registerFields.setManaged(false);
            submitButton.setText("Войти");
        }
        password.clear();
        name.clear();
        feedback.setText("");
    }

    @FXML
    private void handleSubmit() {
        if (isRegisterMode) {
            doRegister();
        } else {
            doLogin();
        }
    }

    private void doLogin() {
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

    private void doRegister() {
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
                    feedback.setText("Регистрация завершена. Теперь войдите в систему.");
                    isRegisterMode = false;
                    loginModeBtn.setSelected(true);
                    updateMode();
                });
    }
}
