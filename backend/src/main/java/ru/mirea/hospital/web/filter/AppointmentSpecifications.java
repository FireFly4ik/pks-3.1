package ru.mirea.hospital.web.filter;

import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;
import ru.mirea.hospital.web.domain.Appointment;
import ru.mirea.hospital.web.domain.Doctor;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Создаёт параметризованную JPA Specification из разобранного фильтра. */
public final class AppointmentSpecifications {
    private AppointmentSpecifications() { }

    public static Specification<Appointment> from(AppointmentFilter filter) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();


            if (filter.archived() != null) predicates.add(cb.equal(root.get("archived"), filter.archived()));
            var now = ru.mirea.hospital.web.service.AppointmentStatusPolicy.now();
            var stored = root.<ru.mirea.hospital.web.domain.AppointmentStatus>get("status");
            var starts = root.<java.time.LocalDateTime>get("startsAt");
            var cancelled = ru.mirea.hospital.web.domain.AppointmentStatus.CANCELLED;
            var completed = ru.mirea.hospital.web.domain.AppointmentStatus.COMPLETED;
            var active = cb.and(cb.notEqual(stored, cancelled), cb.notEqual(stored, completed));
            var finished = cb.or(cb.equal(stored, completed), cb.and(active, cb.lessThanOrEqualTo(starts, now.minusMinutes(30))));
            var terminal = cb.or(cb.equal(stored, cancelled), finished);
            if (filter.status() != null) predicates.add(switch (filter.status()) {
                case CANCELLED -> cb.equal(stored, cancelled);
                case COMPLETED -> finished;
                case UPCOMING -> cb.and(active, cb.or(cb.greaterThan(starts, now), cb.and(cb.isNull(starts), cb.equal(stored, filter.status()))));
                case IN_PROGRESS -> cb.and(active, cb.or(cb.and(cb.lessThanOrEqualTo(starts, now), cb.greaterThan(starts, now.minusMinutes(30))), cb.and(cb.isNull(starts), cb.equal(stored, filter.status()))));
            });
            if (filter.callback() != null) predicates.add(cb.equal(root.get("needsCall"), filter.callback()));
            if (filter.priority() != null) {
                var readiness = root.<java.math.BigDecimal>get("readiness");
                var call = cb.isTrue(root.<Boolean>get("needsCall"));
                var lowScore = cb.lessThan(readiness, new java.math.BigDecimal("60"));
                var unknown = cb.isNull(readiness);
                var below75 = cb.lessThan(readiness, new java.math.BigDecimal("75"));
                var critical = cb.and(call, cb.or(unknown, lowScore));
                var high = cb.or(cb.and(call, cb.greaterThanOrEqualTo(readiness, new java.math.BigDecimal("60"))), cb.and(cb.not(call), lowScore));
                var medium = cb.and(cb.not(call), cb.or(unknown, cb.and(cb.greaterThanOrEqualTo(readiness, new java.math.BigDecimal("60")), below75)));
                var low = cb.and(cb.not(call), cb.greaterThanOrEqualTo(readiness, new java.math.BigDecimal("75")));
                predicates.add(switch (filter.priority()) {
                    case NOT_APPLICABLE -> terminal;
                    case CRITICAL -> cb.and(cb.notEqual(stored, cancelled), active, cb.or(cb.isNull(starts), cb.greaterThan(starts, now.minusMinutes(30))), critical);
                    case HIGH -> cb.and(active, cb.or(cb.isNull(starts), cb.greaterThan(starts, now.minusMinutes(30))), high);
                    case MEDIUM -> cb.and(active, cb.or(cb.isNull(starts), cb.greaterThan(starts, now.minusMinutes(30))), medium);
                    case LOW -> cb.and(active, cb.or(cb.isNull(starts), cb.greaterThan(starts, now.minusMinutes(30))), low);
                });
            }

            Join<Appointment, Doctor> doctorJoin = root.join("doctor", JoinType.LEFT);
            if (filter.doctor() != null) {
                predicates.add(cb.equal(cb.lower(doctorJoin.get("code")), filter.doctor().toLowerCase(Locale.ROOT)));
            }
            if (filter.tag() != null) {
                Join<Appointment, String> tagJoin = root.joinSet("tags", JoinType.LEFT);
                predicates.add(cb.equal(cb.lower(tagJoin), filter.tag().toLowerCase(Locale.ROOT)));
            }
            if (filter.freeText() != null && !filter.freeText().isBlank()) {
                String like = "%" + escape(filter.freeText()) + "%";
                predicates.add(cb.or(
                        cb.like(cb.lower(root.get("reason")), like, '\\'),
                        cb.like(cb.lower(root.get("patientLogin")), like, '\\'),
                        cb.like(cb.lower(root.get("details")), like, '\\'),
                        cb.like(cb.lower(doctorJoin.get("name")), like, '\\'),
                        cb.like(cb.lower(doctorJoin.get("specialty")), like, '\\'),
                        cb.like(cb.lower(root.get("email")), like, '\\'),
                        cb.like(cb.lower(root.get("externalId")), like, '\\'),
                        cb.like(cb.lower(doctorJoin.get("code")), like, '\\')));
            }
            filter.scores().forEach(constraint -> predicates.add(switch (constraint.operator()) {
                case LT -> cb.lessThan(root.get("readiness"), constraint.value());
                case LTE -> cb.lessThanOrEqualTo(root.get("readiness"), constraint.value());
                case EQ -> cb.equal(root.get("readiness"), constraint.value());
                case GTE -> cb.greaterThanOrEqualTo(root.get("readiness"), constraint.value());
                case GT -> cb.greaterThan(root.get("readiness"), constraint.value());
            }));
            return cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static String escape(String value) {
        return value.toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
