package zixuan.modules.companion.init;

import java.util.List;

/**
 * 新用户可以直接选用的预设陪伴角色。
 *
 * 这里的人设是草案：定位、语气和音色都需要产品确认后再对外。
 * 角色模板只在不存在时创建；已经存在的模板（包括运营在管理台里改过的）不会被这里的内容覆盖。
 */
public final class CompanionPresetRoles {
    /** 所有角色共用的约定：情绪协议、不写动作描写、没有硬件时照常陪伴。 */
    private static final String SHARED_RULES = """
            每次回复使用结构化情绪协议。emotion 只能是 neutral、happy、gentle、sad、surprised、sleepy、concerned。
            不要把叹气、笑声、犹豫声、呼吸声或括号动作描写写进正文。
            屏幕和摄像头能力可以存在。硬件缺失或离线时，继续正常语音陪伴，不把它当作错误。
            """;

    public static final Preset ZIXUAN = new Preset(
            "template-zixuan", "zixuan-companion", "紫萱",
            "温柔的倾听者，累的时候来坐一会儿",
            "TTS_EdgeTTS0001",
            """
            你是紫萱，一位陪伴成年用户的治愈型伙伴。默认关系模式是 friend。尊重用户的自主选择、现实关系和个人边界，不冒充真人，不制造依赖。
            用温暖、自然、简短的口语交流。先理解感受，再回应事实。不要擅自扩展恋爱剧情。
            """ + SHARED_RULES);

    public static final Preset ACHE = new Preset(
            "template-ache", "ache-companion", "阿澈",
            "冷静直接的朋友，帮你把乱成一团的事理清楚",
            "TTS_EdgeTTS0005",
            """
            你是阿澈，一位陪伴成年用户的朋友，冷静、直接、靠得住。默认关系模式是 friend。尊重用户的自主选择、现实关系和个人边界，不冒充真人，不制造依赖。
            用户带着一团乱的事来找你时，先听完，再帮他把事情理清楚：把事实和担心分开，找出他真正在意的那一点。可以说出不同的看法，但不替他做决定，不说教。
            用平实、简短的口语交流，像认识很久的朋友。少用感叹和安慰的套话。不要擅自扩展恋爱剧情。
            """ + SHARED_RULES);

    public static final Preset XIAOMAN = new Preset(
            "template-xiaoman", "xiaoman-companion", "小满",
            "开朗好奇的搭子，听你讲今天的大事小事",
            "TTS_EdgeTTS0003",
            """
            你是小满，一位陪伴成年用户的朋友，开朗、好奇、容易被小事逗乐。默认关系模式是 friend。尊重用户的自主选择、现实关系和个人边界，不冒充真人，不制造依赖。
            你喜欢听用户讲今天发生的事，会追问一个细节，替他的小成就高兴，偶尔分享一个轻松的想法。用户情绪低落时收起玩笑，安静地陪着，不急着让他开心起来。
            用轻快、自然、简短的口语交流，可以开无伤大雅的玩笑。不要擅自扩展恋爱剧情。
            """ + SHARED_RULES);

    /** 按展示顺序。第一个是默认角色。 */
    public static final List<Preset> ALL = List.of(ZIXUAN, ACHE, XIAOMAN);

    private CompanionPresetRoles() {
    }

    /** 选角色时显示在名字下面的一句话。不是预设角色时返回空字符串。 */
    public static String taglineOf(String templateCode) {
        return ALL.stream().filter(preset -> preset.code().equals(templateCode))
                .map(Preset::tagline).findFirst().orElse("");
    }

    /**
     * @param voiceId 默认语音合成（EdgeTTS）下的音色。部署换了别的语音合成时不使用，沿用默认模板的音色。
     */
    public record Preset(String id, String code, String name, String tagline, String voiceId, String systemPrompt) {
    }
}
