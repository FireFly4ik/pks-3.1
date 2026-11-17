ALTER TABLE users ADD COLUMN web_account_id uuid UNIQUE REFERENCES app_user(id);

-- Веб-пациент входит через app_user. Для профиля в старом клиенте
-- сохраняем случайный непредъявляемый секрет в совместимом формате.
CREATE FUNCTION provision_web_patient() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.role = 'PATIENT' THEN
        INSERT INTO users (login, full_name, password_hash, role, web_account_id)
        SELECT 'web-' || replace(gen_random_uuid()::text, '-', ''),
               CASE WHEN length(trim(display_name)) >= 2 THEN left(trim(display_name), 120) ELSE 'Пациент' END,
               encode(uuid_send(gen_random_uuid()), 'base64') || ':' ||
                   encode(sha256(uuid_send(gen_random_uuid())), 'base64'),
               'PATIENT', id
        FROM app_user WHERE id = NEW.user_id
        ON CONFLICT (web_account_id) DO NOTHING;
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER create_web_patient AFTER INSERT ON app_user_role
    FOR EACH ROW EXECUTE FUNCTION provision_web_patient();

INSERT INTO app_user_role(user_id, role)
SELECT u.id, 'PATIENT' FROM app_user u
WHERE NOT EXISTS (SELECT 1 FROM app_user_role r WHERE r.user_id = u.id);
