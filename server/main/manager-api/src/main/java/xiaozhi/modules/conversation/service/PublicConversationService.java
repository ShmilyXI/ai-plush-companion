package xiaozhi.modules.conversation.service;

import xiaozhi.modules.conversation.dto.PublicConversationCreateDTO;
import xiaozhi.modules.conversation.vo.PublicConversationSessionVO;

public interface PublicConversationService {
    PublicConversationSessionVO create(Long userId, PublicConversationCreateDTO request);
}
