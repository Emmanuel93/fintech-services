package com.fintech.channels.application.service;

import com.fintech.channels.application.port.out.ChannelRepository;
import com.fintech.channels.domain.Channel;
import com.fintech.channels.domain.ChannelNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class ChannelService {

    private final ChannelRepository channelRepository;

    public ChannelService(ChannelRepository channelRepository) {
        this.channelRepository = channelRepository;
    }

    public List<Channel> listActiveChannels() {
        return channelRepository.findAllActive();
    }

    public Channel getById(UUID channelId) {
        return channelRepository.findById(channelId)
                .orElseThrow(() -> new ChannelNotFoundException(channelId.toString()));
    }
}
