-- Apply after resolving existing active duplicate names and trimming legacy names.
-- Keep this name in sync with EquipmentService.UNIQUE_EQUIPMENT_NAME_INDEX.
CREATE UNIQUE INDEX uq_equipment_active_institution_name
    ON p_equipments (institution_id, name)
    WHERE deleted_at IS NULL;
