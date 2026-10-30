package healoworld.referral_service.application.service;

import healoworld.referral_service.domain.entity.Referral;
import healoworld.referral_service.domain.entity.Reservation;
import healoworld.referral_service.domain.enums.ReferralStatus;
import healoworld.referral_service.domain.enums.RoleType;
import healoworld.referral_service.global.exception.BaseException;
import healoworld.referral_service.global.exception.ErrorCode;
import healoworld.referral_service.infrastructure.repository.ReferralRepository;
import healoworld.referral_service.presentation.dto.request.CreateReferralRequest;
import healoworld.referral_service.presentation.dto.request.CreateReservationRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static healoworld.referral_service.support.DomainFixtures.referralIn;
import static healoworld.referral_service.support.DomainFixtures.reservation;
import static healoworld.referral_service.support.DomainFixtures.reservationRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ReferralServiceTest {
    private static final UUID USER_ID = UUID.randomUUID();
    private static final UUID OTHER_USER_ID = UUID.randomUUID();

    @Mock
    private ReferralRepository referralRepository;

    @Mock
    private ReservationService reservationService;

    @InjectMocks
    private ReferralService referralService;

    @Nested
    @DisplayName("createReferral")
    class CreateReferral {
        @Test
        @DisplayName("새 멱등키면 의뢰와 첫 예약을 생성하고 예약을 연결한다")
        void createWithReservation() {
            CreateReservationRequest reservationRequest = reservationRequest();
            CreateReferralRequest request = createRequest(UUID.randomUUID(), reservationRequest);
            Reservation reservation = reservation(referralIn(ReferralStatus.PENDING, USER_ID), 1);

            given(referralRepository.findByIdempotencyKey(request.getIdempotencyKey())).willReturn(Optional.empty());
            given(reservationService.createReservation(any(Referral.class), eq(reservationRequest))).willReturn(reservation);

            referralService.createReferral(USER_ID, request);

            ArgumentCaptor<Referral> captor = ArgumentCaptor.forClass(Referral.class);
            verify(referralRepository).save(captor.capture());
            Referral saved = captor.getValue();

            assertThat(saved.getReferrerId()).isEqualTo(USER_ID);
            assertThat(saved.getReferralStatus()).isEqualTo(ReferralStatus.PENDING);
            assertThat(saved.getIdempotencyKey()).isEqualTo(request.getIdempotencyKey());
            assertThat(saved.getCurrentReservationId()).isEqualTo(reservation.getId());
            verify(reservationService).createReservation(saved, reservationRequest);
        }

        @Test
        @DisplayName("이미 처리된 멱등키면 기존 의뢰를 반환하고 의뢰와 예약을 새로 만들지 않는다")
        void duplicatedIdempotencyKey() {
            Referral existing = referralIn(ReferralStatus.PENDING, USER_ID);
            CreateReferralRequest request = createRequest(existing.getIdempotencyKey(), reservationRequest());

            given(referralRepository.findByIdempotencyKey(existing.getIdempotencyKey())).willReturn(Optional.of(existing));

            assertThat(referralService.createReferral(USER_ID, request)).isNotNull();

            verify(referralRepository, never()).save(any());
            verifyNoInteractions(reservationService);
        }
    }

    @Nested
    @DisplayName("getReferral")
    class GetReferral {
        @Test
        @DisplayName("본인 의뢰는 조회된다")
        void owner() {
            Referral referral = referralIn(ReferralStatus.PENDING, USER_ID);
            given(referralRepository.findById(referral.getId())).willReturn(Optional.of(referral));

            assertThatCode(() -> referralService.getReferral(USER_ID, referral.getId(), RoleType.USER))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("관리자는 다른 사람의 의뢰도 조회할 수 있다")
        void admin() {
            Referral referral = referralIn(ReferralStatus.PENDING, OTHER_USER_ID);
            given(referralRepository.findById(referral.getId())).willReturn(Optional.of(referral));

            assertThatCode(() -> referralService.getReferral(USER_ID, referral.getId(), RoleType.ADMIN))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("다른 사람의 의뢰는 존재를 숨기고 NOT_FOUND로 응답한다")
        void otherUser() {
            Referral referral = referralIn(ReferralStatus.PENDING, OTHER_USER_ID);
            given(referralRepository.findById(referral.getId())).willReturn(Optional.of(referral));

            assertErrorCode(() -> referralService.getReferral(USER_ID, referral.getId(), RoleType.USER),
                    ErrorCode.REFERRAL_NOT_FOUND);
        }

        @Test
        @DisplayName("없는 의뢰면 NOT_FOUND")
        void notFound() {
            UUID referralId = UUID.randomUUID();
            given(referralRepository.findById(referralId)).willReturn(Optional.empty());

            assertErrorCode(() -> referralService.getReferral(USER_ID, referralId, RoleType.USER),
                    ErrorCode.REFERRAL_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("deleteReferral")
    class DeleteReferral {
        @Test
        @DisplayName("본인의 종료된 의뢰는 soft delete된다")
        void success() {
            Referral referral = referralIn(ReferralStatus.FAILED, USER_ID);
            given(referralRepository.findById(referral.getId())).willReturn(Optional.of(referral));

            referralService.deleteReferral(USER_ID, referral.getId(), RoleType.USER);

            assertThat(referral.getDeletedAt()).isNotNull();
            assertThat(referral.getDeletedBy()).isEqualTo(USER_ID);
        }

        @Test
        @DisplayName("다른 사람의 의뢰는 삭제할 수 없다")
        void otherUser() {
            Referral referral = referralIn(ReferralStatus.FAILED, OTHER_USER_ID);
            given(referralRepository.findById(referral.getId())).willReturn(Optional.of(referral));

            assertErrorCode(() -> referralService.deleteReferral(USER_ID, referral.getId(), RoleType.USER),
                    ErrorCode.REFERRAL_NOT_FOUND);
            assertThat(referral.getDeletedAt()).isNull();
        }

        @Test
        @DisplayName("진행 중인 의뢰는 삭제할 수 없다")
        void invalidStatus() {
            Referral referral = referralIn(ReferralStatus.PENDING, USER_ID);
            given(referralRepository.findById(referral.getId())).willReturn(Optional.of(referral));

            assertErrorCode(() -> referralService.deleteReferral(USER_ID, referral.getId(), RoleType.USER),
                    ErrorCode.INVALID_REFERRAL_STATUS);
        }
    }

    private CreateReferralRequest createRequest(UUID idempotencyKey, CreateReservationRequest reservationRequest) {
        return CreateReferralRequest.builder()
                .referringInstitutionId(UUID.randomUUID())
                .examTypeId(UUID.randomUUID())
                .patientName("홍길동")
                .idempotencyKey(idempotencyKey)
                .reservation(reservationRequest)
                .build();
    }

    private void assertErrorCode(Runnable action, ErrorCode errorCode) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BaseException.class)
                .extracting("errorCode")
                .isEqualTo(errorCode);
    }
}
