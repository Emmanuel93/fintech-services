package com.fintech.channelbackoffice.infrastructure.adapter.in.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * El BFF reenvía el alta de personal a identity <b>tal cual la recibe</b>, así que el contrato de
 * entrada tiene que llevar la CURP.
 *
 * <p>No la llevaba: identity ya la aceptaba y la consola la mandaba, pero el record del canal no
 * tenía el campo, de modo que Jackson lo descartaba en silencio y el empleado se creaba sin ella.
 * El alta devolvía 201 y {@code curp: null}, que es la peor forma de fallar —nada que revisar en un
 * log, ningún error que investigar, sólo un dato que no está—. Con 58 empleados sembrados así, la
 * bitácora habría seguido identificándolos por un UUID sin que nada lo delatara.
 */
class StaffDirectoryControllerCurpTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void elContratoDeAlta_llevaLaCurpHastaIdentity() throws Exception {
        String entrante = """
                {"email":"ana.torres@kredius.mx","fullName":"Ana Torres",
                 "curp":"TOAA900101HDFRNN09","employeeType":"INTERNO",
                 "distributorPartyId":null,"roles":["EXECUTIVE"],
                 "password":"UnaClaveLarga2026"}""";

        var request = mapper.readValue(entrante, StaffDirectoryController.CreateStaffRequest.class);

        assertThat(request.curp()).isEqualTo("TOAA900101HDFRNN09");

        // Lo que el canal reenvía a identity es este mismo objeto: si el campo no viaja en la
        // serialización, el empleado se crea sin CURP aunque la petición la traiga.
        @SuppressWarnings("unchecked")
        Map<String, Object> reenviado = mapper.convertValue(request, Map.class);
        assertThat(reenviado).containsEntry("curp", "TOAA900101HDFRNN09");
    }

    @Test
    void laCurpEsOpcional_paraNoRomperAltasExistentes() throws Exception {
        String sinCurp = """
                {"email":"ana.torres@kredius.mx","fullName":"Ana Torres",
                 "employeeType":"INTERNO","distributorPartyId":null,
                 "roles":["EXECUTIVE"],"password":"UnaClaveLarga2026"}""";

        var request = mapper.readValue(sinCurp, StaffDirectoryController.CreateStaffRequest.class);

        assertThat(request.curp()).isNull();
        assertThat(request.email()).isEqualTo("ana.torres@kredius.mx");
    }
}
