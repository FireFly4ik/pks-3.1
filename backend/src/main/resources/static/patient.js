async function openPatientPortal() {
    const portal = $('account-view');
    const owner = state.user.id;
    const stillHere = () => state.user?.id === owner && !portal.hidden;
    const form = $('patient-book');
    const specialty = $('patient-specialty'), date = $('patient-date'), doctor = $('patient-doctor');
    const alert = $('patient-alert');
    const confirmDialog = $('patient-confirm');
    const titles = {home: 'Обзор', booking: 'Записаться к врачу', visits: 'Мои записи', stats: 'Статистика'};
    const statuses = ['UPCOMING', 'IN_PROGRESS', 'COMPLETED', 'CANCELLED'];
    const today = new Intl.DateTimeFormat('sv-SE', {timeZone: 'Europe/Moscow'}).format(new Date());
    const dateLabel = (day, options) => new Date(day.slice(0, 10) + 'T12:00:00Z').toLocaleDateString('ru-RU', {...options, timeZone: 'UTC'});
    const when = value => dateLabel(value, {day:'numeric', month:'long', year:'numeric'}) + ', ' + value.slice(11,16);
    let visits = [], doctors = [], matchingSlots = new Map(), slot = '', moving = null, screen = 'home';
    let slotRequest = 0, listRequest = 0;

    function message(text = '', error = false) {
        if (!stillHere()) return;
        alert.textContent = text;
        alert.hidden = !text;
        alert.classList.toggle('error', error);
    }

    function show(name) {
        screen = name;
        portal.classList.remove('menu-open');
        $('patient-menu').setAttribute('aria-expanded', 'false');
        $('patient-title').textContent = name === 'booking' && moving ? 'Перенести приём' : titles[name];
        portal.querySelectorAll('[data-page]').forEach(section => section.hidden = section.dataset.page !== name);
        portal.querySelectorAll('[data-screen]').forEach(button => {
            if (button.dataset.screen === name) button.setAttribute('aria-current', 'page');
            else button.removeAttribute('aria-current');
        });
    }

    function visitCard(v) {
        const historical = ['CANCELLED','COMPLETED'].includes(v.status);
        let actions = '';
        if (v.status === 'UPCOMING') {
            actions = '<button class="secondary" data-action="move">Перенести</button><button class="danger-outline" data-action="cancel">Отменить</button>';
        } else if (historical) {
            actions = '<button class="text-action" data-action="hide">Убрать из истории</button>';
        }
        return `<article class="visit-card" data-id="${escapeHtml(v.id)}"><div>
            <time>${escapeHtml(when(v.startsAt))}</time><span class="badge" data-status="${escapeHtml(v.status)}">${escapeHtml(statusLabel(v.status))}</span>
            <h3>${escapeHtml(v.specialty)} · ${escapeHtml(v.doctor)}</h3><p>${escapeHtml(v.reason)}</p>
            ${v.rescheduleCount ? `<small>Переносов: ${v.rescheduleCount}</small>` : ''}</div>
            <div class="visit-actions">${actions}</div></article>`;
    }

    function filtered() {
        const query = $('patient-search').value.trim().toLocaleLowerCase('ru');
        const status = $('patient-status').value, from = $('patient-from').value, to = $('patient-to').value;
        const [field, direction] = $('patient-sort').value.split('-');
        const key = field === 'created' ? 'createdAt' : 'startsAt';
        return visits.filter(v => (!status || status === v.status) && (!from || v.startsAt.slice(0,10) >= from)
            && (!to || v.startsAt.slice(0,10) <= to) && (v.doctor + ' ' + v.specialty).toLocaleLowerCase('ru').includes(query))
            .sort((a,b) => (direction === 'asc' ? 1 : -1) * a[key].localeCompare(b[key]));
    }

    function renderList() {
        const rows = filtered();
        const invalidRange = $('patient-from').value && $('patient-to').value && $('patient-from').value > $('patient-to').value;
        $('patient-count').textContent = invalidRange ? 'Начало диапазона должно быть не позже его окончания.' : `Найдено записей: ${rows.length}`;
        $('patient-visits').innerHTML = rows.map(visitCard).join('') || '<p class="patient-empty">По этим условиям записей нет.</p>';
        $('patient-export').disabled = !rows.length;
    }

    async function refreshVisits() {
        const request = ++listRequest;
        const rows = await api('/patient/appointments');
        if (!stillHere() || request !== listRequest) return;
        visits = rows;
        const next = rows.filter(v => v.status === 'UPCOMING' || v.status === 'IN_PROGRESS').sort((a,b) => a.startsAt.localeCompare(b.startsAt))[0];
        $('patient-next').innerHTML = next
            ? `<strong>${escapeHtml(when(next.startsAt))}</strong><p>${escapeHtml(next.specialty)} · ${escapeHtml(next.doctor)}</p>`
            : '<strong>Пока не запланирован</strong><p>Выберите удобное время в расписании клиники.</p>';
        const day = next?.startsAt || today;
        $('patient-calendar').innerHTML = `<small>${dateLabel(day,{month:'long'})}</small><b>${day.slice(8,10)}</b>`;
        $('patient-recent').innerHTML = [...rows].sort((a,b) => b.createdAt.localeCompare(a.createdAt)).slice(0,3).map(visitCard).join('')
            || '<p class="patient-empty">Здесь появятся ваши записи к врачу.</p>';
        const metrics = [['Всего записей', rows.length], ...statuses.map(s => [statusLabel(s), rows.filter(v => v.status === s).length]),
            ['Перенесённых записей', rows.filter(v => v.rescheduleCount > 0).length], ['Всего переносов', rows.reduce((n,v) => n + v.rescheduleCount, 0)]];
        $('patient-metrics').innerHTML = metrics.map(([label,n]) => `<article class="metric-tile">${label}<strong>${n}</strong></article>`).join('');
        renderList();
    }

    function selection() {
        const selected = doctors.find(d => d.id === doctor.value);
        const entries = [['Специальность', specialty.value], ['Дата', date.value && dateLabel(date.value,{day:'numeric',month:'long'})],
            ['Время по Москве', slot.slice(11,16)], ['Врач', selected?.name]];
        $('patient-selection').innerHTML = entries.map(([key,val]) => `<dt>${key}</dt><dd>${escapeHtml(val || '—')}</dd>`).join('');
        form.querySelector('[type="submit"]').disabled = !slot || !selected;
    }

    async function loadSlots() {
        const request = ++slotRequest;
        slot = '';
        matchingSlots = new Map();
        doctor.replaceChildren(new Option('Сначала выберите время', ''));
        selection();
        const times = $('patient-times');
        if (!date.value || !date.validity.valid || !specialty.value) {
            times.textContent = 'Выберите специальность и дату в пределах ближайших 90 дней.';
            return;
        }
        times.textContent = 'Загрузка расписания…';
        try {
            const results = await Promise.all(doctors.filter(d => d.specialty === specialty.value).map(async d => {
                const params = new URLSearchParams({doctorId: d.id, date: date.value});
                if (moving) params.set('moving', moving.id);
                return [d, await api('/patient/slots?' + params)];
            }));
            if (!stillHere() || request !== slotRequest) return;
            for (const [d, slots] of results) {
                for (const value of slots) {
                    if (moving && moving.doctorId === d.id && moving.startsAt === value) continue;
                    if (!matchingSlots.has(value)) matchingSlots.set(value, []);
                    matchingSlots.get(value).push(d);
                }
            }
            times.replaceChildren();
            for (const value of [...matchingSlots.keys()].sort()) {
                const button = document.createElement('button');
                button.type = 'button';
                button.textContent = value.slice(11,16);
                button.setAttribute('aria-pressed', 'false');
                button.onclick = () => {
                    slot = value;
                    times.querySelectorAll('button').forEach(b => b.setAttribute('aria-pressed', String(b === button)));
                    doctor.replaceChildren(...matchingSlots.get(value).map(d => new Option(d.name, d.id)));
                    selection();
                };
                times.append(button);
            }
            if (!matchingSlots.size) times.textContent = 'Свободного времени нет. Выберите другой день.';
        } catch (error) {
            if (stillHere() && request === slotRequest) times.textContent = error.message;
        }
    }

    function booking(visit = null) {
        moving = visit;
        message();
        show('booking');
        const first = new Date(today + 'T00:00:00Z');
        while ([0,6].includes(first.getUTCDay())) first.setUTCDate(first.getUTCDate()+1);
        date.value = visit?.startsAt.slice(0,10) || first.toISOString().slice(0,10);
        if (visit && doctors.some(d => d.specialty === visit.specialty)) specialty.value = visit.specialty;
        $('patient-reason').value = visit?.reason || '';
        $('booking-title').textContent = visit ? 'Новое время приёма' : 'Выберите приём';
        form.querySelector('[type="submit"]').textContent = visit ? 'Подтвердить перенос' : 'Подтвердить запись';
        loadSlots();
    }

    function resetFilters() {
        for (const id of ['patient-search','patient-from','patient-to','patient-status']) $(id).value = '';
        $('patient-sort').selectedIndex = 0;
        renderList();
    }

    portal.onclick = async event => {
        const button = event.target.closest('button');
        if (!button) return;
        const name = button.dataset.screen || (button.hasAttribute('data-open-visits') ? 'visits' : null);
        if (button.hasAttribute('data-new-booking') || name === 'booking') return booking();
        if (name) {
            message();
            show(name);
            try { await refreshVisits(); } catch (error) { message(error.message, true); }
        }
        const action = button.dataset.action;
        if (!action) return;
        const visit = visits.find(v => v.id === button.closest('[data-id]').dataset.id);
        if (!visit) return;
        if (action === 'move') return booking(visit);
        const cancel = action === 'cancel';
        $('patient-confirm-title').textContent = cancel ? 'Отменить запись?' : 'Убрать запись из истории?';
        $('patient-confirm-detail').textContent = when(visit.startsAt) + ' · ' + visit.doctor;
        $('patient-confirm-ok').textContent = cancel ? 'Отменить запись' : 'Убрать';
        confirmDialog.returnValue = '';
        confirmDialog.onclose = async () => {
            if (confirmDialog.returnValue !== 'confirm' || !stillHere()) return;
            try {
                await api(`/patient/appointments/${visit.id}${cancel ? '/cancel' : ''}?version=${visit.version}`,
                    {method: cancel ? 'POST' : 'DELETE'});
                message(cancel ? 'Запись отменена.' : 'Запись убрана из вашей истории.');
            } catch (error) { message(error.message, true); }
            try { await refreshVisits(); } catch (error) { message(error.message, true); }
        };
        confirmDialog.showModal();
    };

    form.onsubmit = async event => {
        event.preventDefault();
        if (!slot || !doctor.value) return;
        const submit = form.querySelector('[type="submit"]');
        submit.disabled = true;
        const previous = moving;
        try {
            await api(previous ? `/patient/appointments/${previous.id}?version=${previous.version}` : '/patient/appointments', {
                method: previous ? 'PUT' : 'POST', body: JSON.stringify({doctorId: doctor.value, startsAt: slot, reason: $('patient-reason').value.trim()})
            });
            if (!stillHere()) return;
            moving = null;
            resetFilters();
            show('visits');
            message(previous ? 'Приём перенесён.' : 'Вы записаны на приём.');
            await refreshVisits();
        } catch (error) {
            message(error.message, true);
            if (stillHere() && screen === 'booking') await loadSlots();
        }
    };

    $('patient-export').onclick = () => {
        const csvCell = v => {
            const text = String(v ?? '');
            return '"' + (/^[=+@\-\t\r\n]/.test(text) ? "'" : '') + text.replaceAll('"','""') + '"';
        };
        const data = [['Дата и время (Москва)','Специальность','Врач','Статус','Причина обращения','Переносы'],
            ...filtered().map(v => [when(v.startsAt),v.specialty,v.doctor,statusLabel(v.status),v.reason,v.rescheduleCount])];
        const blob = new Blob(['\uFEFF',data.map(row => row.map(csvCell).join(';')).join('\r\n')], {type:'text/csv;charset=utf-8'});
        const a = document.createElement('a');
        a.href = URL.createObjectURL(blob); a.download = 'Мои записи.csv'; a.click();
        setTimeout(() => URL.revokeObjectURL(a.href), 1000);
    };
    $('patient-menu').onclick = () => {
        const opened = portal.classList.toggle('menu-open');
        $('patient-menu').setAttribute('aria-expanded', String(opened));
    };
    $('patient-filters').open = !matchMedia('(max-width: 760px)').matches;
    $('patient-reset').onclick = resetFilters;
    $('patient-password').onclick = () => openPasswordDialog(false);
    $('patient-status').replaceChildren(new Option('Все статусы',''), ...statuses.map(s => new Option(statusLabel(s),s)));
    for (const id of ['patient-search','patient-status','patient-from','patient-to','patient-sort']) $(id).oninput = renderList;
    specialty.onchange = date.onchange = loadSlots;
    doctor.onchange = selection;
    $('patient-name').textContent = state.user.displayName;
    $('account-email').textContent = state.user.email;
    $('patient-today').textContent = dateLabel(today,{weekday:'long',day:'numeric',month:'long'});
    date.min = today;
    const last = new Date(today+'T00:00:00Z');
    last.setUTCDate(last.getUTCDate()+90);
    date.max = last.toISOString().slice(0,10);
    specialty.replaceChildren();
    $('patient-recent').textContent = 'Загрузка записей…';
    $('patient-next').textContent = 'Загрузка…';
    $('patient-calendar').replaceChildren();
    resetFilters(); message(); show('home');
    try {
        const [result] = await Promise.all([api('/patient/doctors'), refreshVisits()]);
        if (!stillHere()) return;
        doctors = result;
        specialty.replaceChildren(...Array.from(new Set(doctors.map(d => d.specialty)), s => new Option(s,s)));
        if (screen === 'booking') await loadSlots();
    } catch (error) { message(error.message, true); }
}
