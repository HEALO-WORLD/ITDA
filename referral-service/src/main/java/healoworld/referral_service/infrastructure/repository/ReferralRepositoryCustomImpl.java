package healoworld.referral_service.infrastructure.repository;

import com.querydsl.core.types.Order;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.PathBuilder;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import healoworld.referral_service.domain.entity.Referral;
import healoworld.referral_service.domain.enums.ReferralStatus;
import healoworld.referral_service.domain.enums.RoleType;
import healoworld.referral_service.presentation.dto.request.ReferralSearchCondition;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.support.PageableExecutionUtils;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.UUID;

import static healoworld.referral_service.domain.entity.QReferral.referral;

@RequiredArgsConstructor
public class ReferralRepositoryCustomImpl implements ReferralRepositoryCustom {
    private final JPAQueryFactory queryFactory;

    @Override
    public Page<Referral> search(ReferralSearchCondition cond, UUID institutionId, RoleType role, Pageable pageable) {
        BooleanExpression[] conditions = {
                institutionScope(institutionId, role),
                referrerIdEq(cond.referrerId()),
                statusEq(cond.status()),
                referringInstitutionIdEq(cond.referringInstitutionId()),
                examTypeIdEq(cond.examTypeId()),
                patientNameContains(cond.patientName())
        };

        List<Referral> content = queryFactory
                .selectFrom(referral)
                .where(conditions)
                .orderBy(toOrderSpecifiers(pageable.getSort()))
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        JPAQuery<Long> countQuery = queryFactory
                .select(referral.count())
                .from(referral)
                .where(conditions);

        return PageableExecutionUtils.getPage(content, pageable, countQuery::fetchOne);
    }

    // 관리자가 아니면 본인 기관 의뢰만 조회
    private BooleanExpression institutionScope(UUID institutionId, RoleType role) {
        return role == RoleType.ADMIN ? null : referral.referringInstitutionId.eq(institutionId);
    }

    private BooleanExpression referrerIdEq(UUID referrerId) {
        return referrerId != null ? referral.referrerId.eq(referrerId) : null;
    }

    private BooleanExpression statusEq(ReferralStatus status) {
        return status != null ? referral.referralStatus.eq(status) : null;
    }

    private BooleanExpression referringInstitutionIdEq(UUID referringInstitutionId) {
        return referringInstitutionId != null ? referral.referringInstitutionId.eq(referringInstitutionId) : null;
    }

    private BooleanExpression examTypeIdEq(UUID examTypeId) {
        return examTypeId != null ? referral.examTypeId.eq(examTypeId) : null;
    }

    private BooleanExpression patientNameContains(String patientName) {
        return StringUtils.hasText(patientName) ? referral.patientName.contains(patientName.trim()) : null;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private OrderSpecifier<?>[] toOrderSpecifiers(Sort sort) {
        PathBuilder<Referral> path = new PathBuilder<>(Referral.class, referral.getMetadata());
        return sort.stream()
                .map(order -> new OrderSpecifier(
                        order.isAscending() ? Order.ASC : Order.DESC,
                        path.getComparable(order.getProperty(), Comparable.class)))
                .toArray(OrderSpecifier[]::new);
    }
}
