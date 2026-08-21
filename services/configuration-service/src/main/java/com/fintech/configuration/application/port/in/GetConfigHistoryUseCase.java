package com.fintech.configuration.application.port.in;

import com.fintech.configuration.domain.ConfigParameter;
import java.util.List;

public interface GetConfigHistoryUseCase {
    /** Returns all versions of a parameter ordered by version desc. */
    List<ConfigParameter> getHistory(String paramKey);
}
