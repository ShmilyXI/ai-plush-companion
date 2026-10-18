package zixuan.modules.agent.service.impl;

import java.util.Date;
import java.util.List;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import zixuan.modules.agent.dao.AgentDao;
import zixuan.modules.agent.dao.AgentVersionActivationAuditDao;
import zixuan.modules.agent.entity.AgentEntity;
import zixuan.modules.agent.entity.AgentVersionActivationAuditEntity;
import zixuan.modules.agent.service.AgentSnapshotService;

/** Creates a published baseline for agents created before versioned configuration existed. */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentVersionBackfillRunner implements SmartInitializingSingleton {
    private final AgentDao agentDao;
    private final AgentSnapshotService snapshotService;
    private final AgentVersionActivationAuditDao activationAuditDao;

    @Override
    public void afterSingletonsInstantiated() {
        List<AgentEntity> agents = agentDao.selectList(null);
        for (AgentEntity agent : agents == null ? List.<AgentEntity>of() : agents) {
            try {
                if (snapshotService.getCurrentVersionNo(agent.getId()) == 0) {
                    snapshotService.createSnapshot(agent.getId(), "backfill-initial");
                }
                Integer version = snapshotService.getCurrentVersionNo(agent.getId());
                if (version != null && version > 0 && agent.getActiveVersionNo() == null) {
                    agentDao.updateActiveVersion(agent.getId(), version, null);
                    AgentVersionActivationAuditEntity audit = new AgentVersionActivationAuditEntity();
                    audit.setAgentId(agent.getId());
                    audit.setUserId(agent.getUserId());
                    audit.setActivatedVersionNo(version);
                    audit.setAction("backfill");
                    audit.setCreatedAt(new Date());
                    activationAuditDao.insert(audit);
                }
            } catch (RuntimeException exception) {
                log.error("agent version backfill failed for agent={}", agent.getId(), exception);
            }
        }
    }
}
