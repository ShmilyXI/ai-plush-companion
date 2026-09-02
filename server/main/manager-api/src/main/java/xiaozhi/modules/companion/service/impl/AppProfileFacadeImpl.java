package xiaozhi.modules.companion.service.impl;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.security.MessageDigest;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;

import xiaozhi.common.exception.ErrorCode;
import xiaozhi.common.exception.RenException;
import xiaozhi.modules.agent.dao.AgentDao;
import xiaozhi.modules.agent.dao.AgentSnapshotDao;
import xiaozhi.modules.agent.entity.AgentEntity;
import xiaozhi.modules.agent.entity.AgentSnapshotEntity;
import xiaozhi.modules.agent.service.AgentService;
import xiaozhi.modules.agent.service.AgentTemplateService;
import xiaozhi.modules.agent.entity.AgentTemplateEntity;
import xiaozhi.modules.companion.dto.AppProfileCreateDTO;
import xiaozhi.modules.companion.dto.AppProfileSaveDTO;
import xiaozhi.modules.companion.dto.CompanionProfileSaveDTO;
import xiaozhi.modules.companion.service.AppProfileFacade;
import xiaozhi.modules.companion.service.CompanionProfileService;
import xiaozhi.modules.companion.vo.AppCapabilityOptionVO;
import xiaozhi.modules.companion.vo.AppAvatarVO;
import xiaozhi.modules.companion.vo.AppAvatarContent;
import xiaozhi.modules.companion.vo.CompanionProfileVO;
import xiaozhi.modules.companion.model.vo.CompanionModelOptionVO;

@Service
public class AppProfileFacadeImpl implements AppProfileFacade {
    private final CompanionProfileService profiles;
    private final AgentService agents;
    private final AgentDao agentDao;
    private final AgentSnapshotDao snapshots;
    private final FileProfileAvatarStore avatarStore;
    private AgentTemplateService templateService;

    public AppProfileFacadeImpl(CompanionProfileService profiles, AgentService agents, AgentDao agentDao,
            AgentSnapshotDao snapshots) {
        this(profiles, agents, agentDao, snapshots, null, new FileProfileAvatarStore());
    }

    public AppProfileFacadeImpl(CompanionProfileService profiles, AgentService agents, AgentDao agentDao,
            AgentSnapshotDao snapshots, AgentTemplateService templateService) {
        this(profiles, agents, agentDao, snapshots, templateService, new FileProfileAvatarStore());
    }

    @Autowired
    public AppProfileFacadeImpl(CompanionProfileService profiles, AgentService agents, AgentDao agentDao,
            AgentSnapshotDao snapshots, AgentTemplateService templateService,
            FileProfileAvatarStore avatarStore) {
        this.profiles = profiles;
        this.agents = agents;
        this.agentDao = agentDao;
        this.snapshots = snapshots;
        this.templateService = templateService;
        this.avatarStore = avatarStore == null ? new FileProfileAvatarStore() : avatarStore;
    }

    @Override
    public List<CompanionProfileVO> list(Long userId) {
        return profiles.list(userId);
    }

    @Override
    public CompanionProfileVO get(Long userId, String profileId) {
        return profiles.get(userId, profileId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public String create(Long userId, AppProfileCreateDTO request) {
        if (request == null || request.getName() == null || request.getName().isBlank()) {
            throw new RenException("角色名称不能为空");
        }
        String source = request.getSource() == null ? "template" : request.getSource().trim().toLowerCase();
        if (!"template".equals(source) && !"custom".equals(source)) {
            throw new RenException("角色来源无效");
        }
        String templateId = request.getTemplateId();
        if ("custom".equals(source) && (templateId == null || templateId.isBlank())) {
            templateId = "template-xiaozhi";
        }
        return profiles.createFromTemplate(userId, templateId, request.getName().trim());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void save(Long userId, String profileId, AppProfileSaveDTO request) {
        AgentEntity locked = requireOwned(userId, profileId);
        profiles.update(userId, profileId, request == null ? new AppProfileSaveDTO() : request);
        agents.publishVersion(profileId, userId);
        AgentSnapshotEntity latest = snapshots.selectLatestSnapshot(profileId);
        if (latest == null || latest.getVersionNo() == null || latest.getId() == null) {
            throw new RenException("角色版本发布失败");
        }
        agents.activateVersion(profileId, latest.getId(), userId);
        // Keep the lock reference live for transaction-level ownership checks.
        if (!userId.equals(locked.getUserId())) throw new RenException(ErrorCode.NO_PERMISSION);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setMemoryEnabled(Long userId, String profileId, boolean enabled) {
        requireOwned(userId, profileId);
        CompanionProfileSaveDTO update = new CompanionProfileSaveDTO();
        update.setMemoryEnabled(enabled ? 1 : 0);
        profiles.update(userId, profileId, update);
        agents.publishVersion(profileId, userId);
        AgentSnapshotEntity latest = snapshots.selectLatestSnapshot(profileId);
        if (latest != null && latest.getId() != null) agents.activateVersion(profileId, latest.getId(), userId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long userId, String profileId) {
        requireOwned(userId, profileId);
        Integer count = agentDao.getDeviceCountByAgentId(profileId);
        if (count != null && count > 0) throw new RenException(ErrorCode.DELETE_DATA_FAILED);
        int affected = agentDao.update(null, new UpdateWrapper<AgentEntity>()
                .eq("id", profileId)
                .eq("user_id", userId)
                .isNull("consumer_deleted_at")
                .set("consumer_deleted_at", new Date()));
        if (affected != 1) throw new RenException("profile_deleted");
    }

    @Override
    public List<AppCapabilityOptionVO> capabilityOptions(Long userId, String profileId) {
        requireOwned(userId, profileId);
        return List.of(option("emotion", "情绪感知", "识别对话中的情绪变化", true), option("weather", "天气", "回答指定城市天气", true), option("web_search", "联网搜索", "查询公开信息", false));
    }

    @Override
    public List<Map<String, Object>> templates() {
        if (templateService == null) return List.of();
        List<AgentTemplateEntity> rows = templateService.list();
        if (rows == null) return List.of();
        return rows.stream().filter(row -> row != null).map(row -> {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", row.getId());
            item.put("name", row.getAgentName());
            item.put("promptPreview", row.getSystemPrompt() == null ? "" : row.getSystemPrompt().substring(0, Math.min(120, row.getSystemPrompt().length())));
            item.put("ttsVoiceId", row.getTtsVoiceId());
            return item;
        }).toList();
    }

    @Override
    public List<CompanionModelOptionVO> modelOptions(Long userId, String profileId) {
        requireOwned(userId, profileId);
        return profiles.modelOptions(userId, profileId);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public AppAvatarVO saveAvatar(Long userId, String profileId, byte[] content, String contentType) {
        requireOwned(userId, profileId);
        if (content == null || content.length == 0 || content.length > 2 * 1024 * 1024) {
            throw new RenException("头像大小必须在 1B 到 2MB 之间");
        }
        if (contentType == null || !List.of("image/jpeg", "image/png", "image/webp").contains(contentType.toLowerCase())) {
            throw new RenException("头像格式不支持");
        }
        String checksum;
        try {
            checksum = java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (java.security.GeneralSecurityException exception) {
            throw new RenException("头像校验失败");
        }
        avatarStore.save(profileId, checksum, contentType, content);
        String url = "/app/assets/avatars/" + profileId + "/" + checksum;
        int affected = agentDao.update(null, new UpdateWrapper<AgentEntity>()
                .eq("id", profileId).eq("user_id", userId).isNull("consumer_deleted_at")
                .set("avatar_url", url).set("updated_at", new Date()).set("updater", userId));
        if (affected != 1) throw new RenException("profile_deleted");
        return new AppAvatarVO(url, checksum);
    }

    @Override
    public AppAvatarContent loadAvatar(Long userId, String profileId, String checksum) {
        AgentEntity profile = requireOwned(userId, profileId);
        String storedUrl = profile.getAvatarUrl();
        if (storedUrl == null || !storedUrl.endsWith("/" + checksum)) {
            throw new RenException("头像不存在");
        }
        return avatarStore.load(profileId, checksum)
                .map(value -> new AppAvatarContent(value.content(), value.contentType()))
                .orElseThrow(() -> new RenException("头像不存在"));
    }

    private AppCapabilityOptionVO option(String id, String name, String description, boolean enabled) {
        AppCapabilityOptionVO option = new AppCapabilityOptionVO();
        option.setId(id);
        option.setName(name);
        option.setDescription(description);
        option.setEnabled(enabled);
        return option;
    }

    private AgentEntity requireOwned(Long userId, String profileId) {
        if (userId == null || profileId == null || profileId.isBlank()) throw new RenException(ErrorCode.NO_PERMISSION);
        AgentEntity profile = agentDao.selectByIdForUpdate(profileId);
        if (profile == null || !userId.equals(profile.getUserId()) || !Integer.valueOf(1).equals(profile.getCompanionEnabled())) {
            throw new RenException(ErrorCode.AGENT_NOT_FOUND);
        }
        if (profile.getConsumerDeletedAt() != null) throw new RenException("profile_deleted");
        return profile;
    }
}
