package xiaozhi.modules.device.dao;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import xiaozhi.modules.device.entity.OtaEntity;

/**
 * OTA固件管理
 */
@Mapper
public interface OtaDao extends BaseMapper<OtaEntity> {
    @Select("SELECT * FROM ai_ota WHERE normalized_type_key = #{normalizedType} FOR UPDATE")
    OtaEntity selectByNormalizedTypeForUpdate(@Param("normalizedType") String normalizedType);

    @Select("SELECT * FROM ai_ota WHERE id = #{id} FOR UPDATE")
    OtaEntity selectByIdForUpdate(@Param("id") String id);
}
