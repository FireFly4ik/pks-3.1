-- Fill missing contact email without changing patient identity.
UPDATE appointments a SET email = au.email
FROM users u JOIN app_user au ON au.id = u.web_account_id
WHERE a.patient_login = u.login AND (a.email IS NULL OR trim(a.email) = '');
