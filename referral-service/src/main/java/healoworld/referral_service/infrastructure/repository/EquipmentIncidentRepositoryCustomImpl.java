package healoworld.referral_service.infrastructure.repository;

import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import healoworld.referral_service.domain.entity.EquipmentIncident;
import healoworld.referral_service.domain.enums.EquipmentIncidentStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.support.PageableExecutionUtils;

import java.util.List;
import java.util.UUID;

import static healoworld.referral_service.domain.entity.QEquipmentIncident.equipmentIncident;

@RequiredArgsConstructor
public class EquipmentIncidentRepositoryCustomImpl implements EquipmentIncidentRepositoryCustom {
    private final JPAQueryFactory queryFactory;

    @Override
    public Page<EquipmentIncident> search(UUID institutionId, UUID equipmentId, EquipmentIncidentStatus status,
                                          Pageable pageable) {
        BooleanExpression[] conditions = {
                institutionIdEq(institutionId),
                equipmentIdEq(equipmentId),
                statusEq(status)
        };

        // 최신 장애순. 발생 시각이 같으면 등록 순서·ID로 순서를 고정해 페이지 사이에 중복·누락이 없게 한다
        List<EquipmentIncident> content = queryFactory
                .selectFrom(equipmentIncident)
                .where(conditions)
                .orderBy(equipmentIncident.occurredAt.desc(), equipmentIncident.createdAt.desc(),
                        equipmentIncident.id.desc())
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch();

        JPAQuery<Long> countQuery = queryFactory
                .select(equipmentIncident.count())
                .from(equipmentIncident)
                .where(conditions);

        return PageableExecutionUtils.getPage(content, pageable, countQuery::fetchOne);
    }

    private BooleanExpression institutionIdEq(UUID institutionId) {
        return institutionId != null ? equipmentIncident.institutionId.eq(institutionId) : null;
    }

    private BooleanExpression equipmentIdEq(UUID equipmentId) {
        return equipmentId != null ? equipmentIncident.equipmentId.eq(equipmentId) : null;
    }

    private BooleanExpression statusEq(EquipmentIncidentStatus status) {
        return status != null ? equipmentIncident.status.eq(status) : null;
    }
}
