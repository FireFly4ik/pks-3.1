'use strict';

/**
 * Access token хранится только в памяти вкладки. Refresh token браузер получает
 * в HttpOnly/SameSite cookie.
 */
const state = {
    accessToken: null,
    user: null,
    doctors: [],
    appointments: { page: 0, size: 25, totalPages: 0, query: '', content: [] },
    selectedAppointmentIds: new Set(),
    activeAppointmentForNotes: null,
    refreshPromise: null
};

const $ = (id) => document.getElementById(id);
const escapeHtml = (value) => String(value ?? '').replace(/[&<>'"]/g, ch => ({'&':'&amp;','<':'&lt;','>':'&gt;',"'":'&#39;','"':'&quot;'}[ch]));
const roles = () => new Set((state.user?.roles || []).map(String));
const isAdmin = () => roles().has('ADMIN');
const canEdit = () => roles().has('EDITOR') || isAdmin();


/**
 * Коды ролей в API остаются английскими; в интерфейсе используются русские названия.
 */
function roleLabel(value) {
    return ({
        PATIENT: 'Пациент',
        ADMIN: 'Администратор',
        EDITOR: 'Регистратор',
        VIEWER: 'Наблюдатель'
    })[value] || value;
}

/** Русские имена полей для ошибок Bean Validation. */
function fieldLabel(value) {
    return ({
        externalId: 'внешний ID',
        patientLogin: 'логин пациента',
        reason: 'причина обращения',
        details: 'комментарий',
        email: 'email',
        phone: 'телефон',
        startsAt: 'время приёма',
        doctorId: 'врач',
        status: 'статус',
        readiness: 'готовность',
        needsCall: 'нужен обратный звонок',
        tags: 'теги',
        version: 'версия'
    })[value] || value;
}

/** Приводит первую букву текста после двоеточия к нижнему регистру. */
function lowerFirst(value) {
    const text = String(value ?? '');
    return text ? text.charAt(0).toLocaleLowerCase('ru-RU') + text.slice(1) : text;
}

/**
 * Нормализует сообщения вида «поле: Ошибка» в «поле: ошибка».
 * Правило применяется и к сообщениям сервера.
 */
function normalizeAfterColon(value) {
    return String(value ?? '').replace(/:\s*([А-ЯЁA-Z])/g,
        (_, letter) => `: ${letter.toLocaleLowerCase('ru-RU')}`);
}

/**
 * Приводит ввод телефона к формату +7-999-999-99-99.
 * Если пользователь вставляет 8XXXXXXXXXX или 7XXXXXXXXXX, код страны
 * автоматически нормализуется к +7.
 */
function formatRussianPhone(value) {
    let digits = String(value ?? '').replace(/\D/g, '');

    if (digits.startsWith('8')) {
        digits = `7${digits.slice(1)}`;
    }

    if (digits.startsWith('7')) {
        digits = digits.slice(1);
    }

    digits = digits.slice(0, 10);

    let result = '+7-';
    if (!digits.length) return result;

    result += digits.slice(0, 3);
    if (digits.length > 3) result += `-${digits.slice(3, 6)}`;
    if (digits.length > 6) result += `-${digits.slice(6, 8)}`;
    if (digits.length > 8) result += `-${digits.slice(8, 10)}`;

    return result;
}

/**
 * Маска телефона:
 * - при фокусе сразу подставляет +7-;
 * - разрешает вводить только цифры и служебные клавиши;
 * - дефисы добавляет автоматически;
 * - вставленный текст очищает от букв и лишних знаков.
 */
function bindPhoneMask(input) {
    if (!input) return;

    input.addEventListener('focus', () => {
        if (!input.value) input.value = '+7-';
    });

    input.addEventListener('keydown', event => {
        const allowedKeys = new Set([
            'Backspace', 'Delete', 'ArrowLeft', 'ArrowRight',
            'Home', 'End', 'Tab', 'Enter'
        ]);

        if (event.ctrlKey || event.metaKey || allowedKeys.has(event.key) || /^\d$/.test(event.key)) {
            return;
        }

        event.preventDefault();
    });

    input.addEventListener('input', () => {
        input.value = formatRussianPhone(input.value);
    });

    input.addEventListener('blur', () => {
        if (input.value === '+7-') input.value = '';
    });
}

function toast(message, error = false) {
    const node = $('toast');
    node.textContent = message;
    node.className = `toast show${error ? ' error' : ''}`;
    clearTimeout(toast.timer);
    toast.timer = setTimeout(() => node.className = 'toast', 3500);
}


/**
 * Ошибки modal dialog выводятся внутри формы, а не через общий toast.
 */
function showFormError(form, message) {
    const box = form.querySelector('[data-form-error]');
    if (!box) {
        toast(message, true);
        return;
    }
    box.textContent = message;
    box.hidden = false;
}

/** Очищает локальное сообщение об ошибке перед новой отправкой формы. */
function clearFormError(form) {
    const box = form.querySelector('[data-form-error]');
    if (!box) return;
    box.textContent = '';
    box.hidden = true;
}

async function parseError(response) {
    try {
        const body = await response.json();
        const fields = body.validationErrors
            ? Object.entries(body.validationErrors)
                .map(([key, value]) => `${fieldLabel(key)}: ${lowerFirst(value)}`)
                .join('; ')
            : '';

        const message = `${body.message || response.statusText}${fields ? ` — ${fields}` : ''}${body.correlationId ? ` [${body.correlationId}]` : ''}`;
        return normalizeAfterColon(message);
    } catch (_) {
        return `${response.status} ${response.statusText}`;
    }
}

/**
 * Общая функция HTTP-вызова обрабатывает 401 и ограничивает повторный refresh.
 * Параллельные запросы используют один refreshPromise.
 */
async function api(path, options = {}, retry = true) {
    const headers = new Headers(options.headers || {});
    if (state.accessToken) headers.set('Authorization', `Bearer ${state.accessToken}`);
    if (options.body && !(options.body instanceof FormData) && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
    const response = await fetch(`/api${path}`, {...options, headers, credentials: 'include'});
    if (response.status === 401 && retry) {
        const refreshed = await refreshAccessToken();
        if (refreshed) return api(path, options, false);
    }
    if (!response.ok) throw new Error(await parseError(response));
    if (response.status === 204) return null;
    const type = response.headers.get('content-type') || '';
    return type.includes('application/json') ? response.json() : response;
}

async function refreshAccessToken() {
    if (!state.refreshPromise) {
        state.refreshPromise = fetch('/api/auth/refresh', {
            method: 'POST', credentials: 'include', headers: {'Content-Type':'application/json'}, body: '{}'
        }).then(async response => {
            if (!response.ok) return false;
            const result = await response.json();
            state.accessToken = result.accessToken;
            state.user = result.user;
            return true;
        }).finally(() => state.refreshPromise = null);
    }
    return state.refreshPromise;
}

async function boot() {
    bindEvents();
    if (await refreshAccessToken()) {
        await enterApplication();
    } else {
        showLogin();
    }
}

function showLogin() {
    $('account-view').hidden = true;
    $('register-form').hidden = true;
    $('login-form').hidden = false;
    $('app-view').hidden = true;
    $('login-view').hidden = false;
    state.accessToken = null;
    state.user = null;
}

async function enterApplication() {
    $('login-view').hidden = true;
    const patient = roles().has('PATIENT') && !canEdit() && !roles().has('VIEWER');
    $('account-view').hidden = !patient;
    $('app-view').hidden = patient;
    applyRoleVisibility();
    $('current-user').innerHTML = `<strong>${escapeHtml(state.user.displayName)}</strong><br>${escapeHtml(state.user.email)}<br>${escapeHtml(state.user.roles.map(roleLabel).join(', '))}`;

    if (state.user.passwordChangeRequired) {
        toast('Для продолжения необходимо сменить временный пароль', true);
        const changed = await openPasswordDialog(true);
        if (!changed) return;
    }

    if (patient) {
        await openPatientPortal();
        return;
    }

    /*
     * Ошибка загрузки аналитики или справочников не считается ошибкой входа.
     * Пользователь остаётся в приложении и получает отдельное уведомление.
     */
    try {
        await Promise.all([loadDoctors(), loadDashboard()]);
    } catch (error) {
        console.error('[Регистратура клиники] Initial data loading failed', error);
        toast(`Вход выполнен, но часть данных не загрузилась: ${error.message}`, true);
    }
}

async function openPatientPortal() {
    const form = $('patient-book');
    const specialty = $('patient-specialty');
    const doctor = $('patient-doctor');
    const date = $('patient-date');
    const time = $('patient-slot');
    const feedback = $('patient-feedback');
    const owner = state.user.id;
    const stillHere = () => state.user?.id === owner && !$('account-view').hidden;
    $('account-email').textContent = state.user.email;
    form.reset();
    doctor.replaceChildren();
    specialty.replaceChildren();
    time.replaceChildren();
    feedback.textContent = '';
    $('patient-visits').textContent = 'Загрузка…';
    const today = new Intl.DateTimeFormat('sv-SE', {timeZone: 'Europe/Moscow'}).format(new Date());
    date.min = today;
    const last = new Date(today + 'T00:00:00Z');
    last.setUTCDate(last.getUTCDate() + 90);
    date.max = last.toISOString().slice(0, 10);
    date.value = today;

    async function loadSlots() {
        const day = date.value;
        const id = doctor.value;
        time.replaceChildren(new Option('Выберите дату и врача', ''));
        if (!id || !day || !date.validity.valid) return;
        try {
            const slots = await api(`/patient/slots?doctorId=${encodeURIComponent(id)}&date=${encodeURIComponent(day)}`);
            if (!stillHere() || doctor.value !== id || date.value !== day) return;
            time.replaceChildren(new Option(slots.length ? 'Выберите время' : 'Свободного времени нет', ''),
                ...slots.map(slot => new Option(slot.slice(11, 16), slot)));
        } catch (error) { if (stillHere()) feedback.textContent = error.message; }
    }

    async function loadVisits() {
        const visits = await api('/patient/appointments');
        if (!stillHere()) return;
        if (!visits.length) {
            $('patient-visits').textContent = 'У вас пока нет записей.';
            return;
        }
        const list = document.createElement('ul');
        for (const visit of visits) {
            const item = document.createElement('li');
            item.textContent = `${formatDate(visit.startsAt)} · ${visit.specialty}, ${visit.doctor} · ${visit.reason} · ${statusLabel(visit.status)}`;
            list.append(item);
        }
        $('patient-visits').replaceChildren(list);
    }

    form.onsubmit = async event => {
        event.preventDefault();
        const submit = form.querySelector('[type="submit"]');
        submit.disabled = true;
        try {
            await api('/patient/appointments', {method: 'POST', body: JSON.stringify({
                doctorId: doctor.value, startsAt: time.value, reason: form.elements.reason.value.trim()
            })});
            if (!stillHere()) return;
            feedback.textContent = 'Вы записаны на приём.';
            form.elements.reason.value = '';
            await loadVisits();
        } catch (error) { if (stillHere()) feedback.textContent = error.message; }
        finally { submit.disabled = false; if (stillHere()) await loadSlots(); }
    };
    date.onchange = doctor.onchange = loadSlots;
    try {
        const [doctors] = await Promise.all([api('/patient/doctors'), loadVisits()]);
        if (!stillHere()) return;
        specialty.replaceChildren(...Array.from(new Set(doctors.map(d => d.specialty)), value => new Option(value, value)));
        specialty.onchange = () => {
            doctor.replaceChildren(...doctors.filter(d => d.specialty === specialty.value).map(d => new Option(d.name, d.id)));
            return loadSlots();
        };
        await specialty.onchange();
    } catch (error) { if (stillHere()) feedback.textContent = error.message; }
}

function applyRoleVisibility() {
    document.querySelectorAll('.admin-only').forEach(node => node.hidden = !isAdmin());
    document.querySelectorAll('.editor-only').forEach(node => node.hidden = !canEdit());
}

function switchTab(name) {
    document.querySelectorAll('.tab').forEach(tab => tab.classList.toggle('active', tab.id === `tab-${name}`));
    document.querySelectorAll('.nav-item').forEach(button => button.classList.toggle('active', button.dataset.tab === name));
    const titles = {
        dashboard: ['Аналитика','Сводная картина по приёмам'],
        appointments: ['Записи','Умный поиск, карточки и массовые операции'],
        doctors: ['Врачи','Единый справочник врачей'],
        users: ['Пользователи','Роли, блокировка и временные пароли'],
        audit: ['Журнал аудита','История действий пользователей']
    };
    [$('page-title').textContent, $('page-subtitle').textContent] = titles[name];
    if (name === 'appointments') loadAppointments();
    if (name === 'doctors') renderDoctors();
    if (name === 'users' && isAdmin()) loadUsers();
    if (name === 'audit' && isAdmin()) loadAudit();
    if (name === 'dashboard') loadDashboard();
}

async function loadDashboard() {
    try {
        const data = await api('/analytics/summary');
        const metrics = [
            ['Всего', data.total], ['Активные', data.active], ['В архиве', data.archived],
            ['Нужен звонок', data.withCallback], ['Готовность', data.readiness ?? '—']
        ];
        $('metric-grid').innerHTML = metrics.map(([name,value]) => `<article class="metric card"><span class="muted">${name}</span><strong>${value}</strong></article>`).join('');
        renderBars('chart-doctors', data.byDoctor);
        renderBars('chart-status', data.byStatus);
        renderBars('chart-priority', data.byPriority);
    } catch (error) { toast(error.message, true); }
}

function renderBars(id, values) {
    const max = Math.max(1, ...values.map(item => item.count));

    const displayName = value => {
        if (id === 'chart-status') return statusLabel(value);
        if (id === 'chart-priority') return priorityLabel(value);
        return value;
    };

    $(id).innerHTML = values.length
        ? values.map(item => `<div class="bar-row"><span>${escapeHtml(displayName(item.name))}</span><progress value="${item.count}" max="${max}"></progress><strong>${item.count}</strong></div>`).join('')
        : '<p class="muted">Нет данных</p>';
}

async function loadDoctors() {
    try { state.doctors = await api('/doctors?activeOnly=false'); renderDoctors(); }
    catch (error) { toast(error.message, true); }
}

function renderDoctors() {
    const body = $('doctors-body');
    if (!body) return;
    body.innerHTML = state.doctors.map(doctor => `<tr>
        <td><strong>${escapeHtml(doctor.code)}</strong></td><td>${escapeHtml(doctor.name || '—')}</td><td>${escapeHtml(doctor.specialty || '—')}</td>
        <td><span class="badge ${doctor.active ? 'success' : ''}">${doctor.active ? 'Да' : 'Нет'}</span></td>
        <td class="admin-only"><div class="actions"><button class="secondary" data-doctor-edit="${doctor.id}">Изменить</button><button class="secondary" data-doctor-toggle="${doctor.id}">${doctor.active ? 'Отключить' : 'Включить'}</button></div></td>
    </tr>`).join('');
    applyRoleVisibility();
}

async function loadAppointments(page = state.appointments.page) {
    try {
        const q = encodeURIComponent(state.appointments.query);
        const result = await api(`/appointments?q=${q}&page=${page}&size=${state.appointments.size}&sort=${encodeURIComponent($('visit-sort').value)}`);
        state.appointments = {...state.appointments, ...result};
        state.selectedAppointmentIds.clear();
        renderAppointments();
    } catch (error) { toast(error.message, true); }
}

function renderAppointments() {
    $('appointments-body').innerHTML = state.appointments.content.map(appointment => `<tr class="${appointment.archived ? 'archived' : ''}">
        <td class="editor-only"><input type="checkbox" data-appointment-select="${appointment.id}"></td>
        <td><strong>${escapeHtml(appointment.reason)}</strong><br><small class="muted">${escapeHtml(appointment.patientLogin)} · ${formatDate(appointment.startsAt)} · ${escapeHtml(appointment.externalId || '')}</small></td>
        <td>${escapeHtml(appointment.doctorCode || '—')}</td><td>${escapeHtml(statusLabel(appointment.status))}</td>
        <td>${appointment.readiness ?? '—'}</td><td>${appointment.needsCall ? '<span class="badge warning">Да</span>' : 'Нет'}</td>
        <td><span class="badge priority-${appointment.priorityLevel}">${escapeHtml(priorityLabel(appointment.priorityLevel))}</span></td>
        <td>${appointment.tags.map(tag => `<span class="badge">${escapeHtml(tag)}</span>`).join(' ') || '—'}</td>
        <td><div class="actions">
            <button class="secondary" data-appointment-view="${appointment.id}">Карточка</button>
            ${canEdit() ? `<button class="secondary" data-appointment-edit="${appointment.id}">Изменить</button><button class="secondary" data-appointment-notes="${appointment.id}">Заметки</button><button class="danger-outline" data-appointment-archive="${appointment.id}">${appointment.archived ? 'Восстановить' : 'В архив'}</button>` : ''}
            ${isAdmin() && appointment.archived ? `<button class="danger-outline" data-permanent="${appointment.id}">Удалить навсегда</button>` : ''}
        </div></td>
    </tr>`).join('') || '<tr><td colspan="9" class="muted">Записи не найдены</td></tr>';
    $('appointments-page').textContent = `Страница ${state.appointments.page + 1} из ${Math.max(1,state.appointments.totalPages)} · записей ${state.appointments.totalElements}`;
    $('appointments-prev').disabled = state.appointments.first;
    $('appointments-next').disabled = state.appointments.last;
    $('select-all-appointments').checked = false;
    applyRoleVisibility();
}

function appointmentForm(appointment = null, readOnly = false) {
    const doctorOptions = ['<option value="">Выберите врача</option>', ...state.doctors.filter(g => g.active || g.id === appointment?.doctorId).map(g => `<option value="${g.id}" ${g.id === appointment?.doctorId ? 'selected' : ''}>${escapeHtml(g.code + ' · ' + g.name + ' · ' + g.specialty)}</option>`)].join('');
    return `<div class="dialog-head"><h3>${appointment ? (readOnly ? 'Карточка приёма' : 'Изменение приёма') : 'Новый приём'}</h3><button type="button" data-close-dialog="entity-dialog" class="icon-button">×</button></div>
    <div class="form-grid">
      <label>Причина обращения<input name="reason" required maxlength="100" value="${escapeHtml(appointment?.reason || '')}" ${readOnly?'disabled':''}></label>
      <label>Логин пациента<input name="patientLogin" required maxlength="100" value="${escapeHtml(appointment?.patientLogin || '')}" ${readOnly?'disabled':''}></label>
      <label>Комментарий<input name="details" maxlength="100" value="${escapeHtml(appointment?.details || '')}" ${readOnly?'disabled':''}></label>
      <label>Код записи<input name="externalId" required maxlength="100" value="${escapeHtml(appointment?.externalId || '')}" ${readOnly?'disabled':''}></label>
      <label>Email<input name="email" type="email" maxlength="254" value="${escapeHtml(appointment?.email || '')}" ${readOnly?'disabled':''}></label>
      <label>Телефон<input name="phone" type="tel" inputmode="numeric" autocomplete="tel" maxlength="16" pattern="\\+7-[0-9]{3}-[0-9]{3}-[0-9]{2}-[0-9]{2}" title="Формат: +7-999-999-99-99" placeholder="+7-999-999-99-99" value="${escapeHtml(appointment?.phone ? formatRussianPhone(appointment.phone) : '')}" ${readOnly?'disabled':''}></label>
      <label>Дата и время приёма<input name="startsAt" required type="datetime-local" value="${appointment?.startsAt || ''}" ${readOnly?'disabled':''}></label>
      <label>Врач<select name="doctorId" required ${readOnly?'disabled':''}>${doctorOptions}</select></label>
      <label>Статус<select name="status" ${readOnly?'disabled':''}>${statusOptions(appointment?.status)}</select></label>
      <label>Готовность (0–100)<input name="readiness" type="number" min="0" max="100" step="0.01" inputmode="decimal" placeholder="Например, 68.50" value="${appointment?.readiness ?? ''}" ${readOnly?'disabled':''}></label>
      <label class="inline"><input name="needsCall" type="checkbox" ${appointment?.needsCall?'checked':''} ${readOnly?'disabled':''}> Нужен обратный звонок</label>
      <label class="span-2">Теги через запятую<input name="tags" maxlength="1000" value="${escapeHtml((appointment?.tags || []).join(', '))}" ${readOnly?'disabled':''}></label>
    </div>
    ${readOnly ? '' : '<div class="form-message error" data-form-error hidden></div><button class="primary" type="submit">Сохранить</button>'}`;
}

async function openAppointment(id = null, readOnly = false) {
    const appointment = id ? await api(`/appointments/${id}`) : null;
    const form = $('entity-form');
    form.innerHTML = appointmentForm(appointment, readOnly);

    const phoneInput = form.elements.phone;
    if (phoneInput && !readOnly) {
        bindPhoneMask(phoneInput);
    }

    form.onsubmit = readOnly ? null : async event => {
        event.preventDefault();
        clearFormError(form);

        /*
         * reportValidity() запускает нативную browser-валидацию до похода в backend.
         * Так пользователь сразу видит проблему в конкретном поле.
         */
        if (!form.reportValidity()) return;

        const data = new FormData(form);
        const payload = {
            externalId: empty(data.get('externalId')), patientLogin: data.get('patientLogin'), reason: data.get('reason'), details: empty(data.get('details')),
            email: empty(data.get('email')), phone: empty(data.get('phone')), startsAt: empty(data.get('startsAt')),
            doctorId: empty(data.get('doctorId')), status: data.get('status'), readiness: empty(data.get('readiness')) ? Number(data.get('readiness')) : null,
            needsCall: data.get('needsCall') === 'on', tags: String(data.get('tags') || '').split(',').map(v => v.trim()).filter(Boolean), version: appointment?.version ?? null
        };
        try {
            await api(id ? `/appointments/${id}` : '/appointments', {method: id ? 'PUT' : 'POST', body: JSON.stringify(payload)});
            $('entity-dialog').close(); toast('Карточка сохранена'); await Promise.all([loadAppointments(), loadDashboard()]);
        } catch (error) {
            showFormError(form, error.message);
        }
    };
    $('entity-dialog').showModal();
}

async function archiveAppointment(id) {
    const appointment = state.appointments.content.find(item => item.id === id);
    const action = appointment?.archived ? 'восстановить' : 'переместить в архив';
    if (!confirm(`Действительно ${action} карточку?`)) return;
    try {
        await api(appointment.archived ? `/appointments/${id}/restore` : `/appointments/${id}`, {method: appointment.archived ? 'POST' : 'DELETE'});
        toast('Операция выполнена'); await Promise.all([loadAppointments(), loadDashboard()]);
    } catch (error) { toast(error.message, true); }
}

async function openNotes(appointmentId) {
    state.activeAppointmentForNotes = appointmentId;
    $('note-form').reset();
    delete $('note-form').dataset.editId;
    $('note-form').querySelector('button').textContent = 'Добавить заметку';
    await renderNotes();
    $('notes-dialog').showModal();
}

async function renderNotes() {
    try {
        const list = await api(`/appointments/${state.activeAppointmentForNotes}/notes`);
        state.notes = list;
        $('notes-list').innerHTML = list.map(note => `<article class="note"><div class="note-meta"><span>${escapeHtml(note.authorName)} · ${formatDate(note.createdAt)}</span><span>${note.pinned ? '📌' : ''}</span></div><p>${escapeHtml(note.body).replace(/\n/g,'<br>')}</p><div class="actions"><button class="secondary" data-note-edit="${note.id}">Изменить</button><button class="danger-outline" data-note-delete="${note.id}">Удалить</button></div></article>`).join('') || '<p class="muted">Заметок пока нет</p>';
    } catch (error) { toast(error.message, true); }
}

async function bulkAction() {
    if (!state.selectedAppointmentIds.size) return toast('Выберите хотя бы один приём', true);
    const action = prompt('Действие: ARCHIVE, RESTORE, SET_STATUS, ADD_TAG, REMOVE_TAG', 'ARCHIVE');
    if (!action) return;
    const payload = {ids: [...state.selectedAppointmentIds], action: action.toUpperCase(), status: null, tag: null};
    if (payload.action === 'SET_STATUS') payload.status = prompt('Статус: UPCOMING, IN_PROGRESS, CANCELLED, COMPLETED', 'UPCOMING');
    if (payload.action === 'ADD_TAG' || payload.action === 'REMOVE_TAG') payload.tag = prompt('Тег');
    try {
        const result = await api('/appointments/bulk', {method:'POST', body:JSON.stringify(payload)});
        toast(`Успешно: ${result.succeeded}; ошибок: ${result.failures.length}`); await Promise.all([loadAppointments(), loadDashboard()]);
    } catch (error) { toast(error.message, true); }
}

async function exportCsv() {
    try {
        const response = await api(`/appointments/export?q=${encodeURIComponent(state.appointments.query)}`);
        const blob = await response.blob();
        const url = URL.createObjectURL(blob);
        const link = document.createElement('a'); link.href = url; link.download = `appointments-${new Date().toISOString().slice(0,10)}.csv`; link.click();
        URL.revokeObjectURL(url);
    } catch (error) { toast(error.message, true); }
}

function doctorForm(doctor = null) {
    return `<div class="dialog-head"><h3>${doctor?'Изменение данных врача':'Новый врач'}</h3><button type="button" data-close-dialog="entity-dialog" class="icon-button">×</button></div>
    <label>Код<input name="code" required maxlength="50" value="${escapeHtml(doctor?.code || '')}"></label>
    <label>ФИО врача<input required name="name" maxlength="120" value="${escapeHtml(doctor?.name || '')}"></label>
    <label>Специальность<input name="specialty" type="text" required maxlength="80" value="${escapeHtml(doctor?.specialty || '')}"></label>
    <label class="inline"><input name="active" type="checkbox" ${doctor?.active !== false?'checked':''}> Активен</label>
    <button class="primary">Сохранить</button>`;
}

async function openDoctor(id = null) {
    const doctor = id ? state.doctors.find(g => g.id === id) : null;
    const form = $('entity-form'); form.innerHTML = doctorForm(doctor);
    form.onsubmit = async event => {
        event.preventDefault(); const data = new FormData(form);
        const payload = {code:data.get('code'), name:empty(data.get('name')), specialty:data.get('specialty'), active:data.get('active')==='on', version:doctor?.version??null};
        try { await api(id?`/doctors/${id}`:'/doctors',{method:id?'PUT':'POST',body:JSON.stringify(payload)}); $('entity-dialog').close(); toast('Данные врача сохранены'); await loadDoctors(); }
        catch (error) { toast(error.message,true); }
    }; $('entity-dialog').showModal();
}

async function toggleDoctor(id) {
    const doctor = state.doctors.find(g => g.id === id);
    try { await api(`/doctors/${id}/active?active=${!doctor.active}&version=${doctor.version}`,{method:'PATCH'}); await loadDoctors(); }
    catch (error) { toast(error.message,true); }
}

async function loadUsers(number = 0) {
    try {
        const page = await api(`/admin/users?page=${number}&size=25&sort=email,asc`);
        $('users-prev').disabled = page.first;
        $('users-next').disabled = page.last;
        $('users-prev').onclick = () => loadUsers(page.page - 1);
        $('users-next').onclick = () => loadUsers(page.page + 1);
        $('users-page').textContent = `Страница ${page.page + 1} из ${Math.max(1, page.totalPages)}`;
        $('users-body').innerHTML = page.content.map(user => `<tr><td>${escapeHtml(user.email)}</td><td>${escapeHtml(user.displayName)}</td><td>${user.roles.map(r=>`<span class="badge">${escapeHtml(roleLabel(r))}</span>`).join(' ')}</td><td>${user.enabled?'Да':'Нет'}</td><td>${user.passwordChangeRequired?'Да':'Нет'}</td><td><div class="actions"><button class="secondary" data-user-edit="${user.id}">Изменить</button><button class="secondary" data-user-reset="${user.id}">Сбросить пароль</button></div></td></tr>`).join('');
        state.users = page.content;
    } catch (error) { toast(error.message,true); }
}

function userForm(user = null) {
    const checked = role => user?.roles.includes(role) || (!user && role==='VIEWER');
    return `<div class="dialog-head"><h3>${user?'Изменение':'Новый'} пользователь</h3><button type="button" data-close-dialog="entity-dialog" class="icon-button">×</button></div>
    <label>Email<input name="email" type="email" required maxlength="254" value="${escapeHtml(user?.email||'')}"></label>
    <label>Отображаемое имя<input name="displayName" required maxlength="160" value="${escapeHtml(user?.displayName||'')}"></label>
    ${user?'': '<label>Временный пароль<input name="temporaryPassword" type="password" required minlength="12" maxlength="128"></label>'}
    <fieldset><legend>Роли</legend>${['PATIENT','VIEWER','EDITOR','ADMIN'].map(r=>`<label class="inline"><input type="checkbox" name="roles" value="${r}" ${checked(r)?'checked':''}> ${escapeHtml(roleLabel(r))}</label>`).join('')}</fieldset>
    ${user?`<label class="inline"><input type="checkbox" name="enabled" ${user.enabled?'checked':''}> Активен</label>`:''}
    <button class="primary">Сохранить</button>`;
}

async function openUser(id = null) {
    const user = id ? state.users.find(u=>u.id===id) : null;
    const form=$('entity-form'); form.innerHTML=userForm(user);
    form.onsubmit=async event=>{event.preventDefault(); const data=new FormData(form); const payload={email:data.get('email'),displayName:data.get('displayName'),roles:data.getAll('roles'),...(user?{enabled:data.get('enabled')==='on',version:user.version}:{temporaryPassword:data.get('temporaryPassword')})};
        try{await api(id?`/admin/users/${id}`:'/admin/users',{method:id?'PUT':'POST',body:JSON.stringify(payload)});$('entity-dialog').close();toast('Пользователь сохранён');await loadUsers();}catch(error){toast(error.message,true);}};
    $('entity-dialog').showModal();
}

async function resetUserPassword(id) {
    const password=prompt('Новый временный пароль (не менее 12 символов, регистры, цифра и спецсимвол)'); if(!password)return;
    try{await api(`/admin/users/${id}/reset-password`,{method:'POST',body:JSON.stringify({temporaryPassword:password})});toast('Пароль сброшен');await loadUsers();}catch(error){toast(error.message,true);}
}

async function loadAudit(number = 0) {
    try {
        const q=encodeURIComponent($('audit-search').value.trim()); const page=await api(`/admin/audit?q=${q}&page=${number}&size=25&sort=occurred,desc`);
        $('audit-prev').disabled = page.first;
        $('audit-next').disabled = page.last;
        $('audit-prev').onclick = () => loadAudit(page.page - 1);
        $('audit-next').onclick = () => loadAudit(page.page + 1);
        $('audit-page').textContent = `Страница ${page.page + 1} из ${Math.max(1, page.totalPages)}`;
        $('audit-body').innerHTML=page.content.map(item=>`<tr><td>${formatDate(item.occurredAt)}</td><td>${escapeHtml(item.actorEmail||'system')}</td><td>${escapeHtml(item.eventType)}</td><td>${escapeHtml(item.entityType||'—')}</td><td>${escapeHtml(item.summary)}</td><td><code>${escapeHtml(item.correlationId||'—')}</code></td></tr>`).join('');
    } catch(error){toast(error.message,true);}
}

function openPasswordDialog(required=false) {
    return new Promise(resolve => {
        const dialog = $('entity-dialog');
        const form = $('entity-form');

        const currentPasswordField = required
            ? ''
            : '<label>Текущий пароль<input name="current" type="password" required maxlength="128" autocomplete="current-password"></label>';

        form.innerHTML = `<div class="dialog-head"><h3>${required ? 'Установка постоянного пароля' : 'Смена пароля'}</h3>${required ? '' : '<button type="button" data-close-dialog="entity-dialog" class="icon-button">×</button>'}</div>
            ${currentPasswordField}
            <label>Новый пароль<input name="next" type="password" required minlength="12" maxlength="128" autocomplete="new-password"></label>
            <label>Повторите новый пароль<input name="confirm" type="password" required minlength="12" maxlength="128" autocomplete="new-password"></label>
            <p class="muted">Минимум 12 символов: верхний/нижний регистр, цифра и специальный символ.</p>
            <button class="primary">${required ? 'Установить пароль' : 'Изменить'}</button>`;

        let completed = false;

        const onClose = () => {
            dialog.removeEventListener('close', onClose);
            if (!completed) resolve(false);
        };
        dialog.addEventListener('close', onClose);

        form.onsubmit = async event => {
            event.preventDefault();

            const data = new FormData(form);
            const next = String(data.get('next') || '');
            const confirm = String(data.get('confirm') || '');

            if (next !== confirm) {
                toast('Новые пароли не совпадают', true);
                return;
            }

            try {
                if (required) {
                    await api('/auth/change-initial-password', {
                        method: 'POST',
                        body: JSON.stringify({newPassword: next})
                    });

                    /*
                     * Access JWT был выпущен до смены пароля и ещё содержит
                     * passwordChangeRequired=true. Refresh-cookie ротируется, после чего
                     * клиент получает новый access JWT с актуальным значением из БД.
                     */
                    const refreshed = await refreshAccessToken();
                    if (!refreshed) {
                        completed = true;
                        dialog.close();
                        showLogin();
                        toast('Пароль изменён, но сессию не удалось обновить. Войдите новым паролем.', true);
                        resolve(false);
                        return;
                    }

                    state.user.passwordChangeRequired = false;
                    completed = true;
                    dialog.close();
                    toast('Постоянный пароль установлен');
                    resolve(true);
                    return;
                }

                await api('/auth/change-password', {
                    method: 'POST',
                    body: JSON.stringify({
                        currentPassword: data.get('current'),
                        newPassword: next
                    })
                });

                completed = true;
                dialog.close();
                toast('Пароль изменён. Выполните вход заново.');
                resolve(true);
                await logout();
            } catch (error) {
                toast(error.message, true);
            }
        };

        dialog.showModal();
    });
}

async function logout() {
    try { await fetch('/api/auth/logout',{method:'POST',credentials:'include',headers:{'Content-Type':'application/json'},body:'{}'}); } catch (_) {}
    showLogin();
}

function bindEvents() {
    const registration = $('register-form');
    $('open-register').onclick = () => {
        $('login-form').hidden = true;
        $('register-error').hidden = true;
        registration.hidden = false;
        registration.elements.displayName.focus();
    };
    $('back-to-login').onclick = () => {
        registration.reset();
        registration.hidden = true;
        $('login-form').hidden = false;
        $('login-email').focus();
    };
    $('account-logout').onclick = logout;
    registration.onsubmit = async event => {
        event.preventDefault();
        const button = registration.querySelector('[type="submit"]');
        const back = $('back-to-login');
        const error = $('register-error');
        const payload = Object.fromEntries(new FormData(registration));
        payload.email = payload.email.trim();
        button.disabled = back.disabled = true;
        error.hidden = true;
        try {
            const response = await fetch('/api/auth/register', {
                method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(payload)
            });
            if (!response.ok) throw new Error(await parseError(response));

            const session = await response.json();
            state.accessToken = session.accessToken;
            state.user = session.user;
            registration.reset();
            await enterApplication();
        } catch (failure) {
            error.textContent = failure.message;
            error.hidden = false;
        } finally {
            button.disabled = back.disabled = false;
        }
    };
    $('login-form').addEventListener('submit', async event => {
        event.preventDefault();

        const form = $('login-form');
        const button = form.querySelector('button[type="submit"]');
        const errorNode = $('login-error');

        errorNode.hidden = true;
        button.disabled = true;
        const originalCaption = button.textContent;
        button.textContent = 'Входим…';

        try {
            const response = await fetch('/api/auth/login', {
                method: 'POST',
                credentials: 'include',
                headers: {'Content-Type':'application/json'},
                body: JSON.stringify({
                    email: $('login-email').value,
                    password: $('login-password').value,
                    clientType: 'WEB'
                })
            });

            if (!response.ok) {
                throw new Error(await parseError(response));
            }

            const result = await response.json();
            state.accessToken = result.accessToken;
            state.user = result.user;
            $('login-password').value = '';

            await enterApplication();
        } catch (error) {
            /*
             * Если ошибка возникает после успешного HTTP login, экран входа снова
             * открывается и сообщение выводится в видимой форме.
             */
            console.error('[Регистратура клиники] Login flow failed', error);
            showLogin();
            errorNode.textContent = error.message || 'Не удалось выполнить вход';
            errorNode.hidden = false;
        } finally {
            button.disabled = false;
            button.textContent = originalCaption;
        }
    });
    $('navigation').addEventListener('click',event=>{const button=event.target.closest('[data-tab]');if(button&&!button.hidden)switchTab(button.dataset.tab);});
    $('logout').addEventListener('click',logout); $('change-password').addEventListener('click',()=>openPasswordDialog(false));
    $('visit-sort').addEventListener('change', () => loadAppointments(0));
    $('search-appointments').addEventListener('click',()=>{state.appointments.query=$('appointment-search').value.trim();state.appointments.page=0;loadAppointments(0);});
    $('appointment-search').addEventListener('keydown',event=>{if(event.key==='Enter')$('search-appointments').click();});
    $('appointments-prev').addEventListener('click',()=>loadAppointments(state.appointments.page-1)); $('appointments-next').addEventListener('click',()=>loadAppointments(state.appointments.page+1));
    $('add-appointment').addEventListener('click',()=>openAppointment()); $('bulk-action').addEventListener('click',bulkAction); $('export-csv').addEventListener('click',exportCsv);
    $('import-csv').addEventListener('click',()=>{$('csv-result').textContent='';$('csv-apply').disabled=true;$('csv-dialog').showModal();});
    $('add-doctor').addEventListener('click',()=>openDoctor()); $('add-user').addEventListener('click',()=>openUser()); $('search-audit').addEventListener('click',()=>loadAudit(0));
    $('appointments-body').addEventListener('click',event=>handleAppointmentTableClick(event));
    $('appointments-body').addEventListener('change',event=>{const checkbox=event.target.closest('[data-appointment-select]');if(checkbox){checkbox.checked?state.selectedAppointmentIds.add(checkbox.dataset.appointmentSelect):state.selectedAppointmentIds.delete(checkbox.dataset.appointmentSelect);}});
    $('select-all-appointments').addEventListener('change',event=>{document.querySelectorAll('[data-appointment-select]').forEach(cb=>{cb.checked=event.target.checked;event.target.checked?state.selectedAppointmentIds.add(cb.dataset.appointmentSelect):state.selectedAppointmentIds.delete(cb.dataset.appointmentSelect);});});
    $('doctors-body').addEventListener('click',event=>{const edit=event.target.closest('[data-doctor-edit]');const toggle=event.target.closest('[data-doctor-toggle]');if(edit)openDoctor(edit.dataset.doctorEdit);if(toggle)toggleDoctor(toggle.dataset.doctorToggle);});
    $('users-body').addEventListener('click',event=>{const edit=event.target.closest('[data-user-edit]');const reset=event.target.closest('[data-user-reset]');if(edit)openUser(edit.dataset.userEdit);if(reset)resetUserPassword(reset.dataset.userReset);});
    $('note-form').addEventListener('submit', async event => {
        event.preventDefault();
        const form = event.currentTarget;
        const note = state.notes.find(item => item.id === form.dataset.editId);
        const url = `/appointments/${state.activeAppointmentForNotes}/notes`;
        try {
            await api(note ? `${url}/${note.id}` : url, {method: note ? 'PUT' : 'POST', body: JSON.stringify({
                body: $('note-body').value, pinned: $('note-pinned').checked, version: note?.version
            })});
            form.reset();
            delete form.dataset.editId;
            form.querySelector('button').textContent = 'Добавить заметку';
            await renderNotes();
        } catch (error) { toast(error.message, true); }
    });
    $('notes-list').addEventListener('click', async event => {
        const edit = event.target.closest('[data-note-edit]');
        if (edit) {
            const note = state.notes.find(item => item.id === edit.dataset.noteEdit);
            $('note-body').value = note.body;
            $('note-pinned').checked = note.pinned;
            $('note-form').dataset.editId = note.id;
            $('note-form').querySelector('button').textContent = 'Сохранить изменения';
            return;
        }
        const button = event.target.closest('[data-note-delete]');
        if (button && confirm('Удалить заметку?')) {
            try {
                await api(`/appointments/${state.activeAppointmentForNotes}/notes/${button.dataset.noteDelete}`, {method: 'DELETE'});
                await renderNotes();
            } catch (error) { toast(error.message, true); }
        }
    });
    $('csv-preview').addEventListener('click',()=>runCsv(false)); $('csv-apply').addEventListener('click',()=>runCsv(true));
    document.body.addEventListener('click',event=>{const close=event.target.closest('[data-close-dialog]');if(close)$(close.dataset.closeDialog).close();});
}

async function handleAppointmentTableClick(event) {
    const permanent = event.target.closest('[data-permanent]');
    if (permanent && confirm('Безвозвратно удалить архивную запись со всеми заметками?')) {
        try {
            await api(`/appointments/${permanent.dataset.permanent}/permanent`, {method: 'DELETE', headers: {'X-Confirm-Delete': 'PERMANENTLY_DELETE'}});
            await Promise.all([loadAppointments(), loadDashboard()]);
        } catch (error) { toast(error.message, true); }
        return;
    }
    const view=event.target.closest('[data-appointment-view]'), edit=event.target.closest('[data-appointment-edit]'), notes=event.target.closest('[data-appointment-notes]'), archive=event.target.closest('[data-appointment-archive]');
    if(view)openAppointment(view.dataset.appointmentView,true); if(edit)openAppointment(edit.dataset.appointmentEdit,false); if(notes)openNotes(notes.dataset.appointmentNotes); if(archive)archiveAppointment(archive.dataset.appointmentArchive);
}

async function runCsv(apply) {
    const file=$('csv-file').files[0]; if(!file)return toast('Выберите CSV-файл',true);
    const form=new FormData();form.append('file',file);
    try{const result=await api(`/appointments/import/${apply?'apply':'preview'}?mode=${$('csv-mode').value}`,{method:'POST',body:form});$('csv-result').textContent=JSON.stringify(result,null,2);$('csv-apply').disabled=apply?true:result.invalidRows>0;if(apply){toast('Импорт завершён');await Promise.all([loadAppointments(),loadDashboard()]);}}catch(error){toast(error.message,true);$('csv-result').textContent=error.message;}
}

function statusOptions(selected='UPCOMING'){return ['UPCOMING','IN_PROGRESS','CANCELLED','COMPLETED'].map(v=>`<option value="${v}" ${v===selected?'selected':''}>${statusLabel(v)}</option>`).join('');}
function statusLabel(value){return ({UPCOMING:'Предстоит',IN_PROGRESS:'В процессе',CANCELLED:'Отменена',COMPLETED:'Завершена'})[value]||value;}
function priorityLabel(value){return ({NOT_APPLICABLE:'Не применяется',LOW:'Низкий',MEDIUM:'Средний',HIGH:'Высокий',CRITICAL:'Критический'})[value]||value;}
function formatDate(value){return value?new Intl.DateTimeFormat('ru-RU',{dateStyle:'short',timeStyle:'short'}).format(new Date(value)):'—';}
function empty(value){const normalized=String(value??'').trim();return normalized||null;}

boot().catch(error => {
    console.error('[Регистратура клиники] Boot failed', error);
    showLogin();
    toast(error.message || 'Не удалось инициализировать приложение', true);
});
