package com.fintech.collections.application.port.in;

import com.fintech.collections.domain.BureauReport;
import java.util.List;

public interface ListBureauReportsUseCase {
    List<BureauReport> listPending();
}
