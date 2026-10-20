package zixuan.modules.companion.screen.controller;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.imageio.ImageIO;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import zixuan.common.constant.Constant;
import zixuan.common.exception.RenException;
import zixuan.common.utils.Result;
import zixuan.modules.companion.screen.dao.ScreenSceneDao;
import zixuan.modules.companion.screen.entity.ScreenSceneEntity;
import zixuan.modules.security.user.SecurityUser;
import zixuan.modules.device.entity.DeviceEntity;
import zixuan.modules.device.service.DeviceService;
import zixuan.modules.sys.service.SysParamsService;

@RestController
@RequiredArgsConstructor
@RequestMapping("/companion/screen-scenes")
public class ScreenSceneController {
    private final ScreenSceneDao sceneDao;
    private final DeviceService deviceService;
    private final SysParamsService sysParamsService;
    private static final Path IMAGE_DIR = Path.of("uploadfile", "screen-assets");
    private static final String RESOURCE_PATH = "/companion/screen-scenes/resources/";
    private static final Pattern RESOURCE_ID = Pattern.compile(
            "(?:^screen-asset:|/companion/screen-scenes/resources/)([0-9a-fA-F-]{36})$");

    @GetMapping("/devices/{deviceId}")
    @RequiresPermissions("sys:role:normal")
    public Result<List<ScreenSceneEntity>> list(@PathVariable String deviceId) {
        requireOwned(deviceId);
        return new Result<List<ScreenSceneEntity>>().ok(sceneDao.selectByDevice(SecurityUser.getUserId(), deviceId));
    }

    @PostMapping
    @RequiresPermissions("sys:role:normal")
    public Result<ScreenSceneEntity> save(@RequestBody @Valid SceneInput input) {
        requireOwned(input.getDeviceId());
        ScreenSceneEntity entity = new ScreenSceneEntity();
        entity.setUserId(SecurityUser.getUserId());
        entity.setDeviceId(input.getDeviceId());
        entity.setSceneId(input.getSceneId());
        entity.setVersion(input.getVersion() == null ? 1 : input.getVersion());
        entity.setMode(input.getMode());
        entity.setSceneJson(input.getSceneJson());
        entity.setStatus("DRAFT");
        entity.setCreatedAt(new Date());
        entity.setUpdatedAt(new Date());
        if (sceneDao.insert(entity) != 1) throw new RenException("场景草稿保存失败");
        return new Result<ScreenSceneEntity>().ok(entity);
    }

    @PostMapping("/{id}/publish")
    @RequiresPermissions("sys:role:normal")
    public Result<ScreenSceneEntity> publish(@PathVariable String id) {
        ScreenSceneEntity entity = sceneDao.selectById(id);
        if (entity == null || !SecurityUser.getUserId().equals(entity.getUserId())) throw new RenException("场景不存在");
        entity.setStatus("PUBLISHED");
        entity.setVersion(entity.getVersion() == null ? 1 : entity.getVersion() + 1);
        entity.setUpdatedAt(new Date());
        sceneDao.updateById(entity);
        if (deviceService.isOnline(entity.getDeviceId())) {
            deviceService.callDeviceTool(entity.getDeviceId(), "self.screen.set_mode", Map.of("mode", entity.getMode()));
            applySceneLayers(entity.getDeviceId(), entity.getSceneJson());
        }
        return new Result<ScreenSceneEntity>().ok(entity);
    }

    @PostMapping(value = "/resources", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RequiresPermissions("sys:role:normal")
    public Result<Map<String, Object>> upload(@RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "360") int width, @RequestParam(defaultValue = "360") int height) {
        if (file.isEmpty() || file.getSize() > 10 * 1024 * 1024) throw new RenException("图片为空或超过 10MB");
        try {
            BufferedImage source = ImageIO.read(file.getInputStream());
            if (source == null) throw new RenException("不支持的图片格式");
            int w = Math.max(1, Math.min(width, 4096));
            int h = Math.max(1, Math.min(height, 4096));
            // Fit inside the target box keeping the aspect ratio; the rest stays black, matching the
            // panel background and firmware/scripts/convert_screen_image.py.
            double scale = Math.min((double) w / source.getWidth(), (double) h / source.getHeight());
            int fitW = Math.max(1, (int) Math.round(source.getWidth() * scale));
            int fitH = Math.max(1, (int) Math.round(source.getHeight() * scale));
            BufferedImage scaled = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = scaled.createGraphics();
            graphics.drawImage(source.getScaledInstance(fitW, fitH, Image.SCALE_SMOOTH), (w - fitW) / 2, (h - fitH) / 2, null);
            graphics.dispose();
            ByteArrayOutputStream output = new ByteArrayOutputStream(12 + w * h * 2);
            output.write(new byte[] { 0x19, 0x04, 0, 0, (byte) (w & 0xff), (byte) ((w >>> 8) & 0xff),
                    (byte) (h & 0xff), (byte) ((h >>> 8) & 0xff), (byte) ((w * 2) & 0xff), (byte) (((w * 2) >>> 8) & 0xff), 0, 0 });
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
                int rgb = scaled.getRGB(x, y);
                int r = (rgb >>> 16) & 0xff, g = (rgb >>> 8) & 0xff, b = rgb & 0xff;
                int value = ((r >>> 3) << 11) | ((g >>> 2) << 5) | (b >>> 3);
                output.write(value & 0xff); output.write((value >>> 8) & 0xff);
            }
            String id = UUID.randomUUID().toString();
            Files.createDirectories(IMAGE_DIR);
            Files.write(IMAGE_DIR.resolve(id + ".bin"), output.toByteArray(), StandardOpenOption.CREATE_NEW);
            return new Result<Map<String, Object>>().ok(Map.of("id", id, "asset", id + ".bin", "width", w, "height", h,
                    "resource", "screen-asset:" + id,
                    "url", "/zixuan" + RESOURCE_PATH + id));
        } catch (IOException exception) {
            throw new RenException("图片转换失败", exception);
        }
    }

    @GetMapping("/resources/{id}")
    @ResponseBody
    public ResponseEntity<Resource> download(@PathVariable String id) {
        if (!id.matches("[0-9a-fA-F-]{36}")) return ResponseEntity.notFound().build();
        FileSystemResource resource = new FileSystemResource(IMAGE_DIR.resolve(id + ".bin"));
        if (!resource.exists()) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).body(resource);
    }

    private void requireOwned(String deviceId) {
        boolean owned = deviceService.getUserDevices(SecurityUser.getUserId()).stream()
                .map(DeviceEntity::getId).anyMatch(deviceId::equals);
        if (!owned) throw new RenException("设备不存在或无权限");
    }

    private void applySceneLayers(String deviceId, String sceneJson) {
        try {
            JSONArray layers = JSONUtil.parseObj(sceneJson).getJSONArray("layers");
            if (layers == null) return;
            for (Object value : layers) {
                JSONObject layer = value instanceof JSONObject object ? object : JSONUtil.parseObj(value);
                String resource = layer.getStr("resource");
                String type = layer.getStr("type");
                String imageUrl = "image".equals(type) ? deviceImageUrl(resource) : null;
                if (imageUrl != null) {
                    deviceService.callDeviceTool(deviceId, "self.screen.set_image_url", Map.of("url", imageUrl));
                } else if ("animation".equals(type) && resource != null && resource.endsWith(".eaf")) {
                    deviceService.callDeviceTool(deviceId, "self.screen.set_emotion", Map.of("emotion", emotionName(resource)));
                } else if ("text".equals(type) && layer.getStr("text") != null) {
                    deviceService.callDeviceTool(deviceId, "self.screen.set_text", Map.of("text", layer.getStr("text")));
                }
            }
        } catch (RuntimeException ignored) {
            // Schema validation reports malformed scenes before publication in normal flows.
        }
    }

    /** "assets/neutral.eaf" -> "neutral": the device expects a bare emotion name. */
    private static String emotionName(String resource) {
        String name = resource.substring(resource.lastIndexOf('/') + 1);
        return name.substring(0, name.length() - ".eaf".length());
    }

    /**
     * Builds the URL the device downloads an uploaded image from. The device only accepts images
     * on the same origin as its OTA server, so the URL is derived from {@code server.ota} instead of
     * whatever host the console happened to be opened on.
     */
    private String deviceImageUrl(String resource) {
        if (resource == null) return null;
        Matcher matcher = RESOURCE_ID.matcher(resource.trim());
        if (!matcher.find()) return null;
        String otaUrl = sysParamsService.getValue(Constant.SERVER_OTA, true);
        if (otaUrl == null || otaUrl.isBlank()) throw new RenException("未配置 server.ota，无法生成设备图片地址");
        String base = otaUrl.trim().replaceAll("/+$", "");
        if (base.endsWith("/ota")) base = base.substring(0, base.length() - "/ota".length());
        return base + RESOURCE_PATH + matcher.group(1);
    }

    @Data
    public static class SceneInput {
        @NotBlank private String deviceId;
        @NotBlank private String sceneId;
        @NotBlank private String mode;
        @NotBlank private String sceneJson;
        private Integer version;
    }
}
