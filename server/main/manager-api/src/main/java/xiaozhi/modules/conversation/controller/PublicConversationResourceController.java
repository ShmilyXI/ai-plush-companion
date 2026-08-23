package xiaozhi.modules.conversation.controller;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import xiaozhi.common.page.PageData;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.agent.dto.AgentDTO;
import xiaozhi.modules.agent.service.AgentService;
import xiaozhi.modules.conversation.service.PublicConversationAuthService;
import xiaozhi.modules.device.service.DeviceService;
import xiaozhi.modules.device.vo.UserShowDeviceListVO;
import xiaozhi.modules.model.entity.ModelConfigEntity;
import xiaozhi.modules.model.service.ModelConfigService;
import xiaozhi.modules.security.user.SecurityUser;
import xiaozhi.modules.timbre.dto.TimbrePageDTO;
import xiaozhi.modules.timbre.service.TimbreService;
import xiaozhi.modules.timbre.vo.TimbreDetailsVO;

@RestController
@RequestMapping("/api/v1")
public class PublicConversationResourceController {
    private final AgentService agents;
    private final ModelConfigService models;
    private final TimbreService timbres;
    private final DeviceService devices;
    private final PublicConversationAuthService auth;

    public PublicConversationResourceController(AgentService agents, ModelConfigService models,
            TimbreService timbres, DeviceService devices) {
        this(agents, models, timbres, devices, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PublicConversationResourceController(AgentService agents, ModelConfigService models,
            TimbreService timbres, DeviceService devices, PublicConversationAuthService auth) {
        this.agents = agents;
        this.models = models;
        this.timbres = timbres;
        this.devices = devices;
        this.auth = auth;
    }

    @GetMapping("/agents")
    @RequiresPermissions("sys:role:normal")
    public Result<List<AgentDTO>> agents(@RequestParam(defaultValue = "") String keyword) {
        PublicConversationAuthService.AuthenticatedCaller caller = requireResourceRead();
        List<AgentDTO> result = agents.getUserAgents(SecurityUser.getUserId(), keyword, "name");
        if (caller != null && caller.apiKey() && !caller.agentIds().isEmpty()) {
            result = result.stream().filter(agent -> caller.agentIds().contains(agent.getId())).toList();
        }
        return new Result<List<AgentDTO>>().ok(result);
    }

    @GetMapping("/models")
    @RequiresPermissions("sys:role:normal")
    public Result<List<Map<String, Object>>> models(@RequestParam String type) {
        requireResourceRead();
        List<Map<String, Object>> result = models.getEnabledModelsByType(type).stream()
                .map(PublicConversationResourceController::publicModel)
                .toList();
        return new Result<List<Map<String, Object>>>().ok(result);
    }

    @GetMapping("/voices")
    @RequiresPermissions("sys:role:normal")
    public Result<PageData<TimbreDetailsVO>> voices(@RequestParam String ttsModelId,
            @RequestParam(defaultValue = "1") String page, @RequestParam(defaultValue = "20") String limit) {
        requireResourceRead();
        TimbrePageDTO request = new TimbrePageDTO();
        request.setTtsModelId(ttsModelId);
        request.setPage(page);
        request.setLimit(limit);
        return new Result<PageData<TimbreDetailsVO>>().ok(timbres.page(request));
    }

    @GetMapping("/devices")
    @RequiresPermissions("sys:role:normal")
    public Result<List<UserShowDeviceListVO>> devices() {
        requireResourceRead();
        return new Result<List<UserShowDeviceListVO>>().ok(devices.getUserDeviceList(SecurityUser.getUserId(), null));
    }

    private PublicConversationAuthService.AuthenticatedCaller requireResourceRead() {
        if (auth == null) return null;
        PublicConversationAuthService.AuthenticatedCaller caller = auth.current();
        if (caller.apiKey()) auth.requireScope(caller, "resource:read");
        return caller;
    }

    private static Map<String, Object> publicModel(ModelConfigEntity model) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", model.getId());
        result.put("type", model.getModelType());
        result.put("modelCode", model.getModelCode());
        result.put("modelName", model.getModelName());
        result.put("enabled", model.getIsEnabled());
        result.put("docLink", model.getDocLink());
        result.put("remark", model.getRemark());
        result.put("sort", model.getSort());
        return result;
    }
}
