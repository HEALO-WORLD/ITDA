package healoworld.referral_service.application.service;

import healoworld.referral_service.domain.entity.Referral;
import healoworld.referral_service.domain.entity.ReferralHistory;
import healoworld.referral_service.domain.entity.Reservation;
import healoworld.referral_service.domain.enums.Reason;
import healoworld.referral_service.domain.enums.ReferralStatus;
import healoworld.referral_service.domain.enums.RoleType;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import healoworld.referral_service.infrastructure.repository.ReferralHistoryRepository;
import healoworld.referral_service.infrastructure.repository.ReferralRepository;
import healoworld.referral_service.presentation.dto.response.ReferralHistoryResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static healoworld.referral_service.support.DomainFixtures.referral;
import static healoworld.referral_service.support.DomainFixtures.reservation;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ReferralHistoryServiceTest {
    private static final UUID USER_ID = UUID.randomUUID();

    @Mock
    private ReferralHistoryRepository referralHistoryRepository;

    @Mock
    private ReferralRepository referralRepository;

    @InjectMocks
    private ReferralHistoryService referralHistoryService;

    @Test
    @DisplayName("의뢰기관은 자기 기관 의뢰의 변경 내역을 조회한다")
    void referringInstitution() {
        Referral referral = referral(USER_ID);
        Reservation reservation = reservation(referral, 1);
        ReferralHistory created = history(referral, reservation, null, ReferralStatus.PENDING, Reason.REFERRAL_CREATED);
        ReferralHistory confirmed = history(referral, reservation, ReferralStatus.PENDING, ReferralStatus.CONFIRMED, Reason.RESERVATION_CONFIRMED);
        given(referralRepository.findById(referral.getId())).willReturn(Optional.of(referral));
        given(referralHistoryRepository.findAllByReferralIdOrderByOccurredAtAsc(referral.getId()))
                .willReturn(List.of(created, confirmed));

        List<ReferralHistoryResponse> result =
                referralHistoryService.getReferralHistories(referral.getReferringInstitutionId(), referral.getId(), RoleType.USER);

        assertThat(result).extracting(ReferralHistoryResponse::getToStatus)
                .containsExactly(ReferralStatus.PENDING, ReferralStatus.CONFIRMED);
        assertThat(result.get(1).getFromStatus()).isEqualTo(ReferralStatus.PENDING);
        assertThat(result.get(1).getReason()).isEqualTo(Reason.RESERVATION_CONFIRMED);
        assertThat(result.get(1).getReferralId()).isEqualTo(referral.getId());
        assertThat(result.get(1).getReservationId()).isEqualTo(reservation.getId());
        assertThat(result.get(1).getChangedBy()).isEqualTo(USER_ID);
    }

    @Test
    @DisplayName("관리자는 다른 기관 의뢰의 변경 내역도 조회한다")
    void admin() {
        Referral referral = referral(USER_ID);
        given(referralRepository.findById(referral.getId())).willReturn(Optional.of(referral));
        given(referralHistoryRepository.findAllByReferralIdOrderByOccurredAtAsc(referral.getId())).willReturn(List.of());

        List<ReferralHistoryResponse> result =
                referralHistoryService.getReferralHistories(UUID.randomUUID(), referral.getId(), RoleType.ADMIN);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("다른 기관은 변경 내역을 조회할 수 없고 존재 여부도 숨긴다")
    void otherInstitution() {
        Referral referral = referral(USER_ID);
        given(referralRepository.findById(referral.getId())).willReturn(Optional.of(referral));

        assertThatThrownBy(() -> referralHistoryService.getReferralHistories(UUID.randomUUID(), referral.getId(), RoleType.USER))
                .isInstanceOf(BaseException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.REFERRAL_NOT_FOUND);
        verifyNoInteractions(referralHistoryRepository);
    }

    @Test
    @DisplayName("의뢰가 없으면 REFERRAL_NOT_FOUND")
    void referralNotFound() {
        UUID referralId = UUID.randomUUID();
        given(referralRepository.findById(referralId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> referralHistoryService.getReferralHistories(UUID.randomUUID(), referralId, RoleType.ADMIN))
                .isInstanceOf(BaseException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.REFERRAL_NOT_FOUND);
        verifyNoInteractions(referralHistoryRepository);
    }

    private ReferralHistory history(Referral referral, Reservation reservation, ReferralStatus from, ReferralStatus to, Reason reason) {
        ReferralHistory history = ReferralHistory.create(referral, reservation, from, to, reason, UUID.randomUUID(), USER_ID);
        ReflectionTestUtils.setField(history, "id", UUID.randomUUID());
        return history;
    }
}
