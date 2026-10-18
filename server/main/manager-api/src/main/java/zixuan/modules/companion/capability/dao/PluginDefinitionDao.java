package zixuan.modules.companion.capability.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import zixuan.modules.companion.capability.entity.PluginDefinitionEntity;

@Mapper
public interface PluginDefinitionDao extends BaseMapper<PluginDefinitionEntity> {
    @Select("SELECT * FROM ai_plugin_definition WHERE capability_id=#{capabilityId} LIMIT 1")
    PluginDefinitionEntity selectByCapabilityId(@Param("capabilityId") String capabilityId);
}
