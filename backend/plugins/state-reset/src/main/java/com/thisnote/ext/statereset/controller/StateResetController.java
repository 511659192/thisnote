package com.thisnote.ext.statereset.controller;

import com.thisnote.ext.statereset.StateResetPlugin;
import com.thisnote.ext.statereset.service.StateResetService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 状态重置插件自己的端点：POST /api/plugins/state-reset/reset。
 *
 * <p>有副作用，所以是 POST + 动作路径；插件停用后由端点闸门统一拦下。
 */
@RestController
@RequestMapping("/api/plugins/" + StateResetPlugin.PLUGIN_ID)
@RequiredArgsConstructor
public class StateResetController {

    private final StateResetService stateResetService;

    @PostMapping("/reset")
    public Map<String, Object> reset() {
        stateResetService.resetAll();
        return Map.of("ok", true);
    }
}
