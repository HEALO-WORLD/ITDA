package healoworld.referral_service.domain.enums;

// p_equipment_incidents.status: 장비 장애 "사건" 자체의 상태
// 장비 운영 상태(OPERATIONAL/FAILED/MAINTENANCE, Institution Service 소유)와 다르고,
// 의뢰별 후속 처리 결과는 IncidentProcessingStatus(p_incident_referrals)로 따로 관리한다
// TODO: MVP 확정값은 OCCURRED 하나뿐. 추가 상태와 전이 규칙은 팀 확정 후 추가한다
public enum EquipmentIncidentStatus {
    OCCURRED
}
