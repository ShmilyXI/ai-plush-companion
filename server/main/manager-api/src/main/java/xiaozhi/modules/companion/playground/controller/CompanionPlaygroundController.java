package xiaozhi.modules.companion.playground.controller;

import java.util.List;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.validation.Valid;
import xiaozhi.common.utils.Result;
import xiaozhi.modules.companion.playground.dto.PlaygroundInputDTO;
import xiaozhi.modules.companion.playground.dto.PlaygroundSessionCreateDTO;
import xiaozhi.modules.companion.playground.service.CompanionPlaygroundService;
import xiaozhi.modules.companion.playground.vo.PlaygroundEventVO;
import xiaozhi.modules.companion.playground.vo.PlaygroundSessionVO;
import xiaozhi.modules.security.user.SecurityUser;

@RestController
@RequestMapping("/companion/playground/sessions")
@RequiresPermissions("sys:role:normal")
public class CompanionPlaygroundController {
    private final CompanionPlaygroundService service;

    public CompanionPlaygroundController(CompanionPlaygroundService service) {
        this.service = service;
    }

    @PostMapping
    public Result<PlaygroundSessionVO> create(@RequestBody @Valid PlaygroundSessionCreateDTO request) {
        return new Result<PlaygroundSessionVO>().ok(service.create(SecurityUser.getUserId(), request));
    }

    @GetMapping("/{id}")
    public Result<PlaygroundSessionVO> get(@PathVariable String id) {
        return new Result<PlaygroundSessionVO>().ok(service.get(SecurityUser.getUserId(), id));
    }

    @PostMapping("/{id}/inputs")
    public Result<List<PlaygroundEventVO>> input(@PathVariable String id, @RequestBody @Valid PlaygroundInputDTO request) {
        return new Result<List<PlaygroundEventVO>>().ok(service.acceptInput(SecurityUser.getUserId(), id, request));
    }

    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable String id, @RequestParam(defaultValue = "0") long after) {
        List<PlaygroundEventVO> events = service.events(SecurityUser.getUserId(), id, after);
        SseEmitter emitter = new SseEmitter(30_000L);
        try {
            for (PlaygroundEventVO event : events) emitter.send(SseEmitter.event().id(Long.toString(event.sequence())).name("playground-event").data(event));
            emitter.complete();
        } catch (Exception exception) {
            emitter.completeWithError(exception);
        }
        return emitter;
    }

    @DeleteMapping("/{id}")
    public Result<Void> close(@PathVariable String id) {
        service.close(SecurityUser.getUserId(), id);
        return new Result<Void>().ok(null);
    }
}
