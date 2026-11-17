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
            if (filter.status() != null) predicates.add(cb.equal(root.get("status"), filter.status()));
            if (filter.callback() != null) predicates.add(cb.equal(root.get("needsCall"), filter.callback()));
            if (filter.priority() != null) predicates.add(cb.equal(root.get("priorityLevel"), filter.priority()));

            Join<Appointment, Doctor> doctorJoin = root.join("doctor", JoinType.LEFT);
            if (filter.doctor() != null) {
                predicates.add(cb.like(cb.lower(doctorJoin.get("code")), "%" + escape(filter.doctor()) + "%", '\\'));
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
