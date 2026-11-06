-- Apply after resolving active duplicate (name, modality) pairs and trimming legacy names.
-- Keep this name in sync with ExamTypeService.UNIQUE_EXAM_TYPE_INDEX.
CREATE UNIQUE INDEX uq_exam_type_active_name_modality
    ON p_exam_types (name, modality)
    WHERE deleted_at IS NULL;
