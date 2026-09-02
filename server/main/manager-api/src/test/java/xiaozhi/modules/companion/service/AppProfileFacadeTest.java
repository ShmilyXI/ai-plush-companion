package xiaozhi.modules.companion.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Optional;
import java.util.List;

import org.junit.jupiter.api.Test;

import xiaozhi.common.exception.RenException;
import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.agent.dao.AgentSnapshotDao;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.agent.entity.AgentSnapshotEntity;
import xiaozhi.modules.agent.service.AgentService;
import xiaozhi.modules.companion.dto.AppProfileSaveDTO;
import xiaozhi.modules.companion.service.impl.AppProfileFacadeImpl;
import xiaozhi.modules.companion.service.impl.FileProfileAvatarStore;
import xiaozhi.modules.companion.service.CompanionProfileService;
import xiaozhi.modules.companion.vo.AppAvatarContent;
import xiaozhi.modules.companion.vo.CompanionProfileVO;

class AppProfileFacadeTest {
    private final CompanionProfileService profiles = mock(CompanionProfileService.class);
    private final AgentService agents = mock(AgentService.class);
    private final AgentDao agentDao = mock(AgentDao.class);
    private final AgentSnapshotDao snapshots = mock(AgentSnapshotDao.class);
    private final AppProfileFacadeImpl facade = new AppProfileFacadeImpl(profiles, agents, agentDao, snapshots);

    @Test
    void savePublishesAndActivatesTheNewSnapshotExactlyOnce() {
        AgentEntity agent = new AgentEntity();
        agent.setId("p1");
        agent.setUserId(7L);
        agent.setCompanionEnabled(1);
        AgentSnapshotEntity snapshot = new AgentSnapshotEntity();
        snapshot.setId("snapshot-2");
        snapshot.setAgentId("p1");
        snapshot.setVersionNo(2);
        when(agentDao.selectByIdForUpdate("p1")).thenReturn(agent);
        when(snapshots.selectLatestSnapshot("p1")).thenReturn(snapshot);

        facade.save(7L, "p1", new AppProfileSaveDTO());

        verify(profiles).update(eq(7L), eq("p1"), any());
        verify(agents).publishVersion("p1", 7L);
        verify(agents).activateVersion("p1", "snapshot-2", 7L);
    }

    @Test
    void deleteRejectsAProfileStillBoundToADevice() {
        AgentEntity agent = new AgentEntity();
        agent.setId("p1");
        agent.setUserId(7L);
        agent.setCompanionEnabled(1);
        when(agentDao.selectByIdForUpdate("p1")).thenReturn(agent);
        when(agentDao.getDeviceCountByAgentId("p1")).thenReturn(1);

        assertThrows(RuntimeException.class, () -> facade.delete(7L, "p1"));
        verify(agentDao, never()).update(any(), any());
    }

    @Test
    void listDelegatesToTheOwnerScopedProfileService() {
        when(profiles.list(7L)).thenReturn(List.of(new CompanionProfileVO()));
        assertEquals(1, facade.list(7L).size());
        verify(profiles).list(7L);
    }

    @Test
    void avatarBytesAreStoredBeforeReturningTheChecksumAddressedUrl() throws Exception {
        AgentEntity agent = new AgentEntity();
        agent.setId("p1");
        agent.setUserId(7L);
        agent.setCompanionEnabled(1);
        when(agentDao.selectByIdForUpdate("p1")).thenReturn(agent);
        when(agentDao.update(any(), any())).thenReturn(1);
        FileProfileAvatarStore avatarStore = mock(FileProfileAvatarStore.class);
        AppProfileFacadeImpl avatarFacade = new AppProfileFacadeImpl(
                profiles, agents, agentDao, snapshots, null, avatarStore);
        byte[] content = "avatar".getBytes(StandardCharsets.UTF_8);
        String checksum = java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(content));

        var result = avatarFacade.saveAvatar(7L, "p1", content, "image/png");

        verify(avatarStore).save("p1", checksum, "image/png", content);
        assertEquals("/app/assets/avatars/p1/" + checksum, result.getUrl());
    }

    @Test
    void avatarReadRequiresTheStoredProfileChecksum() {
        AgentEntity agent = new AgentEntity();
        agent.setId("p1");
        agent.setUserId(7L);
        agent.setCompanionEnabled(1);
        agent.setAvatarUrl("/app/assets/avatars/p1/checksum");
        when(agentDao.selectByIdForUpdate("p1")).thenReturn(agent);
        FileProfileAvatarStore avatarStore = mock(FileProfileAvatarStore.class);
        when(avatarStore.load("p1", "checksum")).thenReturn(Optional.of(
                new FileProfileAvatarStore.StoredAvatar(
                        new byte[] {1, 2}, "image/png", java.nio.file.Path.of("avatar"))));
        AppProfileFacadeImpl avatarFacade = new AppProfileFacadeImpl(
                profiles, agents, agentDao, snapshots, null, avatarStore);

        AppAvatarContent result = avatarFacade.loadAvatar(7L, "p1", "checksum");

        assertEquals("image/png", result.contentType());
        org.junit.jupiter.api.Assertions.assertArrayEquals(new byte[] {1, 2}, result.content());
    }
}
