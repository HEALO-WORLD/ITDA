package healoworld.referral_service.infrastructure.repository;

import com.querydsl.core.Tuple;
import com.querydsl.core.types.Order;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.jpa.impl.JPAQuery;
import com.querydsl.jpa.impl.JPAQueryFactory;
import healoworld.referral_service.domain.entity.QInstitutionNotification;
import healoworld.referral_service.domain.entity.QInstitutionNotificationRead;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.support.PageableExecutionUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

// 기관 알림 목록: 알림 본문 LEFT JOIN 이 사용자의 읽음 기록 (사용자당 최대 1행이라 알림이 중복되지 않는다)
// 읽음 필터·정렬·페이지·전체 건수를 모두 DB 한 번의 조회 조건으로 계산한다 (페이지를 가져온 뒤 Java에서 거르지 않는다)
//  - isRead=true: 읽음 기록이 있음 / false: 없음
//  - 정렬 createdAt은 알림 생성 시각, readAt은 이 사용자가 처음 읽은 시각
//    안 읽은 알림의 readAt은 NULL이며 PostgreSQL 기본 순서(asc는 NULL 마지막, desc는 NULL 처음)를 따른다
//    (읽은 시각이 알림 테이블 컬럼이던 이전 구조와 같은 순서)
//  - 같은 값끼리는 알림 ID로 순서를 고정해 페이지 사이에 행이 빠지거나 겹치지 않게 한다
@RequiredArgsConstructor
public class InstitutionNotificationRepositoryCustomImpl implements InstitutionNotificationRepositoryCustom {

    private static final QInstitutionNotification notification = QInstitutionNotification.institutionNotification;
    private static final QInstitutionNotificationRead read = QInstitutionNotificationRead.institutionNotificationRead;

    private final JPAQueryFactory queryFactory;

    @Override
    public Page<InstitutionNotificationView> searchForUser(UUID institutionId, UUID userId, UUID referralId,
                                                           Boolean isRead, Pageable pageable) {
        BooleanExpression[] conditions = {
                notification.institutionId.eq(institutionId),
                referralIdEq(referralId),
                isReadEq(isRead)
        };

        List<InstitutionNotificationView> content = queryFactory
                .select(notification, read.readAt)
                .from(notification)
                .leftJoin(read).on(read.notificationId.eq(notification.id), read.userId.eq(userId))
                .where(conditions)
                .orderBy(toOrderSpecifiers(pageable.getSort()))
                .offset(pageable.getOffset())
                .limit(pageable.getPageSize())
                .fetch()
                .stream()
                .map(InstitutionNotificationRepositoryCustomImpl::toView)
                .toList();

        // 읽음 필터가 없으면 JOIN 없이 알림만 센다 (LEFT JOIN은 행 수를 바꾸지 않는다)
        JPAQuery<Long> countQuery = queryFactory
                .select(notification.count())
                .from(notification);
        if (isRead != null) {
            countQuery.leftJoin(read).on(read.notificationId.eq(notification.id), read.userId.eq(userId));
        }
        countQuery.where(conditions);

        return PageableExecutionUtils.getPage(content, pageable, countQuery::fetchOne);
    }

    private static InstitutionNotificationView toView(Tuple tuple) {
        return new InstitutionNotificationView(tuple.get(notification), tuple.get(read.readAt));
    }

    private BooleanExpression referralIdEq(UUID referralId) {
        return referralId != null ? notification.referralId.eq(referralId) : null;
    }

    private BooleanExpression isReadEq(Boolean isRead) {
        if (isRead == null) {
            return null;
        }
        return isRead ? read.id.isNotNull() : read.id.isNull();
    }

    // 허용 정렬 필드(createdAt, readAt)는 NotificationPageRequest가 이미 검증한다
    private OrderSpecifier<?>[] toOrderSpecifiers(Sort sort) {
        List<OrderSpecifier<?>> orders = new ArrayList<>();
        for (Sort.Order order : sort) {
            Order direction = order.isAscending() ? Order.ASC : Order.DESC;
            switch (order.getProperty()) {
                case "createdAt" -> orders.add(new OrderSpecifier<>(direction, notification.createdAt));
                case "readAt" -> orders.add(new OrderSpecifier<>(direction, read.readAt));
                default -> throw new IllegalArgumentException("지원하지 않는 정렬 필드: " + order.getProperty());
            }
        }
        orders.add(notification.id.asc());
        return orders.toArray(OrderSpecifier[]::new);
    }
}
