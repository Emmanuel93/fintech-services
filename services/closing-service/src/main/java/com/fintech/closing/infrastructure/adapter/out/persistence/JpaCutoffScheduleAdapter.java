package com.fintech.closing.infrastructure.adapter.out.persistence;

import com.fintech.closing.application.port.out.CutoffScheduleRepository;
import com.fintech.closing.domain.CutoffSchedule;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JpaCutoffScheduleAdapter implements CutoffScheduleRepository {

    private final SpringDataCutoffScheduleRepository jpa;

    JpaCutoffScheduleAdapter(SpringDataCutoffScheduleRepository jpa) { this.jpa = jpa; }

    @Override
    public Optional<CutoffSchedule> find(UUID creditAccountId, int cycleNumber) {
        return jpa.findById(new CutoffSchedule.Key(creditAccountId, cycleNumber));
    }

    @Override public List<CutoffSchedule> findDueOn(LocalDate d)        { return jpa.findDueOn(d); }
    @Override public List<CutoffSchedule> findByAccount(UUID id)        { return jpa.findByAccount(id); }
    @Override public CutoffSchedule save(CutoffSchedule s)              { return jpa.save(s); }
    @Override public int saveAll(List<CutoffSchedule> s)                { jpa.saveAll(s); return s.size(); }
}
