package zixuan.modules.companion.playground.controller;

import java.util.List;

import org.apache.shiro.authz.annotation.RequiresPermissions;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import zixuan.common.utils.Result;
import zixuan.modules.companion.playground.dto.PlaygroundInputDTO;
import zixuan.modules.companion.playground.dto.PlaygroundSessionCreateDTO;
import zixuan.modules.companion.playground.service.CompanionPlaygroundService;
import zixuan.modules.companion.playground.vo.PlaygroundEventVO;
import zixuan.modules.companion.playground.vo.PlaygroundSessionVO;
import zixuan.modules.security.user.SecurityUser;

@RestController
@RequestMapping("/companion/playground/sessions")
public class CompanionPlaygroundController {
    private final CompanionPlaygroundService service;

    public CompanionPlaygroundController(CompanionPlaygroundService service) {
        this.service = service;
    }

    @PostMapping
    @RequiresPermissions("sys:role:normal")
    public Result<PlaygroundSessionVO> create(@RequestBody @Valid PlaygroundSessionCreateDTO request) {
        return new Result<PlaygroundSessionVO>().ok(service.create(SecurityUser.getUserId(), request));
    }

    @GetMapping("/{id}")
    @RequiresPermissions("sys:role:normal")
    public Result<PlaygroundSessionVO> get(@PathVariable String id) {
        return new Result<PlaygroundSessionVO>().ok(service.get(SecurityUser.getUserId(), id));
    }

    @PostMapping("/{id}/inputs")
    @RequiresPermissions("sys:role:normal")
    public Result<List<PlaygroundEventVO>> input(@PathVariable String id, @RequestBody @Valid PlaygroundInputDTO request) {
        return new Result<List<PlaygroundEventVO>>().ok(service.acceptInput(SecurityUser.getUserId(), id, request));
    }

    @GetMapping("/{id}/events")
    public Result<List<PlaygroundEventVO>> events(@PathVariable String id, @RequestParam(defaultValue = "0") long after) {
        return new Result<List<PlaygroundEventVO>>().ok(service.events(SecurityUser.getUserId(), id, after));
    }

    @DeleteMapping("/{id}")
    @RequiresPermissions("sys:role:normal")
    public Result<Void> close(@PathVariable String id) {
        service.close(SecurityUser.getUserId(), id);
        return new Result<Void>().ok(null);
    }
}
