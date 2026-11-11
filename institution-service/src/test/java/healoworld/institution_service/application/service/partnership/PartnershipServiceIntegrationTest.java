package healoworld.institution_service.application.service.partnership;

import healoworld.institution_service.domain.entity.Institution;
import healoworld.institution_service.domain.entity.Partnership;
import healoworld.institution_service.domain.entity.PartnershipHistory;
import healoworld.institution_service.domain.enums.InstitutionType;
import healoworld.institution_service.domain.enums.Role;
import healoworld.institution_service.infrastructure.repository.InstitutionRepository;
import healoworld.institution_service.infrastructure.repository.PartnershipRepository;
import healoworld.institution_service.infrastructure.repository.UserRepository;
import healoworld.institution_service.presentation.dto.response.PartnershipResponse;
import healoworld.institution_service.support.PostgresContainerSupport;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static healoworld.institution_service.domain.enums.PartnershipStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.AssertionsForClassTypes.tuple;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class PartnershipServiceIntegrationTest extends PostgresContainerSupport {
    @Autowired
    PartnershipService partnershipService;

    @Autowired
    InstitutionRepository institutionRepository;

    @Autowired
    UserRepository userRepository;

    @Autowired
    EntityManager em;

    private Institution requester;
    private Institution exam;
    @Autowired
    private PartnershipRepository partnershipRepository;

    @BeforeEach
    void setUp() {
        requester = institutionRepository.save(
                Institution.create("A의원", InstitutionType.REQUESTER, "서울시 강남구", "10000001"));
        exam = institutionRepository.save(
                Institution.create("X영상센터", InstitutionType.EXAM, "서울시 서초구", "10000003"));
    }

    @Test
    @DisplayName("요청 -> 승인 -> 종료하면 협약 상태가 바뀌고, 단계마다 이력이 함께 저장된다.")
    void request_approve_terminate() {
        // when: 실제 사용 순서대로 서비스 호출 (각 단계의 주체도 실제와 같게)
        PartnershipResponse requested = partnershipService.request(requester.getId(), exam.getId());  //요청
        partnershipService.approve(requested.id(), exam.getId());  //승인
        partnershipService.terminate(requested.id(), requester.getId(), Role.REQUESTER);  //종료

        // then: db에서 다시 조회해서 확인
        Partnership partnership = partnershipRepository.findById(requested.id()).orElseThrow();
        assertThat(partnership.getStatus()).isEqualTo(TERMINATED);
        assertThat(partnership.getApprovedAt()).isNotNull();
        assertThat(partnership.getTerminatedAt()).isNotNull();

        assertThat(historiesOf(requested.id()))
                .extracting(PartnershipHistory::getFromStatus, PartnershipHistory::getToStatus)
                .containsExactlyInAnyOrder(
                        tuple(null, PENDING),        // 첫 요청
                        tuple(PENDING, ACTIVE),      // 승인
                        tuple(ACTIVE, TERMINATED));  // 종료

    }

    @Test
    @DisplayName("거절된 협약을 다시 요청하면 새로 만들지 않고 기존 협약이 PENDING으로 돌아가며 이력이 남는다.")
    void reRequest_afterReject() {
        // given
        PartnershipResponse first = partnershipService.request(requester.getId(), exam.getId());
        partnershipService.reject(first.id(), exam.getId());

        // when
        PartnershipResponse second = partnershipService.request(requester.getId(), exam.getId());
        flushAndClear();

        // then
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(partnershipRepository.findById(first.id()).orElseThrow().getStatus()).isEqualTo(PENDING);
        assertThat(partnershipRepository.count()).isEqualTo(1);
        assertThat(historiesOf(first.id()))
                .extracting(PartnershipHistory::getFromStatus, PartnershipHistory::getToStatus)
                .containsExactlyInAnyOrder(
                        tuple(null, PENDING),        // 첫 요청
                        tuple(PENDING, REJECTED),    // 거절
                        tuple(REJECTED, PENDING));   // 재요청
    }

    // PartnershipHistoryRepository에는 조회 메서드가 없어서 테스트에서 JPQL로 직접 조회
    private List<PartnershipHistory> historiesOf(UUID partnershipId) {
        return em.createQuery("select h from PartnershipHistory h where h.partnership.id = :id", PartnershipHistory.class)
                .setParameter("id", partnershipId)
                .getResultList();
    }

    private void flushAndClear() {
        em.flush();   // 서비스가 바꾼 내용을 DB에 실제로 반영
        em.clear();   // 메모리를 비워서 아래 조회가 DB에서 다시 읽게 함
    }
}
