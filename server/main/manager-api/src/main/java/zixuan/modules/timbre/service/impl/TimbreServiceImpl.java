package zixuan.modules.timbre.service.impl;

import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;

import cn.hutool.core.collection.CollectionUtil;
import lombok.AllArgsConstructor;
import zixuan.common.constant.Constant;
import zixuan.common.exception.ErrorCode;
import zixuan.common.page.PageData;
import zixuan.common.redis.RedisKeys;
import zixuan.common.redis.RedisUtils;
import zixuan.common.service.impl.BaseServiceImpl;
import zixuan.common.utils.ConvertUtils;
import zixuan.common.utils.MessageUtils;
import zixuan.modules.model.dto.VoiceDTO;
import zixuan.modules.security.user.SecurityUser;
import zixuan.modules.timbre.dao.TimbreDao;
import zixuan.modules.timbre.dto.TimbreDataDTO;
import zixuan.modules.timbre.dto.TimbrePageDTO;
import zixuan.modules.timbre.entity.TimbreEntity;
import zixuan.modules.timbre.service.TimbreService;
import zixuan.modules.timbre.vo.TimbreDetailsVO;
import zixuan.modules.voiceclone.dao.VoiceCloneDao;
import zixuan.modules.voiceclone.entity.VoiceCloneEntity;

/**
 * 音色的业务层的实现
 * 
 * @author zjy
 * @since 2025-3-21
 */
@AllArgsConstructor
@Service
public class TimbreServiceImpl extends BaseServiceImpl<TimbreDao, TimbreEntity> implements TimbreService {

    private static final Pattern LANGUAGE_SEPARATOR = Pattern.compile("[、；;,，]");

    private final TimbreDao timbreDao;
    private final VoiceCloneDao voiceCloneDao;
    private final RedisUtils redisUtils;

    @Override
    public PageData<TimbreDetailsVO> page(TimbrePageDTO dto) {
        Map<String, Object> params = new HashMap<String, Object>();
        params.put(Constant.PAGE, dto.getPage());
        params.put(Constant.LIMIT, dto.getLimit());
        IPage<TimbreEntity> page = baseDao.selectPage(
                getPage(params, null, true),
                // 定义查询条件
                new QueryWrapper<TimbreEntity>()
                        // 必须按照ttsID查找
                        .eq("tts_model_id", dto.getTtsModelId())
                        // 如果有音色名字，按照音色名模糊查找
                        .like(StringUtils.isNotBlank(dto.getName()), "name", dto.getName()));

        return getPageData(page, TimbreDetailsVO.class);
    }

    @Override
    public TimbreDetailsVO get(String timbreId) {
        if (StringUtils.isBlank(timbreId)) {
            return null;
        }

        // 先从Redis获取缓存
        String key = RedisKeys.getTimbreDetailsKey(timbreId);
        TimbreDetailsVO cachedDetails = (TimbreDetailsVO) redisUtils.get(key);
        if (cachedDetails != null) {
            return cachedDetails;
        }

        // 如果缓存中没有，则从数据库获取
        TimbreEntity entity = baseDao.selectById(timbreId);
        if (entity == null) {
            return null;
        }

        // 转换为VO对象
        TimbreDetailsVO details = ConvertUtils.sourceToTarget(entity, TimbreDetailsVO.class);

        // 存入Redis缓存
        if (details != null) {
            redisUtils.set(key, details);
        }

        return details;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean save(TimbreDataDTO dto) {
        isTtsModelId(dto.getTtsModelId());
        TimbreEntity timbreEntity = ConvertUtils.sourceToTarget(dto, TimbreEntity.class);
        return baseDao.insert(timbreEntity) == 1;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean update(String timbreId, TimbreDataDTO dto) {
        isTtsModelId(dto.getTtsModelId());
        TimbreEntity timbreEntity = ConvertUtils.sourceToTarget(dto, TimbreEntity.class);
        timbreEntity.setId(timbreId);
        boolean updated = baseDao.updateById(timbreEntity) == 1;
        // 删除缓存
        redisUtils.delete(RedisKeys.getTimbreDetailsKey(timbreId));
        return updated;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(String[] ids) {
        baseDao.deleteByIds(Arrays.asList(ids));
    }

    @Override
    public List<VoiceDTO> getVoiceNames(String ttsModelId, String voiceName) {
        QueryWrapper<TimbreEntity> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("tts_model_id", StringUtils.isBlank(ttsModelId) ? "" : ttsModelId);
        if (StringUtils.isNotBlank(voiceName)) {
            queryWrapper.like("name", voiceName);
        }
        List<TimbreEntity> timbreEntities = Optional.ofNullable(timbreDao.selectList(queryWrapper)).orElseGet(ArrayList::new);
        List<VoiceDTO> voiceDTOs = timbreEntities.stream()
                .map(entity -> {
                    VoiceDTO dto = new VoiceDTO(entity.getId(), entity.getName());
                    dto.setVoiceDemo(entity.getVoiceDemo());
                    dto.setLanguages(entity.getLanguages()); // 设置语言类型
                    dto.setIsClone(false); // 设置为普通音色
                    return dto;
                })
                .collect(Collectors.toList());

        // 获取当前登录用户ID
        Long currentUserId = SecurityUser.getUser().getId();
        if (currentUserId != null) {
            // 查询用户的所有克隆音色记录
            List<VoiceDTO> cloneEntities = voiceCloneDao.getTrainSuccess(ttsModelId, currentUserId);
            for (VoiceDTO entity : cloneEntities) {
                // 只添加训练成功的克隆音色，且模型ID匹配
                VoiceDTO voiceDTO = new VoiceDTO();
                voiceDTO.setId(entity.getId());
                voiceDTO.setName(MessageUtils.getMessage(ErrorCode.VOICE_CLONE_PREFIX) + entity.getName());
                // 保留从数据库查询到的voiceDemo字段
                voiceDTO.setVoiceDemo(entity.getVoiceDemo());
                voiceDTO.setLanguages(entity.getLanguages());
                voiceDTO.setIsClone(true); // 设置为克隆音色
                redisUtils.set(RedisKeys.getTimbreNameById(voiceDTO.getId()), voiceDTO.getName(),
                        RedisUtils.NOT_EXPIRE);
                voiceDTOs.add(0, voiceDTO);
            }
        }

        return CollectionUtil.isEmpty(voiceDTOs) ? null : voiceDTOs;
    }

    @Override
    public String getDefaultLanguageById(String id) {
        if (StringUtils.isBlank(id)) {
            return null;
        }

        TimbreEntity timbre = timbreDao.selectById(id);
        if (timbre != null) {
            return firstNonBlankLanguage(timbre.getLanguages());
        }

        VoiceCloneEntity voiceClone = voiceCloneDao.selectById(id);
        return voiceClone == null ? null : firstNonBlankLanguage(voiceClone.getLanguages());
    }

    @Override
    public String getDefaultLanguage(String languages) {
        return firstNonBlankLanguage(languages);
    }

    @Override
    public boolean hasTimbresForModel(String ttsModelId) {
        if (StringUtils.isBlank(ttsModelId)) {
            return false;
        }
        Long count = timbreDao.selectCount(new QueryWrapper<TimbreEntity>().eq("tts_model_id", ttsModelId));
        return count != null && count > 0;
    }

    private String firstNonBlankLanguage(String languages) {
        if (StringUtils.isBlank(languages)) {
            return null;
        }
        return LANGUAGE_SEPARATOR.splitAsStream(languages)
                .map(StringUtils::trimToNull)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    /**
     * 处理是不是tts模型的id
     */
    private void isTtsModelId(String ttsModelId) {
        // 等模型配置那边写好调用方法判断
    }

    @Override
    public String getTimbreNameById(String id) {
        if (StringUtils.isBlank(id)) {
            return null;
        }

        String cachedName = (String) redisUtils.get(RedisKeys.getTimbreNameById(id));

        if (StringUtils.isNotBlank(cachedName)) {
            return cachedName;
        }

        TimbreEntity entity = timbreDao.selectById(id);
        if (entity != null) {
            String name = entity.getName();
            if (StringUtils.isNotBlank(name)) {
                redisUtils.set(RedisKeys.getTimbreNameById(id), name);
            }
            return name;
        } else {
            VoiceCloneEntity cloneEntity = voiceCloneDao.selectById(id);
            if (cloneEntity != null) {
                String name = MessageUtils.getMessage(ErrorCode.VOICE_CLONE_PREFIX) + cloneEntity.getName();
                redisUtils.set(RedisKeys.getTimbreNameById(id), name);
                return name;
            }
        }

        return null;
    }

    @Override
    public Map<String, String> getTimbreNamesByIds(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        Map<String, String> names = new HashMap<>();
        for (TimbreEntity timbre : timbreDao.selectList(new QueryWrapper<TimbreEntity>().in("id", ids))) {
            names.put(timbre.getId(), timbre.getName());
        }
        Set<String> missingIds = ids.stream().filter(id -> !names.containsKey(id)).collect(Collectors.toSet());
        if (!missingIds.isEmpty()) {
            for (VoiceCloneEntity clone : voiceCloneDao.selectList(
                    new QueryWrapper<VoiceCloneEntity>().in("id", missingIds))) {
                names.put(clone.getId(), MessageUtils.getMessage(ErrorCode.VOICE_CLONE_PREFIX) + clone.getName());
            }
        }
        return names;
    }

    @Override
    public VoiceDTO getByVoiceCode(String ttsModelId, String voiceCode) {
        if (StringUtils.isBlank(voiceCode)) {
            return null;
        }
        QueryWrapper<TimbreEntity> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("tts_model_id", ttsModelId);
        queryWrapper.eq("tts_voice", voiceCode);
        List<TimbreEntity> list = timbreDao.selectList(queryWrapper);
        if (list.isEmpty()) {
            return null;
        }
        TimbreEntity entity = list.get(0);
        VoiceDTO dto = new VoiceDTO(entity.getId(), entity.getName());
        dto.setVoiceDemo(entity.getVoiceDemo());
        dto.setIsClone(false); // 设置为普通音色
        return dto;
    }
}
