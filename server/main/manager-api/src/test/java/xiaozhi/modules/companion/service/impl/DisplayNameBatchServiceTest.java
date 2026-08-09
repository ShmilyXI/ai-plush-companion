package xiaozhi.modules.companion.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import xiaozhi.common.exception.ErrorCode;
import xiaozhi.common.utils.MessageUtils;
import xiaozhi.modules.model.dao.ModelConfigDao;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.model.service.impl.ModelConfigServiceImpl;
import xiaozhi.modules.timbre.dao.TimbreDao;
import xiaozhi.modules.timbre.entity.TimbreEntity;
import xiaozhi.modules.timbre.service.impl.TimbreServiceImpl;
import xiaozhi.modules.voiceclone.dao.VoiceCloneDao;
import xiaozhi.modules.voiceclone.entity.VoiceCloneEntity;

class DisplayNameBatchServiceTest {
    @Test
    void modelNamesUseOneQueryForDifferentIds() {
        ModelConfigDao dao = mock(ModelConfigDao.class);
        when(dao.selectList(any())).thenReturn(List.of(model("m1", "One"), model("m2", "Two")));
        ModelConfigServiceImpl service = new ModelConfigServiceImpl(dao, null, null, null);

        Map<String, String> names = service.getModelNamesByIds(Set.of("m1", "m2"));

        assertEquals(Map.of("m1", "One", "m2", "Two"), names);
        verify(dao, times(1)).selectList(any());
    }

    @Test
    void timbreNamesUseFixedQueriesForDifferentIds() {
        TimbreDao timbreDao = mock(TimbreDao.class);
        VoiceCloneDao cloneDao = mock(VoiceCloneDao.class);
        when(timbreDao.selectList(any())).thenReturn(List.of(timbre("v1", "Alice")));
        when(cloneDao.selectList(any())).thenReturn(List.of(clone("v2", "Bob")));
        TimbreServiceImpl service = new TimbreServiceImpl(timbreDao, cloneDao, null);

        Map<String, String> names;
        try (MockedStatic<MessageUtils> messages = mockStatic(MessageUtils.class)) {
            messages.when(() -> MessageUtils.getMessage(ErrorCode.VOICE_CLONE_PREFIX)).thenReturn("Clone ");
            names = service.getTimbreNamesByIds(Set.of("v1", "v2"));
        }

        assertEquals("Alice", names.get("v1"));
        assertTrueCloneName(names.get("v2"));
        verify(timbreDao, times(1)).selectList(any());
        verify(cloneDao, times(1)).selectList(any());
    }

    @Test
    void modelNamesKeepNullDatabaseNamesWithoutFailing() {
        ModelConfigDao dao = mock(ModelConfigDao.class);
        when(dao.selectList(any())).thenReturn(List.of(model("m1", null), model("m2", "Two")));
        ModelConfigServiceImpl service = new ModelConfigServiceImpl(dao, null, null, null);

        Map<String, String> names = service.getModelNamesByIds(Set.of("m1", "m2"));

        assertTrue(names.containsKey("m1"));
        assertNull(names.get("m1"));
        assertEquals("Two", names.get("m2"));
    }

    @Test
    void timbreNamesKeepNullDatabaseNamesWithoutFailing() {
        TimbreDao timbreDao = mock(TimbreDao.class);
        VoiceCloneDao cloneDao = mock(VoiceCloneDao.class);
        when(timbreDao.selectList(any())).thenReturn(List.of(timbre("v1", null), timbre("v2", "Bob")));
        TimbreServiceImpl service = new TimbreServiceImpl(timbreDao, cloneDao, null);

        Map<String, String> names = service.getTimbreNamesByIds(Set.of("v1", "v2"));

        assertTrue(names.containsKey("v1"));
        assertNull(names.get("v1"));
        assertEquals("Bob", names.get("v2"));
        verify(cloneDao, times(0)).selectList(any());
    }

    private ModelConfigEntity model(String id, String name) {
        ModelConfigEntity entity = new ModelConfigEntity();
        entity.setId(id);
        entity.setModelName(name);
        return entity;
    }

    private TimbreEntity timbre(String id, String name) {
        TimbreEntity entity = new TimbreEntity();
        entity.setId(id);
        entity.setName(name);
        return entity;
    }

    private VoiceCloneEntity clone(String id, String name) {
        VoiceCloneEntity entity = new VoiceCloneEntity();
        entity.setId(id);
        entity.setName(name);
        return entity;
    }

    private void assertTrueCloneName(String name) {
        org.junit.jupiter.api.Assertions.assertTrue(name != null && name.endsWith("Bob"));
    }
}
