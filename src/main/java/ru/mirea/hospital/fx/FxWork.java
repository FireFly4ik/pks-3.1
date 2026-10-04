package ru.mirea.hospital.fx;

import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javafx.concurrent.Task;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import ru.mirea.hospital.exception.*;

public final class FxWork {
    private FxWork() {}

    public static <T> void run(Node owner, Callable<T> work, Consumer<T> success) {
        owner.setDisable(true);
        Task<T> task =
                new Task<>() {
                    @Override
                    protected T call() throws Exception {
                        return work.call();
                    }
                };
        task.setOnSucceeded(
                event -> {
                    owner.setDisable(false);
                    try {
                        success.accept(task.getValue());
                    } catch (Exception e) {
                        error(owner, e);
                    }
                });
        task.setOnFailed(
                event -> {
                    owner.setDisable(false);
                    error(owner, task.getException());
                });
        Thread thread = new Thread(task, "hospital-database");
        thread.setDaemon(true);
        thread.start();
    }

    public static void error(Node owner, Throwable error) {
        String message =
                error instanceof BusinessException
                                || error instanceof DataAccessException
                                || error instanceof IllegalArgumentException
                        ? error.getMessage()
                        : error instanceof java.nio.file.FileAlreadyExistsException
                                ? "Файл уже существует. Выберите новое имя, чтобы сохранить экспорт."
                                : "Не удалось выполнить операцию. Проверьте подключение к базе или доступ к файлу и повторите попытку.";
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.initOwner(owner.getScene().getWindow());
        alert.setHeaderText("Операция не выполнена");
        alert.setContentText(message);
        alert.showAndWait();
    }
}
