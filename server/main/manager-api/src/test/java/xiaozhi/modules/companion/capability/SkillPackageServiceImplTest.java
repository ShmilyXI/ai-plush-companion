package xiaozhi.modules.companion.capability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import xiaozhi.modules.companion.capability.dao.SkillPackageDao;
import xiaozhi.modules.companion.capability.dto.SkillPackageDraftDTO;
import xiaozhi.modules.companion.capability.entity.SkillPackageEntity;
import xiaozhi.modules.companion.capability.packagefile.LocalSkillPackageStore;
import xiaozhi.modules.companion.capability.packagefile.SkillPackageBuilder;
import xiaozhi.modules.companion.capability.packagefile.SkillPackageParser;
import xiaozhi.modules.companion.capability.packagefile.SkillPackageValidator;
import xiaozhi.modules.companion.capability.service.impl.SkillPackageServiceImpl;
import xiaozhi.modules.companion.capability.vo.SkillPackageValidationVO;
import xiaozhi.common.exception.RenException;

class SkillPackageServiceImplTest {
    @TempDir
    Path root;

    @Test
    void onlineSkillSaveBuildsCanonicalDraftPackage() {
        SkillPackageDao packages = mock(SkillPackageDao.class);
        when(packages.insert(any(SkillPackageEntity.class))).thenReturn(1);
        SkillPackageValidationVO valid = new SkillPackageValidationVO();
        valid.setStatus("VALID");
        SkillPackageValidator validator = mock(SkillPackageValidator.class);
        when(validator.validate(any(), anyString())).thenReturn(valid);
        SkillPackageServiceImpl service = new SkillPackageServiceImpl(packages, new LocalSkillPackageStore(root),
                new SkillPackageBuilder(), new SkillPackageParser(), validator);

        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("schemaVersion", 1);
        manifest.put("id", "skill-weather");
        manifest.put("name", "天气");
        manifest.put("version", 1);
        manifest.put("tools", java.util.List.of());
        SkillPackageDraftDTO draft = new SkillPackageDraftDTO();
        draft.setManifest(manifest);
        draft.setSkillMarkdown("查天气");

        var result = service.saveOnlineDraft(7L, "skill-weather", 1, draft);

        assertEquals(1, result.getVersion());
        assertEquals("ONLINE", result.getSource());
    }

    @Test
    void publishMarksDraftImmutable() {
        SkillPackageDao packages = mock(SkillPackageDao.class);
        SkillPackageEntity draft = new SkillPackageEntity();
        draft.setCapabilityId("skill-weather");
        draft.setVersionNo(1);
        draft.setValidationStatus("VALID");
        draft.setPublished(0);
        when(packages.selectDraft("skill-weather")).thenReturn(draft);
        when(packages.updateById(draft)).thenReturn(1);
        SkillPackageServiceImpl service = new SkillPackageServiceImpl(packages, mock(), new SkillPackageBuilder(),
                new SkillPackageParser(), mock(SkillPackageValidator.class));

        service.publishDraft(7L, "skill-weather");

        assertEquals(1, draft.getPublished());
        assertThrows(RenException.class, () -> service.publishDraft(7L, "skill-weather"));
    }

}
