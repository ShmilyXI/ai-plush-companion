package zixuan.modules.analytics.service;

import java.util.Map;

import zixuan.modules.analytics.dto.ClientEventsDTO;

/**
 * 内测期间用来回答三个问题的统计：用户会不会回来（留存）、回复够不够快（延迟）、
 * 哪里出了问题（客户端事件）。所有数据都不含对话内容。
 */
public interface AnalyticsService {

    /** 记录客户端上报的事件。格式不对的事件直接丢弃，返回实际记下的条数。 */
    int recordEvents(Long userId, ClientEventsDTO dto);

    /**
     * 留存、延迟和事件的总览。
     *
     * @param days 看最近多少天，1 到 60
     */
    Map<String, Object> overview(int days);
}
