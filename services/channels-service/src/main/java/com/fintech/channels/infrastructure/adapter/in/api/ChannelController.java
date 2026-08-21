package com.fintech.channels.infrastructure.adapter.in.api;

import com.fintech.channels.application.service.ChannelService;
import com.fintech.channels.domain.Channel;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/channels")
@Tag(name = "Channels", description = "Channel configuration")
class ChannelController {

    private final ChannelService channelService;

    ChannelController(ChannelService channelService) {
        this.channelService = channelService;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'SUPPORT')")
    @Operation(summary = "List active channels")
    ResponseEntity<List<Channel>> listActiveChannels() {
        return ResponseEntity.ok(channelService.listActiveChannels());
    }
}
