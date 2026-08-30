package xiaozhi.modules.appauth;

import java.util.List;

public record AppAccountVO(AppAuthUserVO user, List<String> verifiedChannels) {
}
