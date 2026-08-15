package xiaozhi.modules.companion.capability.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import xiaozhi.modules.companion.capability.entity.CapabilityVersionEntity;

@Mapper
public interface CapabilityVersionDao extends BaseMapper<CapabilityVersionEntity> {
    @Select("SELECT MAX(version_no) FROM ai_capability_version WHERE capability_id=#{capabilityId}")
    Integer selectMaxVersion(@Param("capabilityId") String capabilityId);

    @Select("SELECT * FROM ai_capability_version WHERE capability_id=#{capabilityId} AND version_no=#{versionNo} LIMIT 1")
    CapabilityVersionEntity selectVersion(@Param("capabilityId") String capabilityId,
            @Param("versionNo") Integer versionNo);
}
