package xiaozhi.modules.device.dao;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import xiaozhi.modules.device.entity.CompanionMemoryMigrationEntity;

@Mapper
public interface CompanionMemoryMigrationDao extends BaseMapper<CompanionMemoryMigrationEntity> {
    @Select("SELECT * FROM ai_companion_memory_migration WHERE owner_id=#{ownerId} ORDER BY created_at DESC LIMIT #{limit}")
    List<CompanionMemoryMigrationEntity> selectRecentByOwner(@Param("ownerId") Long ownerId,
            @Param("limit") int limit);
}
