package xiaozhi.modules.volcengine.voice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import xiaozhi.common.exception.RenException;
import xiaozhi.common.page.PageData;

class VolcengineVoiceCatalogControllerTest {
    @Test
    void exposesReadOnlyCatalogAndValidatesPagination() {
        VolcengineVoiceCatalogService service = mock(VolcengineVoiceCatalogService.class);
        VolcengineVoiceCatalogController controller = new VolcengineVoiceCatalogController(service);
        PageData<VolcengineVoiceDTO> page = new PageData<>(List.of(), 0);
        when(service.list("seed-tts-1.0", 1, 20, "女", null)).thenReturn(page);

        assertEquals(page, controller.list("seed-tts-1.0", 1, 20, "女", null).getData());
        verify(service).list("seed-tts-1.0", 1, 20, "女", null);
        assertThrows(RenException.class, () -> controller.list("seed-tts-1.0", 0, 20, null, null));
        assertThrows(RenException.class, () -> controller.list("seed-tts-1.0", 1, 101, null, null));
    }
}
