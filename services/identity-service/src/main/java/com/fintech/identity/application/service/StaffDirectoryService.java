package com.fintech.identity.application.service;

import com.fintech.identity.application.CreateStaffCommand;
import com.fintech.identity.application.port.in.FindStaffUseCase;
import com.fintech.identity.application.port.in.ManageStaffUseCase;
import com.fintech.identity.application.port.out.StaffUserRepository;
import com.fintech.identity.application.port.out.TokenRepository;
import com.fintech.identity.domain.StaffRole;
import com.fintech.identity.domain.StaffStatus;
import com.fintech.identity.domain.StaffUser;
import com.fintech.identity.domain.StaffUserAlreadyExistsException;
import com.fintech.identity.domain.StaffUserNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Administración del directorio de personal. Toda operación aquí es de un ADMIN sobre otro
 * empleado; el propio interesado no se administra a sí mismo.
 *
 * <p>Quitarle el acceso a alguien —suspenderlo o darlo de baja— revoca sus sesiones vivas en el
 * acto. Sin eso el empleado seguiría operando hasta que expirara su access token.
 */
@Service
@Transactional
public class StaffDirectoryService implements ManageStaffUseCase, FindStaffUseCase {

    private static final Logger log = LoggerFactory.getLogger(StaffDirectoryService.class);

    private final StaffUserRepository staffUserRepository;
    private final TokenRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;

    public StaffDirectoryService(StaffUserRepository staffUserRepository,
                                 TokenRepository tokenRepository,
                                 PasswordEncoder passwordEncoder) {
        this.staffUserRepository = staffUserRepository;
        this.tokenRepository     = tokenRepository;
        this.passwordEncoder     = passwordEncoder;
    }

    // ── ManageStaffUseCase ────────────────────────────────────────────────

    @Override
    public StaffUser create(CreateStaffCommand command) {
        String email = command.email().trim().toLowerCase();
        if (staffUserRepository.existsByEmail(email)) {
            throw new StaffUserAlreadyExistsException(email);
        }
        StaffUser user = StaffUser.create(
                email,
                command.fullName(),
                command.curp(),
                command.employeeType(),
                command.distributorPartyId(),
                command.roles(),
                passwordEncoder.encode(command.password()));

        StaffUser saved = staffUserRepository.save(user);
        log.info("Staff created staffUserId={} email={} roles={}",
                saved.getStaffUserId(), email, saved.roleNames());
        return saved;
    }

    @Override
    public StaffUser changeRoles(UUID staffUserId, Set<StaffRole> roles) {
        StaffUser user = getById(staffUserId);
        user.changeRoles(roles);
        StaffUser saved = staffUserRepository.save(user);
        log.info("Staff roles changed staffUserId={} roles={}", staffUserId, saved.roleNames());
        return saved;
    }

    @Override
    public StaffUser changePassword(UUID staffUserId, String newPassword) {
        StaffUser user = getById(staffUserId);
        user.changePassword(passwordEncoder.encode(newPassword));
        StaffUser saved = staffUserRepository.save(user);
        // Cambiar la contraseña cierra las demás sesiones: es la reacción esperada a un robo.
        tokenRepository.revokeAllByPartyId(staffUserId);
        log.info("Staff password changed staffUserId={}", staffUserId);
        return saved;
    }

    @Override
    public StaffUser suspend(UUID staffUserId) {
        StaffUser user = getById(staffUserId);
        user.suspend();
        StaffUser saved = staffUserRepository.save(user);
        tokenRepository.revokeAllByPartyId(staffUserId);
        log.info("Staff suspended staffUserId={}", staffUserId);
        return saved;
    }

    @Override
    public StaffUser reactivate(UUID staffUserId) {
        StaffUser user = getById(staffUserId);
        user.reactivate();
        StaffUser saved = staffUserRepository.save(user);
        log.info("Staff reactivated staffUserId={}", staffUserId);
        return saved;
    }

    @Override
    public StaffUser disable(UUID staffUserId) {
        StaffUser user = getById(staffUserId);
        user.disable();
        StaffUser saved = staffUserRepository.save(user);
        tokenRepository.revokeAllByPartyId(staffUserId);
        log.info("Staff disabled staffUserId={}", staffUserId);
        return saved;
    }

    // ── FindStaffUseCase ──────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public StaffUser getById(UUID staffUserId) {
        return staffUserRepository.findById(staffUserId)
                .orElseThrow(() -> new StaffUserNotFoundException(staffUserId.toString()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<StaffUser> find(StaffStatus status, StaffRole role) {
        // Filtrado en memoria: el directorio son decenas de registros, no las decenas de miles de
        // clientes y cuentas que sí exigen filtros e índices en la base.
        return staffUserRepository.findAll().stream()
                .filter(u -> status == null || u.getStatus() == status)
                .filter(u -> role == null || u.hasRole(role))
                .sorted(java.util.Comparator.comparing(StaffUser::getFullName))
                .toList();
    }
}
