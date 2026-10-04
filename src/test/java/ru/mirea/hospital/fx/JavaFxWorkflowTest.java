package ru.mirea.hospital.fx;

import static org.junit.jupiter.api.Assertions.*;

import java.time.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import javafx.application.Platform;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.PostgreSQLContainer;
import ru.mirea.hospital.model.*;
import ru.mirea.hospital.repository.*;
import ru.mirea.hospital.service.*;
import ru.mirea.hospital.util.*;

/** Requires a desktop session; run with -Dhospital.uiTests=true. */
@EnabledIfSystemProperty(named = "hospital.uiTests", matches = "true")
class JavaFxWorkflowTest {
    @Test
    void realFxmlLoginBookingFiltersTransferCancelDeleteAndAdmin() throws Exception {
        try (var postgres =
                new PostgreSQLContainer<>("postgres:18").withDatabaseName("hospital_ui_test")) {
            postgres.start();
            var db =
                    new DatabaseManager(
                            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            db.initialize();
            DemoData.initialize(db, true, null);
            var app =
                    new HospitalApplication() {
                        @Override
                        public void connect() {}
                    };
            app.users = new UserService(new UserRepository(db));
            app.appointments =
                    new AppointmentService(
                            new JdbcAppointmentRepository(db),
                            app.users,
                            Clock.system(ZoneId.of("Europe/Moscow")));
            var started = new CountDownLatch(1);
            Platform.startup(
                    () -> {
                        Platform.setImplicitExit(false);
                        started.countDown();
                    });
            assertTrue(started.await(10, TimeUnit.SECONDS));
            Stage stage =
                    fx(
                            () -> {
                                Stage value = new Stage();
                                app.start(value);
                                return value;
                            });
            try {
                fx(
                        () -> {
                            for (String screen :
                                    new String[] {"login", "main", "appointment", "users"}) {
                                Parent view = app.loader(screen).load();
                                Scene scene = new Scene(view);
                                scene.getStylesheets()
                                        .add(
                                                getClass()
                                                        .getResource("/fx/hospital.css")
                                                        .toExternalForm());
                                view.applyCss();
                                view.layout();
                            }
                            return null;
                        });
                Parent login = fx(() -> stage.getScene().getRoot());
                fx(
                        () -> {
                            ((TextField) login.lookup("#name")).setText("Иванов Иван Иванович");
                            ((PasswordField) login.lookup("#password")).setText("Password123!");
                            return null;
                        });
                click(login, "Зарегистрироваться");
                waitFor(
                        () ->
                                Window.getWindows().stream()
                                        .anyMatch(
                                                w ->
                                                        w.getScene().getRoot()
                                                                        instanceof DialogPane p
                                                                && p.getContentText() != null
                                                                && p.getContentText()
                                                                        .contains(
                                                                                "«Логин» не заполнено")));
                confirm();
                fx(
                        () -> {
                            assertEquals(
                                    "Иванов Иван Иванович",
                                    ((TextField) login.lookup("#name")).getText());
                            ((TextField) login.lookup("#login")).setText("gg_wp");
                            return null;
                        });
                click(login, "Зарегистрироваться");
                waitFor(
                        () ->
                                !login.isDisabled()
                                        && ((Label) login.lookup("#feedback"))
                                                .getText()
                                                .contains("Регистрация завершена"));
                assertEquals(
                        "Иванов Иван Иванович",
                        app.users.login("gg_wp", "Password123!").getFullName());

                fx(
                        () -> {
                            ((TextField) login.lookup("#login")).setText("patient1");
                            ((PasswordField) login.lookup("#password")).setText("DemoPass123!");
                            return null;
                        });
                click(login, "Войти");
                waitFor(
                        () ->
                                stage.getScene().getRoot().lookup("#table") != null
                                        && !stage.getScene().getRoot().isDisabled());
                Parent main = fx(() -> stage.getScene().getRoot());
                TableView<Appointment> table =
                        fx(() -> (TableView<Appointment>) main.lookup("#table"));
                int initial = fx(() -> table.getItems().size());
                assertTrue(initial > 0);
                click(main, "＋ Записаться");
                Parent form = dialogRoot("#specialty");
                waitFor(() -> !form.isDisabled());
                click(form, "Сохранить запись");
                waitFor(
                        () ->
                                Window.getWindows().stream()
                                        .anyMatch(
                                                w ->
                                                        w.getScene().getRoot()
                                                                        instanceof DialogPane p
                                                                && "Операция не выполнена"
                                                                        .equals(
                                                                                p
                                                                                        .getHeaderText())));
                confirm();
                waitFor(() -> form.getScene().getWindow().isShowing() && !form.isDisabled());
                LocalDate day = LocalDate.now(ZoneId.of("Europe/Moscow")).plusDays(25);
                while (day.getDayOfWeek().getValue() > 5) day = day.plusDays(1);
                LocalDate chosen = day;
                fx(
                        () -> {
                            ((ComboBox<String>) form.lookup("#specialty")).setValue("Терапевт");
                            ((DatePicker) form.lookup("#date")).setValue(chosen);
                            return null;
                        });
                waitFor(
                        () ->
                                !form.isDisabled()
                                        && !((ComboBox<?>) form.lookup("#time"))
                                                .getItems()
                                                .isEmpty());
                fx(
                        () -> {
                            ((ComboBox<?>) form.lookup("#time")).getSelectionModel().selectFirst();
                            return null;
                        });
                waitFor(
                        () ->
                                !form.isDisabled()
                                        && !((ComboBox<?>) form.lookup("#doctor"))
                                                .getItems()
                                                .isEmpty());
                fx(
                        () -> {
                            ((ComboBox<?>) form.lookup("#doctor"))
                                    .getSelectionModel()
                                    .selectFirst();
                            return null;
                        });
                click(form, "Сохранить запись");
                waitFor(() -> !main.isDisabled() && table.getItems().size() == initial + 1);
                Appointment created =
                        fx(
                                () ->
                                        table.getItems().stream()
                                                .filter(
                                                        a ->
                                                                a.startsAt()
                                                                        .toLocalDate()
                                                                        .equals(chosen))
                                                .findFirst()
                                                .orElseThrow());
                fx(
                        () -> {
                            ((TextField) main.lookup("#search")).setText("Терапевт");
                            return null;
                        });
                assertTrue(
                        fx(
                                () ->
                                        table.getItems().stream()
                                                .allMatch(a -> a.specialty().equals("Терапевт"))));
                fx(
                        () -> {
                            ((ComboBox<AppointmentStatus>) main.lookup("#status"))
                                    .setValue(AppointmentStatus.CANCELLED);
                            return null;
                        });
                assertTrue(
                        fx(
                                () ->
                                        table.getItems().stream()
                                                .allMatch(
                                                        a ->
                                                                a.status()
                                                                        == AppointmentStatus
                                                                                .CANCELLED)));
                click(main, "Сбросить");
                waitFor(() -> table.getItems().size() == initial + 1);
                fx(
                        () -> {
                            table.getSelectionModel().select(created);
                            return null;
                        });
                click(main, "Перенести");
                Parent edit = dialogRoot("#specialty");
                waitFor(
                        () ->
                                !edit.isDisabled()
                                        && ((ComboBox<?>) edit.lookup("#doctor")).getValue()
                                                != null);
                assertEquals(chosen, fx(() -> ((DatePicker) edit.lookup("#date")).getValue()));
                fx(
                        () -> {
                            ((ComboBox<?>) edit.lookup("#time")).getSelectionModel().select(1);
                            return null;
                        });
                waitFor(
                        () ->
                                !edit.isDisabled()
                                        && ((ComboBox<?>) edit.lookup("#doctor")).getValue()
                                                != null);
                click(edit, "Сохранить запись");
                waitFor(
                        () ->
                                !main.isDisabled()
                                        && table.getItems().stream()
                                                .anyMatch(
                                                        a ->
                                                                a.id() == created.id()
                                                                        && a.rescheduleCount()
                                                                                == 1));
                fx(
                        () -> {
                            table.getSelectionModel()
                                    .select(
                                            table.getItems().stream()
                                                    .filter(a -> a.id() == created.id())
                                                    .findFirst()
                                                    .orElseThrow());
                            return null;
                        });
                click(main, "Отменить запись");
                confirm();
                waitFor(
                        () ->
                                !main.isDisabled()
                                        && table.getItems().stream()
                                                .anyMatch(
                                                        a ->
                                                                a.id() == created.id()
                                                                        && a.status()
                                                                                == AppointmentStatus
                                                                                        .CANCELLED));
                fx(
                        () -> {
                            table.getSelectionModel()
                                    .select(
                                            table.getItems().stream()
                                                    .filter(a -> a.id() == created.id())
                                                    .findFirst()
                                                    .orElseThrow());
                            return null;
                        });
                click(main, "Удалить");
                confirm();
                waitFor(() -> !main.isDisabled() && table.getItems().size() == initial);
                assertTrue(
                        fx(
                                () ->
                                        ((javafx.scene.layout.VBox) main.lookup("#statistics"))
                                                        .getChildren()
                                                        .size()
                                                >= 5));
                fx(
                        () -> {
                            app.actor = app.users.login("admin", "DemoPass123!");
                            app.show("main");
                            return null;
                        });
                waitFor(() -> !stage.getScene().getRoot().isDisabled());
                Parent admin = fx(() -> stage.getScene().getRoot());
                fx(
                        () -> {
                            Parent view = stage.getScene().getRoot();
                            view.applyCss();
                            view.layout();
                            javafx.scene.image.WritableImage image = view.snapshot(null, null);
                            java.awt.image.BufferedImage png =
                                    new java.awt.image.BufferedImage(
                                            (int) image.getWidth(),
                                            (int) image.getHeight(),
                                            java.awt.image.BufferedImage.TYPE_INT_ARGB);
                            for (int y = 0; y < png.getHeight(); y++)
                                for (int x = 0; x < png.getWidth(); x++)
                                    png.setRGB(x, y, image.getPixelReader().getArgb(x, y));
                            javax.imageio.ImageIO.write(
                                    png, "png", new java.io.File("target/javafx-main.png"));
                            return null;
                        });
                click(admin, "Пользователи");
                Parent users = dialogRoot("#name");
                waitFor(() -> !users.isDisabled());
                assertEquals(
                        6, fx(() -> ((TableView<?>) users.lookup("#table")).getItems().size()));
                fx(
                        () -> {
                            users.getScene().getWindow().hide();
                            return null;
                        });
            } finally {
                fx(
                        () -> {
                            for (Window window : java.util.List.copyOf(Window.getWindows()))
                                window.hide();
                            return null;
                        });
                Platform.exit();
            }
        }
    }

    private static <T> T fx(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(20, TimeUnit.SECONDS);
    }

    private static void waitFor(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(25);
        while (System.nanoTime() < deadline) {
            if (fx(condition::getAsBoolean)) return;
            Thread.sleep(50);
        }
        fail(
                "JavaFX condition timed out: "
                        + fx(
                                () ->
                                        Window.getWindows().stream()
                                                .map(
                                                        w ->
                                                                w.getScene().getRoot()
                                                                                instanceof
                                                                                DialogPane p
                                                                        ? p.getHeaderText()
                                                                                + ": "
                                                                                + p.getContentText()
                                                                        : w.getScene()
                                                                                        .getRoot()
                                                                                        .getClass()
                                                                                        .getName()
                                                                                + " disabled="
                                                                                + w.getScene()
                                                                                        .getRoot()
                                                                                        .isDisabled())
                                                .toList()));
    }

    private static void click(Parent root, String text) throws Exception {
        fx(
                () -> {
                    root.applyCss();
                    root.layout();
                    return null;
                });
        waitFor(
                () ->
                        root.lookupAll(".button").stream()
                                .anyMatch(
                                        n ->
                                                n instanceof Button b
                                                        && b.getText().equals(text)
                                                        && !b.isDisabled()));
        Platform.runLater(
                () ->
                        root.lookupAll(".button").stream()
                                .filter(n -> n instanceof Button b && b.getText().equals(text))
                                .map(n -> (Button) n)
                                .findFirst()
                                .orElseThrow()
                                .fire());
    }

    private static Parent dialogRoot(String selector) throws Exception {
        waitFor(
                () ->
                        Window.getWindows().stream()
                                .anyMatch(
                                        w ->
                                                w.isShowing()
                                                        && w.getScene().getRoot()
                                                                instanceof DialogPane
                                                        && w.getScene().getRoot().lookup(selector)
                                                                != null));
        return fx(
                () -> {
                    DialogPane pane =
                            (DialogPane)
                                    Window.getWindows().stream()
                                            .filter(
                                                    w ->
                                                            w.isShowing()
                                                                    && w.getScene().getRoot()
                                                                            instanceof DialogPane
                                                                    && w.getScene()
                                                                                    .getRoot()
                                                                                    .lookup(
                                                                                            selector)
                                                                            != null)
                                            .findFirst()
                                            .orElseThrow()
                                            .getScene()
                                            .getRoot();
                    return (Parent) pane.getContent();
                });
    }

    private static void confirm() throws Exception {
        waitFor(
                () ->
                        Window.getWindows().stream()
                                .anyMatch(
                                        w ->
                                                w.getScene().getRoot() instanceof DialogPane p
                                                        && p.getButtonTypes()
                                                                .contains(ButtonType.OK)));
        Platform.runLater(
                () ->
                        Window.getWindows().stream()
                                .filter(
                                        w ->
                                                w.getScene().getRoot() instanceof DialogPane p
                                                        && p.getButtonTypes()
                                                                .contains(ButtonType.OK))
                                .findFirst()
                                .ifPresent(
                                        w ->
                                                ((Button)
                                                                ((DialogPane)
                                                                                w.getScene()
                                                                                        .getRoot())
                                                                        .lookupButton(
                                                                                ButtonType.OK))
                                                        .fire()));
    }
}
