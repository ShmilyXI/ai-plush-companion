package zixuan.modules.companion.capability.dao;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import zixuan.modules.companion.capability.entity.CapabilitySecretEntity;

@Mapper
public interface CapabilitySecretDao extends BaseMapper<CapabilitySecretEntity> {
    @Select("SELECT * FROM ai_capability_secret WHERE capability_id=#{capabilityId} AND secret_name=#{secretName} LIMIT 1")
    CapabilitySecretEntity selectByCapabilityAndName(@Param("capabilityId") String capabilityId,
            @Param("secretName") String secretName);

    @Select("SELECT * FROM ai_capability_secret WHERE capability_id=#{capabilityId} ORDER BY secret_name ASC")
    List<CapabilitySecretEntity> selectByCapabilityId(@Param("capabilityId") String capabilityId);
}
