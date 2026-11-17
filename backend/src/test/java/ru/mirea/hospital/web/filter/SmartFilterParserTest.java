package ru.mirea.hospital.web.filter;

import org.junit.jupiter.api.Test;
import ru.mirea.hospital.web.domain.PriorityLevel;
import ru.mirea.hospital.web.domain.AppointmentStatus;
import ru.mirea.hospital.web.error.BadRequestException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Проверяет русские и английские команды умного фильтра. */
class SmartFilterParserTest {
    private final SmartFilterParser parser = new SmartFilterParser();

    @Test
    void parsesMixedSmartQuery() {
        AppointmentFilter result = parser.parse("Иванов врач:DOC-1 готовность<3,5 звонок:да приоритет:высокий тег:контроль");
        assertThat(result.freeText()).isEqualTo("Иванов");
        assertThat(result.doctor()).isEqualTo("DOC-1");
        assertThat(result.status()).isNull();
        assertThat(result.callback()).isTrue();
        assertThat(result.priority()).isEqualTo(PriorityLevel.HIGH);
        assertThat(result.tag()).isEqualTo("контроль");
        assertThat(result.archived()).isFalse();
        assertThat(result.scores()).hasSize(1);
    }

    @Test
    void supportsQuotedFreeTextAndStatus() {
        AppointmentFilter result = parser.parse("\"Иван Иванов\" status:in_progress archived:all");
        assertThat(result.freeText()).isEqualTo("Иван Иванов");
        assertThat(result.status()).isEqualTo(AppointmentStatus.IN_PROGRESS);
        assertThat(result.archived()).isNull();
    }

    @Test
    void rejectsOutOfRangeScore() {
        assertThatThrownBy(() -> parser.parse("готовность>101"))
                .isInstanceOf(BadRequestException.class);
    }
}
