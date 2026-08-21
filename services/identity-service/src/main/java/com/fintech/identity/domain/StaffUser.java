package com.fintech.identity.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Empleado con acceso al backoffice.
 *
 * <p>A diferencia de {@link IdentityCredential} —que cuelga de un {@code partyId}, es decir de un
 * <em>cliente</em>— el sujeto aquí es la institución misma. Por eso el agregado incluye la
 * credencial: no hay un "party del empleado" al que separarla, y el ciclo de vida de la contraseña
 * es el de la cuenta. Los roles son una colección de valores del propio agregado, no entidades con
 * vida propia.
 *
 * <p><strong>SU-01:</strong> el correo es único e inmutable — identifica al empleado en la bitácora.
 * <br><strong>SU-02:</strong> un empleado siempre tiene al menos un rol; quitarle el último equivale
 * a darlo de baja y debe hacerse con {@link #disable()}.
 * <br><strong>SU-03:</strong> el registro nunca se borra — comisiones, asignaciones de cartera y
 * auditoría lo referencian por id mucho después de la baja.
 * <br><strong>SU-04:</strong> el colaborador empresarial siempre pertenece a un distribuidor; el
 * personal interno, a ninguno.
 */
@Entity
@Table(schema = "identity", name = "staff_users")
public class StaffUser {

    @Id
    @Column(name = "staff_user_id", nullable = false, updatable = false)
    private UUID staffUserId;

    @Column(name = "email", nullable = false, unique = true, updatable = false)
    private String email;

    @Column(name = "full_name", nullable = false)
    private String fullName;

    /**
     * CURP del empleado. Nulable: el personal dado de alta antes de que se capturara no la tiene y
     * su registro no se borra (SU-03). La necesita la bitácora de auditoría, que debe identificar
     * plenamente a quien actúa y no sólo por un UUID.
     */
    @Column(name = "curp", length = 18)
    private String curp;

    @Enumerated(EnumType.STRING)
    @Column(name = "employee_type", nullable = false)
    private EmployeeType employeeType;

    /**
     * Distribuidor al que pertenece el colaborador; null para personal interno. Es el eje de alcance
     * de datos para {@link EmployeeType#COLABORADOR_EMPRESARIAL}.
     */
    @Column(name = "distributor_party_id")
    private UUID distributorPartyId;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            schema = "identity",
            name = "staff_user_roles",
            joinColumns = @JoinColumn(name = "staff_user_id"))
    @Column(name = "role", nullable = false)
    @Enumerated(EnumType.STRING)
    private Set<StaffRole> roles = EnumSet.noneOf(StaffRole.class);

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private StaffStatus status;

    @Column(name = "failed_attempts", nullable = false)
    private int failedAttempts;

    @Column(name = "locked_until")
    private Instant lockedUntil;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    /** IPv4 (hasta 15 chars) o IPv6 (hasta 45 chars). */
    @Column(name = "last_login_ip", length = 45)
    private String lastLoginIp;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected StaffUser() {}

    public static StaffUser create(String email,
                                   String fullName,
                                   String curp,
                                   EmployeeType employeeType,
                                   UUID distributorPartyId,
                                   Set<StaffRole> roles,
                                   String passwordHash) {
        requireAtLeastOneRole(roles);
        requireConsistentEmployer(employeeType, distributorPartyId);
        var s = new StaffUser();
        s.staffUserId         = UUID.randomUUID();
        s.email               = email.trim().toLowerCase();
        s.fullName            = fullName;
        s.curp                = normalizeCurp(curp);
        s.employeeType        = employeeType;
        s.distributorPartyId  = distributorPartyId;
        s.roles               = EnumSet.copyOf(roles);
        s.passwordHash        = passwordHash;
        s.status              = StaffStatus.ACTIVE;
        s.failedAttempts      = 0;
        s.createdAt           = Instant.now();
        s.updatedAt           = Instant.now();
        return s;
    }

    // ── Sesión ────────────────────────────────────────────────────────────

    public boolean isLocked() {
        return status == StaffStatus.LOCKED
                && lockedUntil != null
                && lockedUntil.isAfter(Instant.now());
    }

    /** Solo una cuenta ACTIVE puede iniciar sesión; SUSPENDED y DISABLED quedan fuera. */
    public boolean canSignIn() {
        return status == StaffStatus.ACTIVE;
    }

    public void recordFailure(int maxAttempts, int lockDurationMinutes) {
        failedAttempts++;
        if (failedAttempts >= maxAttempts) {
            status = StaffStatus.LOCKED;
            lockedUntil = Instant.now().plus(lockDurationMinutes, ChronoUnit.MINUTES);
        }
        touch();
    }

    public void resetFailures() {
        failedAttempts = 0;
        if (status == StaffStatus.LOCKED) {
            status = StaffStatus.ACTIVE;
        }
        lockedUntil = null;
        touch();
    }

    public void recordLogin(Instant loginAt, String ipAddress) {
        this.lastLoginAt = loginAt;
        this.lastLoginIp = ipAddress;
        touch();
    }

    // ── Administración ────────────────────────────────────────────────────

    /** SU-02: nunca deja al empleado sin roles. */
    public void changeRoles(Set<StaffRole> newRoles) {
        requireAtLeastOneRole(newRoles);
        this.roles = EnumSet.copyOf(newRoles);
        touch();
    }

    public void rename(String newFullName) {
        this.fullName = newFullName;
        touch();
    }

    public void changePassword(String newPasswordHash) {
        this.passwordHash = newPasswordHash;
        resetFailures();
    }

    public void suspend() {
        guardNotDisabled();
        this.status = StaffStatus.SUSPENDED;
        touch();
    }

    public void reactivate() {
        guardNotDisabled();
        this.status = StaffStatus.ACTIVE;
        this.failedAttempts = 0;
        this.lockedUntil = null;
        touch();
    }

    /** SU-03: baja lógica — el registro se conserva para la trazabilidad histórica. */
    public void disable() {
        this.status = StaffStatus.DISABLED;
        touch();
    }

    public boolean hasRole(StaffRole role) {
        return roles.contains(role);
    }

    /** Los roles como strings, tal como viajan en el claim {@code roles} del JWT. */
    public java.util.List<String> roleNames() {
        return roles.stream().map(Enum::name).sorted().toList();
    }

    // ── Invariantes ───────────────────────────────────────────────────────

    private static void requireAtLeastOneRole(Set<StaffRole> roles) {
        if (roles == null || roles.isEmpty()) {
            throw new StaffUserWithoutRolesException();
        }
    }

    /** SU-04: el colaborador siempre pertenece a un distribuidor; el interno, a ninguno. */
    private static void requireConsistentEmployer(EmployeeType type, UUID distributorPartyId) {
        if (type == EmployeeType.COLABORADOR_EMPRESARIAL && distributorPartyId == null) {
            throw new InvalidStaffStateException(
                    "Un colaborador empresarial requiere distributorPartyId");
        }
        if (type == EmployeeType.INTERNO && distributorPartyId != null) {
            throw new InvalidStaffStateException(
                    "Un empleado interno no puede tener distributorPartyId");
        }
    }

    /**
     * La CURP se guarda en mayúsculas y sin espacios: es la forma canónica del registro civil, y el
     * CHECK de la tabla la exige así. Capturarla en minúsculas es un desliz de teclado, no un dato
     * distinto, y rechazar el alta por eso no ayuda a nadie.
     */
    private static String normalizeCurp(String curp) {
        if (curp == null) return null;
        String limpio = curp.trim().toUpperCase();
        return limpio.isEmpty() ? null : limpio;
    }

    private void guardNotDisabled() {
        if (status == StaffStatus.DISABLED) {
            throw new InvalidStaffStateException(
                    "El empleado está dado de baja y no puede cambiar de estado");
        }
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    // ── Getters ───────────────────────────────────────────────────────────

    public UUID getStaffUserId()         { return staffUserId; }
    public String getEmail()             { return email; }
    public String getFullName()          { return fullName; }
    public String getCurp()              { return curp; }
    public EmployeeType getEmployeeType(){ return employeeType; }
    public UUID getDistributorPartyId()  { return distributorPartyId; }
    public Set<StaffRole> getRoles()     { return Set.copyOf(roles); }
    public String getPasswordHash()      { return passwordHash; }
    public StaffStatus getStatus()       { return status; }
    public int getFailedAttempts()       { return failedAttempts; }
    public Instant getLockedUntil()      { return lockedUntil; }
    public Instant getLastLoginAt()      { return lastLoginAt; }
    public String getLastLoginIp()       { return lastLoginIp; }
    public Instant getCreatedAt()        { return createdAt; }
    public Instant getUpdatedAt()        { return updatedAt; }
}
