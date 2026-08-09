package xiaozhi.modules.companion.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import xiaozhi.modules.companion.dto.CompanionGrantDTO;
import xiaozhi.modules.companion.dto.CompanionPlanSaveDTO;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class CompanionSubscriptionControllerTest {
    private final LocalValidatorFactoryBean validator = validator();

    @Test
    void planDtoAlignsRequiredFieldsAndDatabaseLengths() {
        CompanionPlanSaveDTO valid = validPlan();
        assertEquals(Set.of(), validator.validate(valid));

        valid.setPlanCode("x".repeat(33));
        valid.setPlanName("x".repeat(65));
        Set<ConstraintViolation<CompanionPlanSaveDTO>> violations = validator.validate(valid);

        assertEquals(2, violations.size());
    }

    @Test
    void grantDtoRequiresPlanAndExpiry() {
        CompanionGrantDTO dto = new CompanionGrantDTO();

        Set<ConstraintViolation<CompanionGrantDTO>> violations = validator.validate(dto);

        assertEquals(2, violations.size());

        dto.setPlanId("x".repeat(33));
        dto.setExpiresAt(Instant.now().plusSeconds(60));
        assertEquals(1, validator.validate(dto).size());
    }

    private CompanionPlanSaveDTO validPlan() {
        CompanionPlanSaveDTO dto = new CompanionPlanSaveDTO();
        dto.setPlanCode("basic-plus");
        dto.setPlanName("Basic Plus");
        dto.setMaxDevices(2);
        dto.setMaxProfiles(5);
        dto.setLongTermMemory(1);
        dto.setAdvancedVoice(0);
        dto.setStatus(1);
        return dto;
    }

    private static LocalValidatorFactoryBean validator() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return validator;
    }
}
