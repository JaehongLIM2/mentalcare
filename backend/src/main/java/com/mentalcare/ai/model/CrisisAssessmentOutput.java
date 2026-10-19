package com.mentalcare.ai.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Structured crisis assessment. Mirrors resources/schemas/crisis-assessment.schema.json. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record CrisisAssessmentOutput(String riskLevel, String reason, String recommendedAction) {}
