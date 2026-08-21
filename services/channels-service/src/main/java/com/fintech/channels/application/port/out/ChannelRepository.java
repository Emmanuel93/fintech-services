package com.fintech.channels.application.port.out;

import com.fintech.channels.domain.Channel;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChannelRepository {
    Optional<Channel> findById(UUID channelId);
    Optional<Channel> findByChannelType(String channelType);
    List<Channel> findAllActive();
    Channel save(Channel channel);
}
