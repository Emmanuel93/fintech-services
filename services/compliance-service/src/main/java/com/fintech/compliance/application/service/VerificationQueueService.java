package com.fintech.compliance.application.service;

import com.fintech.compliance.application.port.out.PersonVerificationRepository;
import com.fintech.compliance.application.port.out.VerificationQueueRepository;
import com.fintech.compliance.domain.IdentityStatus;
import com.fintech.compliance.domain.PersonVerification;
import com.fintech.compliance.domain.ScreeningStatus;
import com.fintech.compliance.domain.VerificationNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

/**
 * Lo que la mesa de cumplimiento lee: la cola y la ficha.
 *
 * <p>Sólo lectura. Los dictámenes siguen en {@link PersonVerificationService}, que es quien
 * republica el veredicto; separar la lectura evita que la consulta de una bandeja tenga a mano los
 * métodos que firman.
 */
@Service
@Transactional(readOnly = true)
public class VerificationQueueService {

    /** Tope de página: una bandeja se navega, no se descarga. */
    static final int MAX_PAGE_SIZE = 200;

    private final VerificationQueueRepository queue;
    private final PersonVerificationRepository verifications;

    public VerificationQueueService(VerificationQueueRepository queue,
                                    PersonVerificationRepository verifications) {
        this.queue         = queue;
        this.verifications = verifications;
    }

    /**
     * La cola, de lo más viejo a lo más nuevo.
     *
     * <p>En ese orden y no al revés: quien lleva más tiempo esperando dictamen es quien lleva más
     * tiempo bloqueado en todas las compuertas, y una bandeja que muestra primero lo reciente
     * esconde justo a esa persona en la última página.
     */
    public Page<PersonVerification> search(Set<IdentityStatus> identity, Set<ScreeningStatus> screening,
                                           String text, int page, int size) {
        return queue.search(identity, screening, text,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                        Sort.by(Sort.Direction.ASC, "createdAt")));
    }

    public PersonVerification byId(UUID verificationId) {
        return verifications.findById(verificationId)
                .orElseThrow(() -> new VerificationNotFoundException(verificationId));
    }
}
