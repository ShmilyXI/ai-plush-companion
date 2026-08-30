package xiaozhi.modules.appauth;

/** Compatibility facade for tests and callers that used the original package. */
public class AppAuthController extends xiaozhi.modules.appauth.controller.AppAuthController {
    public AppAuthController(AppAuthService service) { super(service); }
}
