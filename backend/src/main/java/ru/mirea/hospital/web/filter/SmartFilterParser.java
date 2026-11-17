package ru.mirea.hospital.web.filter;

import org.springframework.stereotype.Component;
import ru.mirea.hospital.web.domain.PriorityLevel;
import ru.mirea.hospital.web.domain.AppointmentStatus;
import ru.mirea.hospital.web.error.BadRequestException;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Разбирает человекочитаемый фильтр в типобезопасную модель.
 *
 * <p>Исходная строка фильтра не объединяется с SQL/JPQL. Парсер принимает только
 * известные команды, а Criteria API передаёт значения через параметры.</p>
 */
@Component
public class SmartFilterParser {
    private static final Pattern TOKEN = Pattern.compile("\\\"([^\\\"]*)\\\"|(\\S+)");
    private static final Pattern SCORE = Pattern.compile("^(?:готовность|score|readiness)(<=|>=|=|<|>)([0-9]+(?:[.,][0-9]{1,2})?)$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Разбирает строку; по умолчанию исключает архивные записи. */
    public AppointmentFilter parse(String source) {
        List<String> free = new ArrayList<>();
        String doctor = null;
        AppointmentStatus status = null;
        Boolean callback = null;
        PriorityLevel priority = null;
        String tag = null;
        Boolean archived = false;
        List<AppointmentFilter.ScoreConstraint> scores = new ArrayList<>();

        if (source == null || source.isBlank()) {
            return new AppointmentFilter("", null, null, null, null, null, false, List.of());
        }
        if (source.length() > 500) throw new BadRequestException("Строка фильтра слишком длинная");

        Matcher matcher = TOKEN.matcher(source.trim());
        while (matcher.find()) {
            String raw = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            String token = raw.trim();
            if (token.isEmpty()) continue;
            String lower = token.toLowerCase(Locale.ROOT);

            Matcher scoreMatcher = SCORE.matcher(lower);
            if (scoreMatcher.matches()) {
                BigDecimal value = new BigDecimal(scoreMatcher.group(2).replace(',', '.'));
                if (value.compareTo(BigDecimal.ZERO) < 0 || value.compareTo(new BigDecimal("100")) > 0) {
                    throw new BadRequestException("Значение готовности должно быть от 0 до 100");
                }
                scores.add(new AppointmentFilter.ScoreConstraint(operator(scoreMatcher.group(1)), value));
                continue;
            }

            int colon = token.indexOf(':');
            if (colon > 0) {
                String key = lower.substring(0, colon);
                String value = stripQuotes(token.substring(colon + 1).trim());
                String normalized = value.toLowerCase(Locale.ROOT);
                switch (key) {
                    case "врач", "doctor" -> doctor = value;
                    case "статус", "status" -> status = status(normalized);
                    case "звонок", "callback" -> callback = booleanValue(normalized, "звонок");
                    case "приоритет", "priority" -> priority = priority(normalized);
                    case "тег", "tag" -> tag = value;
                    case "архив", "archived" -> archived = archivedValue(normalized);
                    default -> free.add(token);
                }
            } else {
                free.add(token);
            }
        }
        return new AppointmentFilter(String.join(" ", free), blankToNull(doctor), status, callback, priority,
                blankToNull(tag), archived, List.copyOf(scores));
    }

    private static AppointmentFilter.Operator operator(String value) {
        return switch (value) {
            case "<" -> AppointmentFilter.Operator.LT;
            case "<=" -> AppointmentFilter.Operator.LTE;
            case "=" -> AppointmentFilter.Operator.EQ;
            case ">=" -> AppointmentFilter.Operator.GTE;
            case ">" -> AppointmentFilter.Operator.GT;
            default -> throw new BadRequestException("Неизвестный оператор готовности");
        };
    }

    private static AppointmentStatus status(String value) {
        return switch (value.replace('-', '_')) {
            case "предстоит", "upcoming" -> AppointmentStatus.UPCOMING;
            case "в_процессе", "in_progress" -> AppointmentStatus.IN_PROGRESS;
            case "отменена", "cancelled" -> AppointmentStatus.CANCELLED;
            case "завершена", "completed" -> AppointmentStatus.COMPLETED;

            default -> throw new BadRequestException("Неизвестный статус: " + value);
        };
    }

    private static PriorityLevel priority(String value) {
        return switch (value) {
            case "не_применяется", "неприменимо", "not_applicable", "n/a" -> PriorityLevel.NOT_APPLICABLE;
            case "низкий", "low" -> PriorityLevel.LOW;
            case "средний", "medium" -> PriorityLevel.MEDIUM;
            case "высокий", "high" -> PriorityLevel.HIGH;
            case "критический", "critical" -> PriorityLevel.CRITICAL;
            default -> throw new BadRequestException("Неизвестный приоритет: " + value);
        };
    }

    private static Boolean booleanValue(String value, String field) {
        return switch (value) {
            case "да", "yes", "true", "1" -> true;
            case "нет", "no", "false", "0" -> false;
            default -> throw new BadRequestException("Поле «" + field + "» принимает да/нет");
        };
    }

    private static Boolean archivedValue(String value) {
        if (value.equals("все") || value.equals("all")) return null;
        return booleanValue(value, "архив");
    }

    private static String stripQuotes(String value) {
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1) : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
