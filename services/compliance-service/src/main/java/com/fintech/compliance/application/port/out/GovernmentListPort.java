package com.fintech.compliance.application.port.out;

import com.fintech.compliance.domain.CsvLayout;
import com.fintech.compliance.domain.WatchlistKind;

import java.io.InputStream;
import java.time.LocalDate;

/**
 * De dónde llega el archivo de una lista.
 *
 * <p>El puerto entrega <b>bytes y layout</b>, no filas ya interpretadas. La interpretación es del
 * dominio ({@link com.fintech.compliance.domain.WatchlistCsvParser}) y tiene que ser la misma
 * viniera el archivo de un portal de gobierno, de un SFTP o del mock: si cada adaptador parseara lo
 * suyo, el mock probaría un parser distinto del que corre en producción — que es la forma más cara
 * de tener pruebas verdes y un fallo real.
 */
public interface GovernmentListPort {

    /** Cómo se lee el archivo de esa lista. Se declara, no se adivina. */
    CsvLayout layoutFor(String sourceCode);

    /**
     * El archivo publicado para esa fecha.
     *
     * <p>Se pide por fecha de publicación y no «el último» para que una carga sea reproducible: al
     * investigar un veredicto hay que poder traer exactamente el archivo contra el que se decidió.
     */
    InputStream download(String sourceCode, WatchlistKind kind, LocalDate publishedOn);

    /** Quién está entregando, para que el log diga si fue el mock o el proveedor real. */
    String describeProvider();
}
