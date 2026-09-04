package zixuan.modules.timbre.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import zixuan.common.exception.ErrorCode;
import zixuan.common.redis.RedisUtils;
import zixuan.common.user.UserDetail;
import zixuan.common.utils.MessageUtils;
import zixuan.modules.model.dto.VoiceDTO;
import zixuan.modules.security.user.SecurityUser;
import zixuan.modules.timbre.dao.TimbreDao;
import zixuan.modules.timbre.entity.TimbreEntity;
import zixuan.modules.voiceclone.dao.VoiceCloneDao;
import zixuan.modules.voiceclone.entity.VoiceCloneEntity;

class TimbreServiceImplTest {

    @Test
    void voiceSelectionResponseContainsOnlySuccessfullyTrainedClones() throws Exception {
        UnpooledDataSource dataSource = new UnpooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:voice_selection_" + System.nanoTime() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("create table ai_voice_clone (id varchar(64), name varchar(64), voice_id varchar(64), "
                    + "languages varchar(64), model_id varchar(64), user_id bigint, train_status int)");
            statement.execute("insert into ai_voice_clone values "
                    + "('success-id','成功音色','success-demo','zh-CN','tts-1',42,2),"
                    + "('failed-id','失败音色','failed-demo','zh-CN','tts-1',42,3)");
        }
        Configuration configuration = new Configuration(
                new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        try (InputStream xml = Resources.getResourceAsStream("mapper/voiceclone/VoiceCloneDao.xml")) {
            new XMLMapperBuilder(xml, configuration, "mapper/voiceclone/VoiceCloneDao.xml",
                    configuration.getSqlFragments()).parse();
        }
        SqlSessionFactory sessionFactory = new SqlSessionFactoryBuilder().build(configuration);
        UserDetail user = new UserDetail();
        user.setId(42L);
        try (SqlSession session = sessionFactory.openSession();
                MockedStatic<SecurityUser> security = org.mockito.Mockito.mockStatic(SecurityUser.class);
                MockedStatic<MessageUtils> messages = org.mockito.Mockito.mockStatic(MessageUtils.class)) {
            security.when(SecurityUser::getUser).thenReturn(user);
            messages.when(() -> MessageUtils.getMessage(ErrorCode.VOICE_CLONE_PREFIX)).thenReturn("克隆-");
            TimbreDao timbreDao = mock(TimbreDao.class);
            when(timbreDao.selectList(any())).thenReturn(List.of());
            TimbreServiceImpl service = new TimbreServiceImpl(
                    timbreDao, session.getMapper(VoiceCloneDao.class), mock(RedisUtils.class));

            List<VoiceDTO> voices = service.getVoiceNames("tts-1", null);

            assertEquals(List.of("success-id"), voices.stream().map(VoiceDTO::getId).toList());
            assertEquals("克隆-成功音色", voices.get(0).getName());
            assertTrue(voices.get(0).getIsClone());
        }
    }

    @Test
    void defaultLanguageUsesFirstValidRegularTimbreLanguageWithoutCloneQuery() {
        TimbreDao timbreDao = mock(TimbreDao.class);
        VoiceCloneDao voiceCloneDao = mock(VoiceCloneDao.class);
        TimbreServiceImpl service = new TimbreServiceImpl(timbreDao, voiceCloneDao, mock(RedisUtils.class));
        TimbreEntity timbre = new TimbreEntity();
        timbre.setLanguages("，， ; 普通话；粤语");
        when(timbreDao.selectById("voice-id")).thenReturn(timbre);

        assertEquals("普通话", service.getDefaultLanguageById("voice-id"));

        verify(voiceCloneDao, never()).selectById("voice-id");
    }

    @Test
    void defaultLanguageFallsBackToCloneTimbre() {
        TimbreDao timbreDao = mock(TimbreDao.class);
        VoiceCloneDao voiceCloneDao = mock(VoiceCloneDao.class);
        TimbreServiceImpl service = new TimbreServiceImpl(timbreDao, voiceCloneDao, mock(RedisUtils.class));
        VoiceCloneEntity voiceClone = new VoiceCloneEntity();
        voiceClone.setLanguages("、, English，中文");
        when(voiceCloneDao.selectById("clone-id")).thenReturn(voiceClone);

        assertEquals("English", service.getDefaultLanguageById("clone-id"));
    }

    @Test
    void delimiterOnlyLanguageConfigurationReturnsNull() {
        TimbreDao timbreDao = mock(TimbreDao.class);
        VoiceCloneDao voiceCloneDao = mock(VoiceCloneDao.class);
        TimbreServiceImpl service = new TimbreServiceImpl(timbreDao, voiceCloneDao, mock(RedisUtils.class));
        TimbreEntity timbre = new TimbreEntity();
        timbre.setLanguages(",，、；;;,,");
        when(timbreDao.selectById("voice-id")).thenReturn(timbre);

        assertNull(service.getDefaultLanguageById("voice-id"));
    }

    @Test
    void defaultLanguageCanReuseAlreadyLoadedLanguageConfiguration() {
        TimbreDao timbreDao = mock(TimbreDao.class);
        VoiceCloneDao voiceCloneDao = mock(VoiceCloneDao.class);
        TimbreServiceImpl service = new TimbreServiceImpl(timbreDao, voiceCloneDao, mock(RedisUtils.class));

        assertEquals("English", service.getDefaultLanguage("、, English，中文"));

        verify(timbreDao, never()).selectById(any());
        verify(voiceCloneDao, never()).selectById(any());
    }

    @Test
    void regularTimbreExistenceUsesOnlyRegularTimbreTable() {
        TimbreDao timbreDao = mock(TimbreDao.class);
        VoiceCloneDao voiceCloneDao = mock(VoiceCloneDao.class);
        TimbreServiceImpl service = new TimbreServiceImpl(timbreDao, voiceCloneDao, mock(RedisUtils.class));
        when(timbreDao.selectCount(any())).thenReturn(1L, 0L);

        assertTrue(service.hasTimbresForModel("tts-1"));
        assertFalse(service.hasTimbresForModel("tts-2"));

        verify(voiceCloneDao, never()).getTrainSuccess(any(), any());
    }
}
