-- PostgreSQL. Приложение также выполняет этот скрипт при запуске.
CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL PRIMARY KEY,
    login VARCHAR(40) NOT NULL UNIQUE CHECK (login ~ '^[a-z0-9_.-]{3,40}$'),
    full_name VARCHAR(120) NOT NULL CHECK (length(trim(full_name)) >= 2),
    password_hash TEXT NOT NULL,
    role VARCHAR(16) NOT NULL CHECK (role IN ('PATIENT', 'ADMIN')),
    active BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE TABLE IF NOT EXISTS doctors (
    id BIGSERIAL PRIMARY KEY,
    full_name VARCHAR(120) NOT NULL,
    specialty VARCHAR(80) NOT NULL,
    UNIQUE (full_name, specialty)
);
CREATE TABLE IF NOT EXISTS appointments (
    id BIGSERIAL PRIMARY KEY,
    patient_id BIGINT NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    doctor_id BIGINT NOT NULL REFERENCES doctors(id) ON DELETE RESTRICT,
    starts_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT LOCALTIMESTAMP,
    status VARCHAR(20) NOT NULL CHECK (status IN ('IN_PROGRESS', 'UPCOMING', 'COMPLETED', 'CANCELLED')),
    reschedule_count INTEGER NOT NULL DEFAULT 0 CHECK (reschedule_count >= 0),
    CHECK (EXTRACT(ISODOW FROM starts_at) BETWEEN 1 AND 5),
    CHECK (starts_at::time >= TIME '09:00' AND starts_at::time < TIME '17:00'),
    CHECK (EXTRACT(MINUTE FROM starts_at) IN (0, 30) AND EXTRACT(SECOND FROM starts_at) = 0),
    CHECK (created_at < starts_at)
);
CREATE UNIQUE INDEX IF NOT EXISTS doctor_slot_unique ON appointments(doctor_id, starts_at) WHERE status <> 'CANCELLED';
CREATE UNIQUE INDEX IF NOT EXISTS patient_slot_unique ON appointments(patient_id, starts_at) WHERE status <> 'CANCELLED';
CREATE INDEX IF NOT EXISTS appointments_patient_idx ON appointments(patient_id);
INSERT INTO doctors(id, full_name, specialty) VALUES
    (1, 'Иванова Елена Сергеевна', 'Терапевт'),
    (2, 'Петров Алексей Николаевич', 'Терапевт'),
    (3, 'Соколова Ольга Андреевна', 'Кардиолог'),
    (4, 'Смирнов Дмитрий Павлович', 'Невролог')
ON CONFLICT DO NOTHING;
SELECT setval(pg_get_serial_sequence('doctors', 'id'), GREATEST((SELECT MAX(id) FROM doctors), 1));
