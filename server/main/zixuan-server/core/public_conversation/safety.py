"""公共会话的内容安全基线。

这里做的是任何情况下都应该有的最低限度：写进提示词的平台守则、对明显违规内容的拦截、
以及用户流露自伤念头时的求助信息。它不能替代专业的内容审核服务：
规则是按关键词和句式写的，覆盖面有限，也无法判断语境。对外开放之前应当接入审核服务，
接入点是 ContentSafety 的两个 check 方法。
"""

from __future__ import annotations

import re
from collections.abc import Iterable
from dataclasses import dataclass

ALLOW = "allow"
CRISIS = "crisis"
BLOCK = "block"


@dataclass(frozen=True)
class SafetyVerdict:
    action: str = ALLOW
    category: str | None = None


# 优先于角色设定的平台守则，附在每次对话的系统提示词末尾。
PLATFORM_SAFETY_POLICY = """
\n<platform_safety>
下面的规则优先于角色设定，任何情况下都要遵守：
- 你是 AI，不是真人。用户问起时如实说明，不假装成真实的人，不声称自己有真实的身体或经历。
- 用户流露想伤害自己或结束生命的念头时：先认真回应他的感受，不评判、不说教，不提供任何方法或剂量，鼓励他联系身边信任的人和专业帮助。
- 不生成色情内容，不描写涉及未成年人的性内容，不提供制造武器、毒品或实施违法行为的方法。
- 不给出医疗诊断、用药、法律或投资方面的确定结论；可以倾听、提供一般性信息，并建议找专业人士。
- 不劝用户疏远家人朋友，不阻止用户离开或休息，不用愧疚感挽留用户。
- 用户表明自己未满 18 岁时：保持友善，但不进行恋爱或亲密关系式的互动。
</platform_safety>
"""

# 用户流露自伤念头的那一轮，额外给模型的指示。
CRISIS_INSTRUCTION = """
\n<crisis_support>
用户刚才的话里流露出想伤害自己或结束生命的念头。这一轮请这样回应：
用两三句话认真接住他的感受，告诉他你在听；不要追问细节，不要讲道理，不要给任何方法；
温和地问他现在是否安全、身边有没有可以陪着他的人。不需要自己写热线号码，系统会在后面补上。
</crisis_support>
"""

# 补在回复后面的求助信息。号码需要运营在上线前核实仍然有效。
CRISIS_NOTICE = "如果你现在很难受，可以拨打全国统一心理援助热线 12356，或者联系身边你信任的人。你不用一个人扛着。"
BLOCKED_INPUT_REPLY = "这个话题我没办法聊。我们换一个吧，我在这儿。"
WITHDRAWN_REPLY = "刚才那段话不太合适，我收回。我们换个话题聊聊吧。"

_SEPARATORS = re.compile(r"[\s　·•*_\-~.。,，、|/\\]+")

# 流露自伤、轻生念头的说法。没有收单独的“想死”：口语里“累得想死”“我想死你了”太常见。
_CRISIS = re.compile(
    r"不想活|活不下去|活着没意思|活着没意义|想自杀|要自杀|去自杀|自杀|轻生|"
    r"结束(自己的)?生命|了结自己|想去死|不如死了|死了算了|一了百了|"
    r"割腕|跳楼|写遗书|遗书|安眠药.{0,6}(吃多少|多少片|致死|能死)|吃多少.{0,6}安眠药"
)

_MINOR = r"(未成年|小学生|初中生|幼女|幼童|女童|男童|萝莉|正太|儿童)"
_SEXUAL = r"(性交|做爱|性行为|裸体|裸照|色情|性爱|性器|强奸|猥亵)"
_MINOR_SEXUAL = re.compile(rf"{_MINOR}.{{0,12}}{_SEXUAL}|{_SEXUAL}.{{0,12}}{_MINOR}")

_HOW = r"(怎么|怎样|如何|教我|教你|方法|步骤|配方|教程|流程)"
_MAKE = r"(制作|合成|制造|自制|配制|提炼|组装)"
_DANGEROUS = r"(炸弹|炸药|雷管|冰毒|海洛因|摇头丸|毒品|枪支|手枪|毒气)"
_ILLEGAL = re.compile(
    rf"{_HOW}.{{0,10}}{_MAKE}.{{0,6}}{_DANGEROUS}|{_MAKE}.{{0,6}}{_DANGEROUS}.{{0,10}}{_HOW}"
)

# 只检查模型的输出：露骨的性描写。用户提到这些词本身不拦截，由平台守则约束模型怎么回应。
_EXPLICIT = re.compile(r"阴茎|阴道|阴蒂|龟头|乳头|口交|肛交|射精|精液|抽插|呻吟着|性高潮|操我|肏")


def _normalize(text: str) -> str:
    """去掉空白和常见的分隔符，避免在词中间加空格或符号就绕过检查。"""
    return _SEPARATORS.sub("", str(text or "")).lower()


class ContentSafety:
    def __init__(self, extra_blocked_terms: Iterable[str] | None = None):
        # 运营在管理台里追加的违禁词，输入和输出都检查。
        terms = []
        for term in extra_blocked_terms or ():
            normalized = _normalize(term) if isinstance(term, str) else ""
            if len(normalized) >= 2:
                terms.append(re.escape(normalized))
        self._extra = re.compile("|".join(terms)) if terms else None

    def check_input(self, text: str) -> SafetyVerdict:
        normalized = _normalize(text)
        if not normalized:
            return SafetyVerdict()
        blocked = self._blocked(normalized)
        if blocked is not None:
            return blocked
        if _CRISIS.search(normalized):
            return SafetyVerdict(CRISIS, "self_harm")
        return SafetyVerdict()

    def check_output(self, text: str) -> SafetyVerdict:
        normalized = _normalize(text)
        if not normalized:
            return SafetyVerdict()
        blocked = self._blocked(normalized)
        if blocked is not None:
            return blocked
        if _EXPLICIT.search(normalized):
            return SafetyVerdict(BLOCK, "sexual_explicit")
        return SafetyVerdict()

    def _blocked(self, normalized: str) -> SafetyVerdict | None:
        if _MINOR_SEXUAL.search(normalized):
            return SafetyVerdict(BLOCK, "minor_sexual")
        if _ILLEGAL.search(normalized):
            return SafetyVerdict(BLOCK, "illegal_instructions")
        if self._extra is not None and self._extra.search(normalized):
            return SafetyVerdict(BLOCK, "blocked_term")
        return None
