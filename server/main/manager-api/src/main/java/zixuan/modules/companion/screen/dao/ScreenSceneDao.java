package zixuan.modules.companion.screen.dao;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import zixuan.modules.companion.screen.entity.ScreenSceneEntity;

@Mapper
public interface ScreenSceneDao extends BaseMapper<ScreenSceneEntity> {
    @Select("SELECT * FROM ai_screen_scene WHERE user_id=#{userId} AND device_id=#{deviceId} ORDER BY updated_at DESC")
    List<ScreenSceneEntity> selectByDevice(Long userId, String deviceId);
}
