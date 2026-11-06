package healoworld.institution_service.application.service.equipment;

import healoworld.institution_service.domain.entity.Equipment;
import healoworld.institution_service.global.exception.BaseException;
import healoworld.institution_service.global.exception.ErrorCode;
import healoworld.institution_service.infrastructure.repository.EquipmentRepository;
import healoworld.institution_service.presentation.dto.response.EquipmentOwnerResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

@ExtendWith(MockitoExtension.class)
class EquipmentOwnerServiceTest {

    @Mock
    EquipmentRepository equipmentRepository;

    @InjectMocks
    EquipmentOwnerService equipmentOwnerService;

    @Test
    @DisplayName("장비가 있으면 장비에 저장된 실제 소속 기관 ID를 돌려준다")
    void returnsOwnerInstitution() {
        UUID equipmentId = UUID.randomUUID();
        UUID institutionId = UUID.randomUUID();
        Equipment equipment = mock(Equipment.class);
        given(equipment.getId()).willReturn(equipmentId);
        given(equipment.getInstitutionId()).willReturn(institutionId);
        given(equipmentRepository.findByIdAndDeletedAtIsNull(equipmentId)).willReturn(Optional.of(equipment));

        EquipmentOwnerResponse response = equipmentOwnerService.getOwner(equipmentId);

        assertThat(response.getEquipmentId()).isEqualTo(equipmentId);
        assertThat(response.getInstitutionId()).isEqualTo(institutionId);
    }

    @Test
    @DisplayName("없거나 삭제된 장비면 404")
    void notFound() {
        UUID equipmentId = UUID.randomUUID();
        given(equipmentRepository.findByIdAndDeletedAtIsNull(equipmentId)).willReturn(Optional.empty());

        assertThatThrownBy(() -> equipmentOwnerService.getOwner(equipmentId))
                .isInstanceOf(BaseException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
    }

    @Test
    @DisplayName("장비 ID가 없으면 400")
    void nullId() {
        assertThatThrownBy(() -> equipmentOwnerService.getOwner(null))
                .isInstanceOf(BaseException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_INPUT);
    }
}
