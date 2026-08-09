package xiaozhi.modules.companion.dao;

import org.apache.ibatis.annotations.Mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import xiaozhi.modules.companion.entity.CompanionAuditEntity;

@Mapper
public interface CompanionAuditDao extends BaseMapper<CompanionAuditEntity> {
}
