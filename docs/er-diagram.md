# ER-диаграмма

```mermaid
erDiagram
    USERS ||--o{ APPOINTMENTS : "пациент"
    DOCTORS ||--o{ APPOINTMENTS : "врач"
    USERS {
        bigint id PK
        varchar login UK
        varchar full_name
        text password_hash
        varchar role "PATIENT / ADMIN"
        boolean active
    }
    DOCTORS {
        bigint id PK
        varchar full_name
        varchar specialty
    }
    APPOINTMENTS {
        bigint id PK
        bigint patient_id FK
        bigint doctor_id FK
        timestamp starts_at "Москва"
        timestamp created_at "Москва"
        varchar status "UPCOMING / IN_PROGRESS / COMPLETED / CANCELLED"
        integer reschedule_count
    }
```

Все поля обязательны. `login` уникален. У врача уникальна пара «ФИО + специальность». Для записей действуют два частичных уникальных индекса: `(doctor_id, starts_at)` и `(patient_id, starts_at)` при `status <> 'CANCELLED'`. Отменённые записи остаются в истории, освобождая слот. FK запрещают удалять пользователя/врача со связанными записями.

`ADMIN` и `PATIENT` — роли учётной записи, а не разные таблицы. Врач — отдельная сущность справочника и в этой версии не входит в программу. `reschedule_count` позволяет учитывать переносы без дополнительного статуса.
