package ru.mirea.hospital.fx;

import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Scene;
import javafx.stage.Stage;
import ru.mirea.hospital.model.User;
import ru.mirea.hospital.repository.*;
import ru.mirea.hospital.service.*;
import ru.mirea.hospital.util.DatabaseManager;

public class HospitalApplication extends Application {
    private Stage stage;
    public UserService users;
    public AppointmentService appointments;
    public User actor;

    @Override
    public void start(Stage stage) throws IOException {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Moscow"));
        this.stage = stage;
        stage.setTitle("Клиника · Запись на приём");
        stage.setMinWidth(780);
        stage.setMinHeight(580);
        show("login");
        stage.show();
    }

    public void connect() throws IOException {
        Properties config = new Properties();
        Path env = Path.of(".env");
        if (Files.exists(env)) {
            for (String line : Files.readAllLines(env)) {
                line = line.strip();
                int split = line.indexOf('=');
                if (line.startsWith("#") || split < 1) continue;
                String value = line.substring(split + 1).strip();
                if (value.length() >= 2
                        && ((value.startsWith("\"") && value.endsWith("\""))
                                || (value.startsWith("'") && value.endsWith("'"))))
                    value = value.substring(1, value.length() - 1);
                config.setProperty(line.substring(0, split).strip(), value);
            }
        }
        config.putAll(System.getenv());
        String password = config.getProperty("DB_PASSWORD");
        if (password == null || password.isBlank())
            throw new IllegalArgumentException(
                    "Укажите DB_PASSWORD в .env в корне проекта или в переменных окружения.");
        DatabaseManager db =
                new DatabaseManager(
                        config.getProperty(
                                "DB_URL",
                                "jdbc:postgresql://localhost:"
                                        + config.getProperty("DB_PORT", "55432")
                                        + "/hospital?connectTimeout=5&socketTimeout=15&options=-c%20TimeZone=Europe/Moscow"),
                        config.getProperty("DB_USER", "hospital"),
                        password);
        db.initialize();
        users = new UserService(new UserRepository(db));
        appointments =
                new AppointmentService(
                        new JdbcAppointmentRepository(db),
                        users,
                        Clock.system(ZoneId.of("Europe/Moscow")));
    }

    public FXMLLoader loader(String view) {
        FXMLLoader loader =
                new FXMLLoader(HospitalApplication.class.getResource("/fx/" + view + ".fxml"));
        loader.setControllerFactory(
                type -> {
                    try {
                        return type.getConstructor(HospitalApplication.class).newInstance(this);
                    } catch (ReflectiveOperationException e) {
                        throw new IllegalStateException("Не удалось открыть экран.", e);
                    }
                });
        return loader;
    }

    public void show(String view) throws IOException {
        FXMLLoader loader = loader(view);
        Scene scene = new Scene(loader.load(), 1180, 760);
        scene.getStylesheets().add(getClass().getResource("/fx/hospital.css").toExternalForm());
        stage.setScene(scene);
        if (loader.getController() instanceof MainController controller) controller.refresh();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
