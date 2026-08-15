package xiaozhi.modules.companion.capability.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import xiaozhi.modules.companion.capability.entity.CapabilityEntity;

@Mapper
public interface CapabilityDao extends BaseMapper<CapabilityEntity> {
    @Select("SELECT * FROM ai_capability WHERE id=#{id} AND deleted=0 LIMIT 1 FOR UPDATE")
    CapabilityEntity selectForUpdate(@Param("id") String id);
}
