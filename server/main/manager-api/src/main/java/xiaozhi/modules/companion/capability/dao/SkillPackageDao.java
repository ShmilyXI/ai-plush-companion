package xiaozhi.modules.companion.capability.dao;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import xiaozhi.modules.companion.capability.entity.SkillPackageEntity;

@Mapper
public interface SkillPackageDao extends BaseMapper<SkillPackageEntity> {
    @Select("SELECT * FROM ai_skill_package WHERE capability_id=#{capabilityId} AND version_no=#{versionNo} LIMIT 1")
    SkillPackageEntity selectByVersion(@Param("capabilityId") String capabilityId,
            @Param("versionNo") Integer versionNo);

    @Select("SELECT * FROM ai_skill_package WHERE capability_id=#{capabilityId} AND published=0 ORDER BY version_no DESC LIMIT 1")
    SkillPackageEntity selectDraft(@Param("capabilityId") String capabilityId);

    @Select("SELECT * FROM ai_skill_package WHERE capability_id=#{capabilityId} ORDER BY version_no DESC")
    List<SkillPackageEntity> selectByCapabilityId(@Param("capabilityId") String capabilityId);
}
