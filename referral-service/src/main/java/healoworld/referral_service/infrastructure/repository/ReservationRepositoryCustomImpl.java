package healoworld.referral_service.infrastructure.repository;

import com.querydsl.core.types.Order;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.PathBuilder;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import healoworld.referral_service.domain.entity.Reservation;
import healoworld.referral_service.domain.enums.FailureReason;
import healoworld.referral_service.domain.enums.ReservationStatus;
import healoworld.referral_service.domain.enums.RoleType;
import healoworld.referral_service.presentation.dto.request.ReservationSearchCondition;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.support.PageableExecutionUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static healoworld.referral_service.domain.entity.QReservation.reservation;

@RequiredArgsConstructor
public class ReservationRepositoryCustomImpl implements ReservationRepositoryCustom {
    private final JPAQueryFactory queryFactory;

    @Override
    public Page<Reservation> search(ReservationSearchCondition cond, UUID userId, RoleType role, Pageable pageable) {
        BooleanExpression[] conditions = {
                accessibleBy(userId, role, cond.referrerId()),
                statusEq(cond.status()),
                failureReasonEq(cond.failureReason()),
                referralIdEq(cond.referralId()),
                examInstitutionIdEq(cond.examInstitutionId()),
                equipmentIdEq(cond.equipmentId()),
                examDateGoe(cond.examDateFrom()),
                examDateLoe(cond.examDateTo())
        };

        List<Reservation> content = queryFactory
                .selectFrom(reservation)
                .where(conditions)
                .orderBy(toOrderSpecifiers(pageable.getSort()))
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        JPAQuery<Long> countQuery = queryFactory
                .select(reservation.count())
                .from(reservation)
                .where(conditions);

        return PageableExecutionUtils.getPage(content, pageable, countQuery::fetchOne);
    }

    // 관리자가 아니면 본인 의뢰만 조회
    private BooleanExpression accessibleBy(UUID userId, RoleType role, UUID referrerId) {
        if (role != RoleType.ADMIN) {
            return reservation.referral.referrerId.eq(userId);
        }
        return referrerId != null ? reservation.referral.referrerId.eq(referrerId) : null;
    }

    private BooleanExpression statusEq(ReservationStatus status) {
        return status != null ? reservation.reservationStatus.eq(status) : null;
    }

    private BooleanExpression equipmentIdEq(UUID equipmentId) {
        return equipmentId != null ? reservation.equipmentId.eq(equipmentId) : null;
    }

    private BooleanExpression examInstitutionIdEq(UUID examInstitutionId) {
        return examInstitutionId != null ? reservation.examInstitutionId.eq(examInstitutionId) : null;
    }

    private BooleanExpression referralIdEq(UUID referralId) {
        return referralId != null ? reservation.referral.id.eq(referralId) : null;
    }

    private BooleanExpression failureReasonEq(FailureReason failureReason) {
        return failureReason != null ? reservation.failureReason.eq(failureReason) : null;
    }

    private BooleanExpression examDateGoe(LocalDateTime from) {
        return from != null ? reservation.examDate.goe(from) : null;
    }

    private BooleanExpression examDateLoe(LocalDateTime to) {
        return to != null ? reservation.examDate.loe(to) : null;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private OrderSpecifier<?>[] toOrderSpecifiers(Sort sort) {
        PathBuilder<Reservation> path = new PathBuilder<>(Reservation.class, reservation.getMetadata());
        return sort.stream()
                .map(order -> new OrderSpecifier(
                        order.isAscending() ? Order.ASC : Order.DESC,
                        path.getComparable(order.getProperty(), Comparable.class)))
                .toArray(OrderSpecifier[]::new);
    }
}
