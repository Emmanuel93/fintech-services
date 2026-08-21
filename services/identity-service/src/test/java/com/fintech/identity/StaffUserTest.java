package com.fintech.identity;

import com.fintech.identity.domain.EmployeeType;
import com.fintech.identity.domain.InvalidStaffStateException;
import com.fintech.identity.domain.StaffRole;
import com.fintech.identity.domain.StaffStatus;
import com.fintech.identity.domain.StaffUser;
import com.fintech.identity.domain.StaffUserWithoutRolesException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

/** Invariantes del agregado StaffUser (SU-01 … SU-04). */
class StaffUserTest {

    private static StaffUser internal(StaffRole... roles) {
        return StaffUser.create("Ana.Torres@Kredius.MX", "Ana Torres", "TOAA900101HDFRNN09",
                EmployeeType.INTERNO, null, Set.of(roles), "$2a$10$hash");
    }

    // ── Alta ──────────────────────────────────────────────────────────────

    @Test
    void create_normalizesEmailAndStartsActive() {
        StaffUser user = internal(StaffRole.EXECUTIVE);

        assertThat(user.getEmail()).isEqualTo("ana.torres@kredius.mx");
        assertThat(user.getStatus()).isEqualTo(StaffStatus.ACTIVE);
        assertThat(user.canSignIn()).isTrue();
        assertThat(user.getFailedAttempts()).isZero();
    }

    @Test
    void create_withoutRoles_throws() {
        assertThatThrownBy(() -> StaffUser.create("x@kredius.mx", "X", null,
                EmployeeType.INTERNO, null, Set.of(), "$2a$10$hash"))
                .isInstanceOf(StaffUserWithoutRolesException.class);
    }

    // ── SU-04: coherencia empleado ↔ distribuidor ─────────────────────────

    @Test
    void create_collaboratorWithoutDistributor_throws() {
        assertThatThrownBy(() -> StaffUser.create("x@aliado.mx", "X", null,
                EmployeeType.COLABORADOR_EMPRESARIAL, null,
                Set.of(StaffRole.EXECUTIVE), "$2a$10$hash"))
                .isInstanceOf(InvalidStaffStateException.class)
                .hasMessageContaining("distributorPartyId");
    }

    @Test
    void create_internalWithDistributor_throws() {
        assertThatThrownBy(() -> StaffUser.create("x@kredius.mx", "X", null,
                EmployeeType.INTERNO, UUID.randomUUID(),
                Set.of(StaffRole.EXECUTIVE), "$2a$10$hash"))
                .isInstanceOf(InvalidStaffStateException.class);
    }

    // ── Bloqueo por intentos fallidos ─────────────────────────────────────

    @Test
    void recordFailure_reachingMaxAttempts_locksAccount() {
        StaffUser user = internal(StaffRole.EXECUTIVE);

        for (int i = 0; i < 3; i++) user.recordFailure(3, 30);

        assertThat(user.isLocked()).isTrue();
        assertThat(user.getStatus()).isEqualTo(StaffStatus.LOCKED);
        assertThat(user.getLockedUntil()).isAfter(Instant.now());
    }

    @Test
    void recordFailure_belowMaxAttempts_staysActive() {
        StaffUser user = internal(StaffRole.EXECUTIVE);

        user.recordFailure(3, 30);
        user.recordFailure(3, 30);

        assertThat(user.isLocked()).isFalse();
        assertThat(user.getFailedAttempts()).isEqualTo(2);
    }

    @Test
    void resetFailures_afterLock_returnsToActive() {
        StaffUser user = internal(StaffRole.EXECUTIVE);
        user.recordFailure(1, 30);
        assertThat(user.isLocked()).isTrue();

        user.resetFailures();

        assertThat(user.getStatus()).isEqualTo(StaffStatus.ACTIVE);
        assertThat(user.getLockedUntil()).isNull();
    }

    @Test
    void resetFailures_doesNotResurrectSuspendedAccount() {
        StaffUser user = internal(StaffRole.EXECUTIVE);
        user.suspend();

        user.resetFailures();

        assertThat(user.getStatus()).isEqualTo(StaffStatus.SUSPENDED);
        assertThat(user.canSignIn()).isFalse();
    }

    // ── SU-02: siempre con al menos un rol ────────────────────────────────

    @Test
    void changeRoles_toEmpty_throws() {
        StaffUser user = internal(StaffRole.EXECUTIVE);

        assertThatThrownBy(() -> user.changeRoles(Set.of()))
                .isInstanceOf(StaffUserWithoutRolesException.class);
        assertThat(user.getRoles()).containsExactly(StaffRole.EXECUTIVE);
    }

    @Test
    void changeRoles_replacesTheWholeSet() {
        StaffUser user = internal(StaffRole.EXECUTIVE);

        user.changeRoles(Set.of(StaffRole.OPS_SUPERVISOR, StaffRole.RISK_ANALYST));

        assertThat(user.getRoles())
                .containsExactlyInAnyOrder(StaffRole.OPS_SUPERVISOR, StaffRole.RISK_ANALYST);
        assertThat(user.roleNames()).containsExactly("OPS_SUPERVISOR", "RISK_ANALYST");
    }

    // ── SU-03: la baja es lógica y terminal ───────────────────────────────

    @Test
    void disable_thenReactivate_throws() {
        StaffUser user = internal(StaffRole.EXECUTIVE);
        user.disable();

        assertThatThrownBy(user::reactivate).isInstanceOf(InvalidStaffStateException.class);
        assertThat(user.getStatus()).isEqualTo(StaffStatus.DISABLED);
    }

    @Test
    void suspend_thenReactivate_restoresAccess() {
        StaffUser user = internal(StaffRole.EXECUTIVE);
        user.recordFailure(5, 30);
        user.suspend();

        user.reactivate();

        assertThat(user.canSignIn()).isTrue();
        assertThat(user.getFailedAttempts()).isZero();
    }

    @Test
    void changePassword_clearsLockout() {
        StaffUser user = internal(StaffRole.EXECUTIVE);
        user.recordFailure(1, 30);

        user.changePassword("$2a$10$newhash");

        assertThat(user.getPasswordHash()).isEqualTo("$2a$10$newhash");
        assertThat(user.isLocked()).isFalse();
    }
}
