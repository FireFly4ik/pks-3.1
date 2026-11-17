CREATE TABLE app_user (
                          id UUID PRIMARY KEY,
                          email VARCHAR(254) NOT NULL UNIQUE,
                          display_name VARCHAR(160) NOT NULL,
                          password_hash VARCHAR(100) NOT NULL,
                          enabled BOOLEAN NOT NULL DEFAULT TRUE,
                          password_change_required BOOLEAN NOT NULL DEFAULT TRUE,
                          failed_login_attempts INTEGER NOT NULL DEFAULT 0,
                          locked_until TIMESTAMPTZ,
                          created_at TIMESTAMPTZ NOT NULL,
                          updated_at TIMESTAMPTZ NOT NULL,
                          version BIGINT NOT NULL DEFAULT 0
);

CREATE TABLE app_user_role (
                               user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
                               role VARCHAR(32) NOT NULL,
                               PRIMARY KEY (user_id, role)
);

CREATE TABLE refresh_session (
                                 id UUID PRIMARY KEY,
                                 user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
                                 token_hash VARCHAR(64) NOT NULL UNIQUE,
                                 expires_at TIMESTAMPTZ NOT NULL,
                                 revoked_at TIMESTAMPTZ,
                                 replaced_by UUID,
                                 user_agent VARCHAR(500),
                                 ip_address VARCHAR(64),
                                 created_at TIMESTAMPTZ NOT NULL,
                                 last_used_at TIMESTAMPTZ
);

CREATE INDEX idx_refresh_user
    ON refresh_session(user_id);

CREATE INDEX idx_refresh_expires
    ON refresh_session(expires_at);



ALTER TABLE doctors
    ADD COLUMN public_id uuid NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    ADD COLUMN code varchar(50),
    ADD COLUMN IF NOT EXISTS active boolean NOT NULL DEFAULT true,
    ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN version bigint NOT NULL DEFAULT 0;
UPDATE doctors SET code = 'DOC-' || id;
ALTER TABLE doctors ALTER COLUMN code SET NOT NULL, ADD UNIQUE(code);
CREATE FUNCTION assign_doctor_code() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.code := COALESCE(NEW.code, 'DOC-' || NEW.id);
    RETURN NEW;
END $$;
CREATE TRIGGER assign_code BEFORE INSERT ON doctors FOR EACH ROW EXECUTE FUNCTION assign_doctor_code();

ALTER TABLE appointments
    ADD COLUMN public_id uuid NOT NULL DEFAULT gen_random_uuid() UNIQUE,
    ADD COLUMN external_id varchar(100),
    ADD COLUMN doctor_uuid uuid REFERENCES doctors(public_id),
    ADD COLUMN patient_login varchar(100),
    ADD COLUMN reason varchar(100) NOT NULL DEFAULT 'Консультация',
    ADD COLUMN details varchar(100),
    ADD COLUMN email varchar(254),
    ADD COLUMN phone varchar(32),
    ADD COLUMN readiness numeric(5,2) CHECK(readiness BETWEEN 0 AND 100),
    ADD COLUMN needs_call boolean NOT NULL DEFAULT false,
    ADD COLUMN priority_level varchar(32) NOT NULL DEFAULT 'MEDIUM',
    ADD COLUMN archived boolean NOT NULL DEFAULT false,
    ADD COLUMN archived_at timestamptz,
    ADD COLUMN archived_by uuid REFERENCES app_user(id),
    ADD COLUMN created_by uuid REFERENCES app_user(id),
    ADD COLUMN updated_by uuid REFERENCES app_user(id),
    ADD COLUMN web_created_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN version bigint NOT NULL DEFAULT 0;
UPDATE appointments a SET external_id = 'VISIT-' || a.id, doctor_uuid = d.public_id,
    patient_login = u.login, web_created_at = a.created_at AT TIME ZONE 'Europe/Moscow',
    priority_level = CASE WHEN a.status IN ('COMPLETED','CANCELLED') THEN 'NOT_APPLICABLE' ELSE 'MEDIUM' END
FROM doctors d, users u WHERE a.doctor_id=d.id AND a.patient_id=u.id;
ALTER TABLE appointments ALTER COLUMN doctor_uuid SET NOT NULL,
    ALTER COLUMN patient_login SET NOT NULL, ALTER COLUMN external_id SET NOT NULL;
CREATE UNIQUE INDEX appointment_external_code ON appointments(lower(external_id));
CREATE INDEX appointment_web_filter ON appointments(archived,status,doctor_uuid);
CREATE INDEX appointment_reason ON appointments(lower(reason));

-- JavaFX передаёт числовые внешние ключи; веб-клиент использует UUID врача и логин пациента.
CREATE FUNCTION link_visit() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'INSERT' OR NEW.doctor_id IS DISTINCT FROM OLD.doctor_id OR NEW.doctor_uuid IS DISTINCT FROM OLD.doctor_uuid THEN
        IF NEW.doctor_uuid IS NULL OR (TG_OP='UPDATE' AND NEW.doctor_id IS DISTINCT FROM OLD.doctor_id) THEN
            SELECT public_id INTO NEW.doctor_uuid FROM doctors WHERE id=NEW.doctor_id AND active;
        ELSE
            SELECT id INTO NEW.doctor_id FROM doctors WHERE public_id=NEW.doctor_uuid AND active;
        END IF;
    END IF;
    IF TG_OP = 'INSERT' OR NEW.patient_id IS DISTINCT FROM OLD.patient_id OR NEW.patient_login IS DISTINCT FROM OLD.patient_login THEN
        IF NEW.patient_login IS NULL OR (TG_OP='UPDATE' AND NEW.patient_id IS DISTINCT FROM OLD.patient_id) THEN
            SELECT login INTO NEW.patient_login FROM users WHERE id=NEW.patient_id;
        ELSE
            SELECT id INTO NEW.patient_id FROM users WHERE login=NEW.patient_login AND active;
        END IF;
    END IF;
    NEW.external_id := COALESCE(NEW.external_id, 'VISIT-' || NEW.id);
    IF TG_OP='UPDATE' AND NEW IS DISTINCT FROM OLD THEN
        NEW.version := OLD.version + 1;
        NEW.updated_at := now();
        IF (NEW.starts_at, NEW.doctor_id) IS DISTINCT FROM (OLD.starts_at, OLD.doctor_id) THEN
            NEW.reschedule_count := OLD.reschedule_count + 1;
        END IF;
    END IF;
    NEW.priority_level := CASE WHEN NEW.status IN ('CANCELLED','COMPLETED') THEN 'NOT_APPLICABLE'
        WHEN NEW.needs_call AND (NEW.readiness IS NULL OR NEW.readiness<60) THEN 'CRITICAL'
        WHEN NEW.needs_call OR NEW.readiness<60 THEN 'HIGH'
        WHEN NEW.readiness IS NULL OR NEW.readiness<75 THEN 'MEDIUM' ELSE 'LOW' END;
    RETURN NEW;
END $$;
CREATE TRIGGER link_visit_keys BEFORE INSERT OR UPDATE ON appointments FOR EACH ROW EXECUTE FUNCTION link_visit();
CREATE TABLE appointment_tag (
                             appointment_id UUID NOT NULL REFERENCES appointments(public_id) ON DELETE CASCADE,
                             tag VARCHAR(50) NOT NULL,
                             PRIMARY KEY (appointment_id, tag)
);

CREATE INDEX idx_appointment_tag_value
    ON appointment_tag(tag);


CREATE TABLE appointment_note (
                              id UUID PRIMARY KEY,
                              appointment_id UUID NOT NULL REFERENCES appointments(public_id) ON DELETE CASCADE,
                              author_id UUID NOT NULL REFERENCES app_user(id),
                              body VARCHAR(4000) NOT NULL,
                              pinned BOOLEAN NOT NULL DEFAULT FALSE,
                              created_at TIMESTAMPTZ NOT NULL,
                              updated_at TIMESTAMPTZ NOT NULL,
                              version BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_note_appointment
    ON appointment_note(appointment_id, pinned DESC, created_at DESC);


CREATE TABLE audit_event (
                             id UUID PRIMARY KEY,
                             actor_id UUID,
                             actor_email VARCHAR(254),
                             event_type VARCHAR(80) NOT NULL,
                             entity_type VARCHAR(80),
                             entity_id UUID,
                             summary VARCHAR(1000) NOT NULL,
                             correlation_id VARCHAR(100),
                             ip_address VARCHAR(64),
                             user_agent VARCHAR(500),
                             occurred_at TIMESTAMPTZ NOT NULL,
                             details_json TEXT
);

CREATE INDEX idx_audit_occurred
    ON audit_event(occurred_at DESC);

CREATE INDEX idx_audit_actor
    ON audit_event(actor_email);

CREATE INDEX idx_audit_entity
    ON audit_event(entity_type, entity_id);
