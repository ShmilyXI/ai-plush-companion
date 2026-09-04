package zixuan.modules.agent.service;

import zixuan.common.page.PageData;
import zixuan.common.service.BaseService;
import zixuan.modules.agent.dto.AgentSnapshotPageDTO;
import zixuan.modules.agent.entity.AgentSnapshotEntity;
import zixuan.modules.agent.vo.AgentSnapshotVO;

public interface AgentSnapshotService extends BaseService<AgentSnapshotEntity> {
    void createSnapshot(String agentId, String source);

    PageData<AgentSnapshotVO> page(String agentId, AgentSnapshotPageDTO params);

    AgentSnapshotVO getSnapshot(String agentId, String snapshotId);

    void restoreSnapshot(String agentId, String snapshotId, String currentStateToken);

    void deleteSnapshot(String agentId, String snapshotId);

    Integer getCurrentVersionNo(String agentId);

    void deleteByAgentId(String agentId);

    long redactLegacySnapshots();
}
