package com.fintech.beneficiary.application.port.out;

/**
 * Lee los datos impresos en la INE.
 *
 * <p>Hoy no hay proveedor de OCR contratado y el adaptador es un stub. El puerto existe para que el
 * día que lo haya cambie un bean y no el flujo: la web ya manda las dos caras y recibe la
 * extracción para precargar «Tus datos», que ella revisa y corrige antes de enviar.
 */
public interface IneReaderPort {

    Extraction read(Request request);

    /**
     * @param declaredFullName el nombre como lo tecleó el distribuidor. Un OCR real no lo
     *                         necesita para leer, pero sirve para avisar si la credencial es de
     *                         otra persona; el stub lo usa para producir una lectura verosímil.
     * @param phone            el celular de la liga
     */
    record Request(byte[] front, String frontContentType, byte[] back, String backContentType,
                   String declaredFullName, String phone) {}

    /** Lo que se leyó. Los campos que el OCR no pueda leer llegan nulos y ella los captura. */
    record Extraction(String firstName, String lastName1, String lastName2,
                      String curp, String rfc, String dateOfBirth,
                      String street, String exteriorNumber, String neighborhood,
                      String municipality, String city, String state, String postalCode) {}
}
