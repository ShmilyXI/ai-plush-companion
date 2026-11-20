package zixuan.modules.conversation.service;

import java.util.List;
import java.util.Map;

import zixuan.common.page.PageData;
import zixuan.modules.conversation.entity.SafetyEventEntity;
import zixuan.modules.conversation.vo.PublicConversationRuntimeBundleVO;

/**
 * 内容安全在管理服务这一侧的部分：把运营追加的违禁词交给运行时，以及保存安全事件供复核。
 * 实际的检查在 zixuan-server 里进行。
 */
public interface ConversationSafetyService {
    /** 写在运行时配置里的键：追加的违禁词。 */
    String BLOCKED_TERMS_KEY = "safetyBlockedTerms";

    /** 运营在系统参数 app.safety_blocked_terms 里追加的违禁词。 */
    List<String> blockedTerms();

    /** 保存运行时上报的一条安全事件。内容不合法时忽略。 */
    void record(PublicConversationRuntimeBundleVO bundle, Map<String, ?> event);

    PageData<SafetyEventEntity> page(long page, long limit);
}
