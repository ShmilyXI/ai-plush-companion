package xiaozhi.modules.companion.model.dao;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import xiaozhi.modules.companion.model.entity.CompanionGlobalModelCredentialEntity;

@Mapper
public interface CompanionGlobalModelCredentialDao extends BaseMapper<CompanionGlobalModelCredentialEntity> {
    @Select("SELECT * FROM ai_companion_global_model_credential "
            + "WHERE user_id=#{userId} AND global_model_id=#{globalModelId} LIMIT 1")
    CompanionGlobalModelCredentialEntity selectOwned(@Param("userId") Long userId,
            @Param("globalModelId") String globalModelId);

    @Insert("INSERT INTO ai_companion_global_model_credential "
            + "(id,user_id,global_model_id,api_url_override,model_id_override,secret_config_ciphertext,created_at,updated_at) "
            + "VALUES (#{id},#{userId},#{globalModelId},#{apiUrlOverride},#{modelIdOverride},"
            + "#{secretConfigCiphertext},#{createdAt},#{updatedAt}) "
            + "ON DUPLICATE KEY UPDATE api_url_override=VALUES(api_url_override),"
            + "model_id_override=VALUES(model_id_override),secret_config_ciphertext=VALUES(secret_config_ciphertext),"
            + "updated_at=VALUES(updated_at)")
    int upsert(CompanionGlobalModelCredentialEntity entity);
}
